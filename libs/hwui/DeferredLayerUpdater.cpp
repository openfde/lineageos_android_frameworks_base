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

#define ALIGN_M(value, base) (((value) + ((base)-1)) & ~((base)-1))
#define YUV_R_COEFF 298
#define YUV_G_COEFF1 100
#define YUV_G_COEFF2 208
#define YUV_B_COEFF 516
#define YUV_R_V_COEFF 409
#define YUV_BIAS 128
void yv12_to_rgb565(const unsigned char* yv12_data, int width, int height, unsigned char* rgb_output) {

    // 计算对齐后的UV宽度
    int aligned_width = ALIGN_M(width, 256);
    int aligned_half_width = aligned_width / 2;

    // 获取各个平面指针（YV12格式：Y平面 -> V平面 -> U平面）
    const uint8_t* y_plane = yv12_data;
    const uint8_t* v_plane = y_plane + aligned_width * height;
    const uint8_t* u_plane = v_plane + aligned_half_width * (height / 2);

    // 预计算循环中的常量
    int aligned_y_stride = aligned_width;
    int aligned_uv_stride = aligned_half_width;

    // 遍历所有行
    for (int y = 0; y < height; y++) {
        // 计算UV行索引
        int uv_y = y / 2;
        int uv_row_offset = uv_y * aligned_uv_stride;

        // Y行起始位置
        const uint8_t* y_row = y_plane + y * aligned_y_stride;

        // RGB行起始位置
        uint16_t* rgb_row = (uint16_t*)(rgb_output + y * aligned_width * 2);

        int x;

        // 处理有效像素区域 (0 到 width-1)
        for (x = 0; x < width; x++) {
            // 计算UV列索引
            int uv_x = x / 2;
            int uv_idx = uv_row_offset + uv_x;

            // 获取YUV值
            int y_val = y_row[x];
            int u_val = u_plane[uv_idx];
            int v_val = v_plane[uv_idx];

            // YUV到RGB转换（快速整数算法）
            int c = y_val - 16;
            int d = u_val - 128;
            int e = v_val - 128;

            // 快速转换
            int r = (YUV_R_COEFF * c + YUV_R_V_COEFF * e + YUV_BIAS) >> 8;
            int g = (YUV_R_COEFF * c - YUV_G_COEFF1 * d - YUV_G_COEFF2 * e + YUV_BIAS) >> 8;
            int b = (YUV_R_COEFF * c + YUV_B_COEFF * d + YUV_BIAS) >> 8;

            // 钳位到0-255范围
            r = (r < 0) ? 0 : ((r > 255) ? 255 : r);
            g = (g < 0) ? 0 : ((g > 255) ? 255 : g);
            b = (b < 0) ? 0 : ((b > 255) ? 255 : b);

            // 转换为RGB565并存储
            rgb_row[x] = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);
        }

        // 填充右侧区域 (width 到 aligned_width-1) 为黑色
        for (; x < aligned_width; x++) {
            rgb_row[x] = 0x0000;  // RGB565黑色
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
                sp<GraphicBuffer> dst_gb;
                sp<GraphicBuffer> graphicBuffer = AHardwareBuffer_to_GraphicBuffer(hardwareBuffer);
                if (graphicBuffer->getPixelFormat() == HAL_PIXEL_FORMAT_YV12) {
                    if (graphicBuffer->needCovertFormat()) {
                        void* data = nullptr;
                        int result = graphicBuffer->lock(GRALLOC_USAGE_SW_READ_OFTEN, &data);
                        if (result == 0 && data != nullptr) {
                            unsigned char* yuv_data = (unsigned char*)data;
                            int width = graphicBuffer->getWidth();
                            int height = graphicBuffer->getHeight();
                            dst_gb = new GraphicBuffer(
                                    width, height, HAL_PIXEL_FORMAT_RGB_565,
                                    GRALLOC_USAGE_HW_TEXTURE | GRALLOC_USAGE_HW_RENDER);

                            void* dst_data = nullptr;
                            int dst_result = dst_gb->lock(GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN, &dst_data);
                            if (dst_result == 0 && dst_data != nullptr) {
                                unsigned char* rgb565 = (unsigned char*) dst_data;
                                yv12_to_rgb565(yuv_data, width, height, rgb565);
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
