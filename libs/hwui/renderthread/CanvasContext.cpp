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

#include "CanvasContext.h"

#include "EglManager.h"
#include "RenderThread.h"
#include "../AnimationContext.h"
#include "../Caches.h"
#include "../DeferredLayerUpdater.h"
#include "../renderstate/RenderState.h"
#include "../renderstate/Stencil.h"
#include "../LayerRenderer.h"
#include "../OpenGLRenderer.h"

#include <algorithm>
#include <private/hwui/DrawGlInfo.h>
#include <strings.h>

#define TRIM_MEMORY_COMPLETE 80
#define TRIM_MEMORY_UI_HIDDEN 20

namespace android {
namespace uirenderer {
namespace renderthread {

CanvasContext::CanvasContext(RenderThread& thread, bool translucent,
        RenderNode* rootRenderNode, IContextFactory* contextFactory)
        : mRenderThread(thread)
        , mEglManager(thread.eglManager())
        , mEglSurface(EGL_NO_SURFACE)
        , mBufferPreserved(false)
        , mSwapBehavior(kSwap_default)
        , mOpaque(!translucent)
        , mCanvas(nullptr)
        , mHaveNewSurface(false)
        , mAnimationContext(contextFactory->createAnimationContext(mRenderThread.timeLord()))
        , mRootRenderNode(rootRenderNode)
        , mCurrentFrameInfo(nullptr) {
    mRenderThread.renderState().registerCanvasContext(this);
}

CanvasContext::~CanvasContext() {
    destroy();
    mRenderThread.renderState().unregisterCanvasContext(this);
}

void CanvasContext::destroy() {
    stopDrawing();
    setSurface(nullptr);
    freePrefetechedLayers();
    destroyHardwareResources();
    mAnimationContext->destroy();
    if (mCanvas) {
        delete mCanvas;
        mCanvas = nullptr;
    }
}

void CanvasContext::setSurface(ANativeWindow* window) {
    ATRACE_CALL();

    mNativeWindow = window;

    if (mEglSurface != EGL_NO_SURFACE) {
        mEglManager.destroySurface(mEglSurface);
        mEglSurface = EGL_NO_SURFACE;
    }

    if (window) {
        mEglSurface = mEglManager.createSurface(window);
    }

    if (mEglSurface != EGL_NO_SURFACE) {
        const bool preserveBuffer = (mSwapBehavior != kSwap_discardBuffer);
        mBufferPreserved = mEglManager.setPreserveBuffer(mEglSurface, preserveBuffer);
        mHaveNewSurface = true;
        makeCurrent();
    } else {
        mRenderThread.removeFrameCallback(this);
    }
}

void CanvasContext::swapBuffers() {
    if (CC_UNLIKELY(!mEglManager.swapBuffers(mEglSurface))) {
        setSurface(nullptr);
    }
    mHaveNewSurface = false;
}

void CanvasContext::requireSurface() {
    LOG_ALWAYS_FATAL_IF(mEglSurface == EGL_NO_SURFACE,
            "requireSurface() called but no surface set!");
    makeCurrent();
}

void CanvasContext::setSwapBehavior(SwapBehavior swapBehavior) {
    mSwapBehavior = swapBehavior;
}

bool CanvasContext::initialize(ANativeWindow* window) {
    setSurface(window);
    if (mCanvas) return false;
    mCanvas = new OpenGLRenderer(mRenderThread.renderState());
    mCanvas->initProperties();
    return true;
}

void CanvasContext::updateSurface(ANativeWindow* window) {
    setSurface(window);
}

bool CanvasContext::pauseSurface(ANativeWindow* window) {
    return mRenderThread.removeFrameCallback(this);
}

// TODO: don't pass viewport size, it's automatic via EGL
void CanvasContext::setup(int width, int height, const Vector3& lightCenter, float lightRadius,
        uint8_t ambientShadowAlpha, uint8_t spotShadowAlpha) {
    if (!mCanvas) return;
    mCanvas->initLight(lightCenter, lightRadius, ambientShadowAlpha, spotShadowAlpha);
}

void CanvasContext::setOpaque(bool opaque) {
    mOpaque = opaque;
}

void CanvasContext::makeCurrent() {
    // TODO: Figure out why this workaround is needed, see b/13913604
    // In the meantime this matches the behavior of GLRenderer, so it is not a regression
    mHaveNewSurface |= mEglManager.makeCurrent(mEglSurface);
}

void CanvasContext::processLayerUpdate(DeferredLayerUpdater* layerUpdater) {
    bool success = layerUpdater->apply();
    LOG_ALWAYS_FATAL_IF(!success, "Failed to update layer!");
    if (layerUpdater->backingLayer()->deferredUpdateScheduled) {
        mCanvas->pushLayerUpdate(layerUpdater->backingLayer());
    }
}

void CanvasContext::prepareTree(TreeInfo& info, int64_t* uiFrameInfo) {
    mRenderThread.removeFrameCallback(this);

    mCurrentFrameInfo = &mFrames.next();
    mCurrentFrameInfo->importUiThreadInfo(uiFrameInfo);
    mCurrentFrameInfo->markSyncStart();

    info.damageAccumulator = &mDamageAccumulator;
    info.renderer = mCanvas;
    if (mPrefetechedLayers.size() && info.mode == TreeInfo::MODE_FULL) {
        info.canvasContext = this;
    }
    mAnimationContext->startFrame(info.mode);
    mRootRenderNode->prepareTree(info);
    mAnimationContext->runRemainingAnimations(info);

    if (info.canvasContext) {
        freePrefetechedLayers();
    }

    if (CC_UNLIKELY(!mNativeWindow.get())) {
        info.out.canDrawThisFrame = false;
        return;
    }

    int runningBehind = 0;
    // TODO: This query is moderately expensive, investigate adding some sort
    // of fast-path based off when we last called eglSwapBuffers() as well as
    // last vsync time. Or something.
    mNativeWindow->query(mNativeWindow.get(),
            NATIVE_WINDOW_CONSUMER_RUNNING_BEHIND, &runningBehind);
    info.out.canDrawThisFrame = !runningBehind;

    if (info.out.hasAnimations || !info.out.canDrawThisFrame) {
        if (!info.out.requiresUiRedraw) {
            // If animationsNeedsRedraw is set don't bother posting for an RT anim
            // as we will just end up fighting the UI thread.
            mRenderThread.postFrameCallback(this);
        }
    }
}

void CanvasContext::stopDrawing() {
    mRenderThread.removeFrameCallback(this);
}

void CanvasContext::notifyFramePending() {
    ATRACE_CALL();
    mRenderThread.pushBackFrameCallback(this);
}

void CanvasContext::draw() {
    LOG_ALWAYS_FATAL_IF(!mCanvas || mEglSurface == EGL_NO_SURFACE,
            "drawRenderNode called on a context with no canvas or surface!");

    profiler().markPlaybackStart();
    mCurrentFrameInfo->markIssueDrawCommandsStart();

    SkRect dirty;
    mDamageAccumulator.finish(&dirty);

    EGLint width, height;
    mEglManager.beginFrame(mEglSurface, &width, &height);
    if (width != mCanvas->getViewportWidth() || height != mCanvas->getViewportHeight()) {
        mCanvas->setViewport(width, height);
        dirty.setEmpty();
    } else if (!mBufferPreserved || mHaveNewSurface) {
        dirty.setEmpty();
    } else {
        if (!dirty.isEmpty() && !dirty.intersect(0, 0, width, height)) {
            ALOGW("Dirty " RECT_STRING " doesn't intersect with 0 0 %d %d ?",
                    SK_RECT_ARGS(dirty), width, height);
            dirty.setEmpty();
        }
        profiler().unionDirty(&dirty);
    }

    if (!dirty.isEmpty()) {
        mCanvas->prepareDirty(dirty.fLeft, dirty.fTop,
                dirty.fRight, dirty.fBottom, mOpaque);
    } else {
        mCanvas->prepare(mOpaque);
    }

    Rect outBounds;
    mCanvas->drawRenderNode(mRootRenderNode.get(), outBounds);

    profiler().draw(mCanvas);

    bool drew = mCanvas->finish();

    profiler().markPlaybackEnd();

    // Even if we decided to cancel the frame, from the perspective of jank
    // metrics the frame was swapped at this point
    mCurrentFrameInfo->markSwapBuffers();

    if (drew) {
        swapBuffers();
    } else {
        mEglManager.cancelFrame();
    }

    // TODO: Use a fence for real completion?
    mCurrentFrameInfo->markFrameCompleted();
    mRenderThread.jankTracker().addFrame(*mCurrentFrameInfo);
    profiler().finishFrame();
}

// Called by choreographer to do an RT-driven animation
void CanvasContext::doFrame() {
    if (CC_UNLIKELY(!mCanvas || mEglSurface == EGL_NO_SURFACE)) {
        return;
    }

    ATRACE_CALL();

    profiler().startFrame();
    int64_t frameInfo[UI_THREAD_FRAME_INFO_SIZE];
    UiFrameInfoBuilder(frameInfo)
        .addFlag(FrameInfoFlags::kRTAnimation)
        .setVsync(mRenderThread.timeLord().computeFrameTimeNanos(),
                mRenderThread.timeLord().latestVsync());

    TreeInfo info(TreeInfo::MODE_RT_ONLY, mRenderThread.renderState());
    prepareTree(info, frameInfo);
    if (info.out.canDrawThisFrame) {
        draw();
    }
}

void CanvasContext::invokeFunctor(RenderThread& thread, Functor* functor) {
    ATRACE_CALL();
    DrawGlInfo::Mode mode = DrawGlInfo::kModeProcessNoContext;
    if (thread.eglManager().hasEglContext()) {
        thread.eglManager().requireGlContext();
        mode = DrawGlInfo::kModeProcess;
    }

    thread.renderState().invokeFunctor(functor, mode, nullptr);
}

void CanvasContext::markLayerInUse(RenderNode* node) {
    if (mPrefetechedLayers.erase(node)) {
        node->decStrong(nullptr);
    }
}

static void destroyPrefetechedNode(RenderNode* node) {
    ALOGW("Incorrectly called buildLayer on View: %s, destroying layer...", node->getName());
    node->destroyHardwareResources();
    node->decStrong(nullptr);
}

void CanvasContext::freePrefetechedLayers() {
    if (mPrefetechedLayers.size()) {
        requireGlContext();
        std::for_each(mPrefetechedLayers.begin(), mPrefetechedLayers.end(), destroyPrefetechedNode);
        mPrefetechedLayers.clear();
    }
}

void CanvasContext::buildLayer(RenderNode* node) {
    ATRACE_CALL();
    if (!mEglManager.hasEglContext() || !mCanvas) {
        return;
    }
    requireGlContext();
    // buildLayer() will leave the tree in an unknown state, so we must stop drawing
    stopDrawing();

    TreeInfo info(TreeInfo::MODE_FULL, mRenderThread.renderState());
    info.damageAccumulator = &mDamageAccumulator;
    info.renderer = mCanvas;
    info.runAnimations = false;
    node->prepareTree(info);
    SkRect ignore;
    mDamageAccumulator.finish(&ignore);
    // Tickle the GENERIC property on node to mark it as dirty for damaging
    // purposes when the frame is actually drawn
    node->setPropertyFieldsDirty(RenderNode::GENERIC);

    mCanvas->markLayersAsBuildLayers();
    mCanvas->flushLayerUpdates();

    node->incStrong(nullptr);
    mPrefetechedLayers.insert(node);
}

bool CanvasContext::copyLayerInto(DeferredLayerUpdater* layer, SkBitmap* bitmap) {
    requireGlContext();
    layer->apply();
    return LayerRenderer::copyLayer(mRenderThread.renderState(), layer->backingLayer(), bitmap);
}

void CanvasContext::destroyHardwareResources() {
    stopDrawing();
    if (mEglManager.hasEglContext()) {
        requireGlContext();
        freePrefetechedLayers();
        mRootRenderNode->destroyHardwareResources();
        Caches::getInstance().flush(Caches::kFlushMode_Layers);
    }
}

void CanvasContext::trimMemory(RenderThread& thread, int level) {
    // No context means nothing to free
    if (!thread.eglManager().hasEglContext()) return;

    ATRACE_CALL();
    thread.eglManager().requireGlContext();
    if (level >= TRIM_MEMORY_COMPLETE) {
        Caches::getInstance().flush(Caches::kFlushMode_Full);
        thread.eglManager().destroy();
    } else if (level >= TRIM_MEMORY_UI_HIDDEN) {
        Caches::getInstance().flush(Caches::kFlushMode_Moderate);
    }
}

void CanvasContext::runWithGlContext(RenderTask* task) {
    requireGlContext();
    task->run();
}

Layer* CanvasContext::createTextureLayer() {
    requireSurface();
    return LayerRenderer::createTextureLayer(mRenderThread.renderState());
}

void CanvasContext::requireGlContext() {
    mEglManager.requireGlContext();
}

void CanvasContext::setTextureAtlas(RenderThread& thread,
        const sp<GraphicBuffer>& buffer, int64_t* map, size_t mapSize) {
    thread.eglManager().setTextureAtlas(buffer, map, mapSize);
}

void CanvasContext::dumpFrames(int fd) {
    FILE* file = fdopen(fd, "a");
    fprintf(file, "\n\n---PROFILEDATA---");
    for (size_t i = 0; i < mFrames.size(); i++) {
        FrameInfo& frame = mFrames[i];
        if (frame[FrameInfoIndex::kSyncStart] == 0) {
            continue;
        }
        fprintf(file, "\n");
        for (int i = 0; i < FrameInfoIndex::kNumIndexes; i++) {
            fprintf(file, "%" PRId64 ",", frame[i]);
        }
    }
    fprintf(file, "\n---PROFILEDATA---\n\n");
    fflush(file);
}

void CanvasContext::resetFrameStats() {
    mFrames.clear();
    mRenderThread.jankTracker().reset();
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
