/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include "DeferredLayerUpdater.h"

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

// TODO: Use public SurfaceTexture APIs once available and include public NDK header file instead.
#include <surfacetexture/surface_texture_platform.h>

#include "AutoBackendTextureRelease.h"
#include "Matrix.h"
#include "Properties.h"
#include "android/hdr_metadata.h"
#include "renderstate/RenderState.h"
#include "renderthread/EglManager.h"
#include "renderthread/RenderThread.h"
#include "renderthread/VulkanManager.h"

#include <ui/GraphicBuffer.h>
#include <android/AHardwareBufferHelpers.h>

using namespace android::uirenderer::renderthread;

#define ALIGN_M(value, base) (((value) + ((base)-1)) & ~((base)-1))

namespace android {
namespace uirenderer {

DeferredLayerUpdater::DeferredLayerUpdater(RenderState& renderState)
        : mRenderState(renderState)
        , mBlend(false)
        , mSurfaceTexture(nullptr, [](ASurfaceTexture*) {})
        , mTransform(nullptr)
        , mGLContextAttached(false)
        , mUpdateTexImage(false)
        , mLayer(nullptr) {
    renderState.registerContextCallback(this);
}

DeferredLayerUpdater::~DeferredLayerUpdater() {
    setTransform(nullptr);
    mRenderState.removeContextCallback(this);
    destroyLayer();
}

void DeferredLayerUpdater::setSurfaceTexture(AutoTextureRelease&& consumer) {
    mSurfaceTexture = std::move(consumer);

    GLenum target = ASurfaceTexture_getCurrentTextureTarget(mSurfaceTexture.get());
    LOG_ALWAYS_FATAL_IF(target != GL_TEXTURE_2D && target != GL_TEXTURE_EXTERNAL_OES,
                        "set unsupported SurfaceTexture with target %x", target);
}

void DeferredLayerUpdater::onContextDestroyed() {
    destroyLayer();
}

void DeferredLayerUpdater::destroyLayer() {
    if (!mLayer) {
        return;
    }

    if (mSurfaceTexture.get() && mGLContextAttached) {
        ASurfaceTexture_releaseConsumerOwnership(mSurfaceTexture.get());
        mGLContextAttached = false;
    }

    mLayer->postDecStrong();

    mLayer = nullptr;

    for (auto& [index, slot] : mImageSlots) {
        slot.clear(mRenderState.getRenderThread().getGrContext());
    }
    mImageSlots.clear();
}

void DeferredLayerUpdater::setPaint(const SkPaint* paint) {
    mAlpha = PaintUtils::getAlphaDirect(paint);
    mMode = PaintUtils::getBlendModeDirect(paint);
    if (paint) {
        mColorFilter = paint->refColorFilter();
    } else {
        mColorFilter.reset();
    }
}

status_t DeferredLayerUpdater::createReleaseFence(bool useFenceSync, EGLSyncKHR* eglFence,
                                                  EGLDisplay* display, int* releaseFence,
                                                  void* handle) {
    *display = EGL_NO_DISPLAY;
    DeferredLayerUpdater* dlu = (DeferredLayerUpdater*)handle;
    RenderState& renderState = dlu->mRenderState;
    status_t err;
    if (Properties::getRenderPipelineType() == RenderPipelineType::SkiaGL) {
        EglManager& eglManager = renderState.getRenderThread().eglManager();
        *display = eglManager.eglDisplay();
        err = eglManager.createReleaseFence(useFenceSync, eglFence, releaseFence);
    } else {
        int previousSlot = dlu->mCurrentSlot;
        if (previousSlot != -1) {
            dlu->mImageSlots[previousSlot].releaseQueueOwnership(
                    renderState.getRenderThread().getGrContext());
        }
        err = renderState.getRenderThread().vulkanManager().createReleaseFence(
                releaseFence, renderState.getRenderThread().getGrContext());
    }
    return err;
}

status_t DeferredLayerUpdater::fenceWait(int fence, void* handle) {
    // Wait on the producer fence for the buffer to be ready.
    status_t err;
    DeferredLayerUpdater* dlu = (DeferredLayerUpdater*)handle;
    RenderState& renderState = dlu->mRenderState;
    if (Properties::getRenderPipelineType() == RenderPipelineType::SkiaGL) {
        err = renderState.getRenderThread().eglManager().fenceWait(fence);
    } else {
        err = renderState.getRenderThread().vulkanManager().fenceWait(
                fence, renderState.getRenderThread().getGrContext());
    }
    return err;
}

void yv12_to_bgra(const unsigned char* yv12_data, int width, int height, int y_stride,
                    unsigned char* rgb_output) {
    int uv_stride = y_stride / 2;

    size_t y_size = y_stride * height;
    size_t uv_size = uv_stride * (height / 2);

    const uint8_t* y_plane = yv12_data;
    const uint8_t* v_plane = yv12_data + y_size;
    const uint8_t* u_plane = v_plane + uv_size;

    const int coef_rv = 359;   // 1.402 * 256
    const int coef_gu = 88;    // 0.344 * 256
    const int coef_gv = 183;   // 0.714 * 256
    const int coef_bu = 454;   // 1.773 * 256

    for (int y = 0; y < height; y++) {
        const uint8_t* src_y = y_plane + y * y_stride;
        uint8_t* dst = rgb_output + y * width * 4;

        int uv_y = y / 2;
        const uint8_t* src_v = v_plane + uv_y * uv_stride;
        const uint8_t* src_u = u_plane + uv_y * uv_stride;

        for (int x = 0; x < width; x++) {
            int uv_x = x / 2;
            int Y = src_y[x];
            int V = src_v[uv_x];
            int U = src_u[uv_x];

            int C = Y;
            int D = U - 128;
            int E = V - 128;

            int R = (C * 298 + coef_rv * E + 128) >> 8;
            int G = (C * 298 - coef_gu * D - coef_gv * E + 128) >> 8;
            int B = (C * 298 + coef_bu * D + 128) >> 8;

            if (R < 0) R = 0; else if (R > 255) R = 255;
            if (G < 0) G = 0; else if (G > 255) G = 255;
            if (B < 0) B = 0; else if (B > 255) B = 255;

            dst[4*x + 0] = (uint8_t)B;
            dst[4*x + 1] = (uint8_t)G;
            dst[4*x + 2] = (uint8_t)R;
            dst[4*x + 3] = 0xFF;   // Alpha
        }
    }
}

void DeferredLayerUpdater::apply() {
    if (!mLayer) {
        mLayer = new Layer(mRenderState, mColorFilter, mAlpha, mMode);
    }

    mLayer->setColorFilter(mColorFilter);
    mLayer->setAlpha(mAlpha, mMode);

    if (mSurfaceTexture.get()) {
        if (!mGLContextAttached) {
            mGLContextAttached = true;
            mUpdateTexImage = true;
            ASurfaceTexture_takeConsumerOwnership(mSurfaceTexture.get());
        }
        if (mUpdateTexImage) {
            mUpdateTexImage = false;
            float transformMatrix[16];
            android_dataspace dataspace;
            AHdrMetadataType hdrMetadataType;
            android_cta861_3_metadata cta861_3;
            android_smpte2086_metadata smpte2086;
            int slot;
            bool newContent = false;
            ARect currentCrop;
            uint32_t outTransform;
            // Note: ASurfaceTexture_dequeueBuffer discards all but the last frame. This
            // is necessary if the SurfaceTexture queue is in synchronous mode, and we
            // cannot tell which mode it is in.
            AHardwareBuffer* hardwareBuffer = ASurfaceTexture_dequeueBuffer(
                    mSurfaceTexture.get(), &slot, &dataspace, &hdrMetadataType, &cta861_3,
                    &smpte2086, transformMatrix, &outTransform, &newContent, createReleaseFence,
                    fenceWait, this, &currentCrop);

            if (hardwareBuffer) {
                int srcWidth = 0;
                int srcHeight = 0;
                sp<GraphicBuffer> dst_gb;
                sp<GraphicBuffer> graphicBuffer = AHardwareBuffer_to_GraphicBuffer(hardwareBuffer);
                if (graphicBuffer->getPixelFormat() == HAL_PIXEL_FORMAT_YV12) {
                    if (graphicBuffer->needConvertFormat()) {
                        void* data = nullptr;
                        int result = graphicBuffer->lock(GRALLOC_USAGE_SW_READ_OFTEN, &data);
                        if (result == 0 && data != nullptr) {
                            unsigned char* yuv_data = (unsigned char*)data;
                            int width = graphicBuffer->getWidth();
                            int height = graphicBuffer->getHeight();
                            int stride = graphicBuffer->getStride();

                            srcWidth = width;
                            srcHeight = height;
                            width = ALIGN_M(width,64);

                            dst_gb = new GraphicBuffer(
                                    width, height, HAL_PIXEL_FORMAT_BGRA_8888,
                                    GRALLOC_USAGE_HW_TEXTURE | GRALLOC_USAGE_HW_RENDER
                                        | GRALLOC_USAGE_PRIVATE_0);

                            void* dst_data = nullptr;
                            int dst_result = dst_gb->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, &dst_data);
                            if (dst_result == 0 && dst_data != nullptr) {
                                unsigned char* bgra = (unsigned char*) dst_data;
                                yv12_to_bgra(yuv_data, width, height, stride, bgra);
                            }
                            if (dst_gb != NULL) {
                                dst_gb->unlock();
                            }
                            graphicBuffer->unlock();
                        }
                    }
                }
                AHardwareBuffer* new_hardwareBuffer = hardwareBuffer;
                if (dst_gb != NULL) {
                    new_hardwareBuffer = AHardwareBuffer_from_GraphicBuffer(dst_gb.get());
                }

                mCurrentSlot = slot;
                sk_sp<SkImage> layerImage = mImageSlots[slot].createIfNeeded(
                        new_hardwareBuffer, dataspace, newContent,
                        mRenderState.getRenderThread().getGrContext());
                AHardwareBuffer_Desc bufferDesc;
                AHardwareBuffer_describe(hardwareBuffer, &bufferDesc);
                // unref to match the ref added by ASurfaceTexture_dequeueBuffer. eglCreateImageKHR
                // (invoked by createIfNeeded) will add a ref to the AHardwareBuffer.
                AHardwareBuffer_release(hardwareBuffer);
                if (layerImage.get()) {
                    // force filtration if buffer size != layer size
                    bool forceFilter =
                            mWidth != layerImage->width() || mHeight != layerImage->height();
                    if (dst_gb != NULL) {
                        if (dst_gb->getUsage() & GRALLOC_USAGE_PRIVATE_0) {
                            if (srcWidth != layerImage->width() || srcHeight != layerImage->height()) {
                                currentCrop.right = srcWidth;
                                currentCrop.bottom = srcHeight;
                            }
                        }
                    }
                    SkRect currentCropRect =
                            SkRect::MakeLTRB(currentCrop.left, currentCrop.top, currentCrop.right,
                                             currentCrop.bottom);

                    float maxLuminanceNits = -1.f;
                    if (hdrMetadataType & HDR10_SMPTE2086) {
                        maxLuminanceNits = std::max(smpte2086.maxLuminance, maxLuminanceNits);
                    }

                    if (hdrMetadataType & HDR10_CTA861_3) {
                        maxLuminanceNits =
                                std::max(cta861_3.maxContentLightLevel, maxLuminanceNits);
                    }
                    mLayer->setBufferFormat(bufferDesc.format);
                    updateLayer(forceFilter, layerImage, outTransform, currentCropRect,
                                maxLuminanceNits);
                }
            }
        }

        if (mTransform) {
            mLayer->getTransform() = *mTransform;
            setTransform(nullptr);
        }
    }
}

void DeferredLayerUpdater::updateLayer(bool forceFilter, const sk_sp<SkImage>& layerImage,
                                       const uint32_t transform, SkRect currentCrop,
                                       float maxLuminanceNits) {
    mLayer->setBlend(mBlend);
    mLayer->setForceFilter(forceFilter);
    mLayer->setSize(mWidth, mHeight);
    mLayer->setCurrentCropRect(currentCrop);
    mLayer->setWindowTransform(transform);
    mLayer->setImage(layerImage);
    mLayer->setMaxLuminanceNits(maxLuminanceNits);
}

void DeferredLayerUpdater::detachSurfaceTexture() {
    if (mSurfaceTexture.get()) {
        destroyLayer();
        mSurfaceTexture = nullptr;
    }
}

sk_sp<SkImage> DeferredLayerUpdater::ImageSlot::createIfNeeded(AHardwareBuffer* buffer,
                                                               android_dataspace dataspace,
                                                               bool forceCreate,
                                                               GrDirectContext* context) {
    if (!mTextureRelease || !mTextureRelease->getImage().get() || dataspace != mDataspace ||
        forceCreate || mBuffer != buffer) {
        if (buffer != mBuffer) {
            clear(context);
        }

        if (!buffer) {
            return nullptr;
        }

        if (!mTextureRelease) {
            mTextureRelease = new AutoBackendTextureRelease(context, buffer);
        } else {
            mTextureRelease->newBufferContent(context);
        }

        mDataspace = dataspace;
        mBuffer = buffer;
        mTextureRelease->makeImage(buffer, dataspace, context);
    }
    return mTextureRelease ? mTextureRelease->getImage() : nullptr;
}

void DeferredLayerUpdater::ImageSlot::clear(GrDirectContext* context) {
    if (mTextureRelease) {
        if (Properties::getRenderPipelineType() == RenderPipelineType::SkiaVulkan) {
            this->releaseQueueOwnership(context);
        }
        // The following unref counteracts the initial mUsageCount of 1, set by default initializer.
        mTextureRelease->unref(true);
        mTextureRelease = nullptr;
    }

    mBuffer = nullptr;
}

void DeferredLayerUpdater::ImageSlot::releaseQueueOwnership(GrDirectContext* context) {
    LOG_ALWAYS_FATAL_IF(Properties::getRenderPipelineType() != RenderPipelineType::SkiaVulkan);
    if (mTextureRelease) {
        mTextureRelease->releaseQueueOwnership(context);
    }
}

} /* namespace uirenderer */
} /* namespace android */
