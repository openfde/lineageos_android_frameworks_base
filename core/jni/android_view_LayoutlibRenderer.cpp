/*
 * Copyright 2024 The Android Open Source Project
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

#define LOG_TAG "LayoutlibRenderer"
#define LOG_NDEBUG 1

#include <gui/BufferQueue.h>
#include <gui/IGraphicBufferConsumer.h>
#include <gui/IGraphicBufferProducer.h>
#include <ui/GraphicBuffer.h>

#include "android_runtime/android_view_Surface.h"
#include "core_jni_helpers.h"
#include "jni.h"
#include <utils/Log.h>
#include <android_runtime/Log.h>
#include <log/log_main.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
JNIEnv* mEnv;
static jclass bridge;
static jclass layoutLog;
static jmethodID getLogId;
static jmethodID logMethodId;

namespace android {

jfieldID gNativeContextFieldId;

void RLayoutlibLogger(base::LogId, base::LogSeverity severity, const char* tag, const char* file,
                     unsigned int line, const char* message) {
    JNIEnv* env = mEnv;
    jint logPrio = severity;
    jstring tagString = env->NewStringUTF(tag);
    jstring messageString = env->NewStringUTF(message);

    jobject bridgeLog = env->CallStaticObjectMethod(bridge, getLogId);

    env->CallVoidMethod(bridgeLog, logMethodId, logPrio, tagString, messageString);

    env->DeleteLocalRef(tagString);
    env->DeleteLocalRef(messageString);
    env->DeleteLocalRef(bridgeLog);
}

void RLayoutlibAborter(const char* abort_message) {
    // Layoutlib should not call abort() as it would terminate Studio.
    // Throw an exception back to Java instead.
    JNIEnv* env = mEnv;
    jniThrowRuntimeException(env, "The Android framework has encountered a fatal error xd");
}

/**
 * Class to store information needed by the Layoutlib renderer
 */
class JNILayoutlibRendererContext : public RefBase {
public:
    void onFirstRef() {
    }
    void onLastStrongRef(const void* id) {

    }
    bool onIncStrongAttempted(uint32_t flags, const void* id) {
        return false;
    }
    void onLastWeakRef(const void* id) {

    }
    ~JNILayoutlibRendererContext() override {
        if (mBufferConsumer != nullptr) {
            mBufferConsumer.clear();
        }
    }

    void setBufferConsumer(const sp<IGraphicBufferConsumer>& consumer) {
        mBufferConsumer = consumer;
    }

    IGraphicBufferConsumer* getBufferConsumer() {
        return mBufferConsumer.get();
    }

private:
    sp<IGraphicBufferConsumer> mBufferConsumer;
};

static jobject android_view_LayoutlibRenderer_createSurface(JNIEnv* env, jobject thiz) {

    sp<IGraphicBufferProducer> gbProducer;
    sp<IGraphicBufferConsumer> gbConsumer;
    BufferQueue::createBufferQueue(&gbProducer, &gbConsumer);

    // // Save the IGraphicBufferConsumer in the context so that it can be reused for buffer creation
    sp<JNILayoutlibRendererContext> newCtx = sp<JNILayoutlibRendererContext>::make();

    newCtx->setBufferConsumer(gbConsumer);
    auto* const currentCtx = reinterpret_cast<JNILayoutlibRendererContext*>(
            env->GetLongField(thiz, gNativeContextFieldId));
    if (newCtx != nullptr) {
        // Create a strong reference to the new context to avoid it being destroyed
        newCtx->incStrong((void*)android_view_LayoutlibRenderer_createSurface);
    }
    if (currentCtx != nullptr) {
        // Delete the reference to the previous context as it is not needed and can be destroyed
        currentCtx->decStrong((void*)android_view_LayoutlibRenderer_createSurface);
    }
    env->SetLongField(thiz, gNativeContextFieldId, reinterpret_cast<jlong>(newCtx.get()));
    return android_view_Surface_createFromIGraphicBufferProducer(env, gbProducer);
}

static jobject android_view_LayoutlibRenderer_createBuffer(JNIEnv* env, jobject thiz, jint width,
                                                           jint height) {
    auto* ctx = reinterpret_cast<JNILayoutlibRendererContext*>(env->GetLongField(thiz, gNativeContextFieldId));
    if (ctx == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException", "No Rendener has been created");
        return nullptr;
    }

    // if (width <= 0 || height <= 0) {
    //     width = 512;
    //     height = 1024;
    //     jniThrowException(env, "java/lang/IllegalStateException", "No width-height has been created");
    // }

    IGraphicBufferConsumer* bufferConsumer = ctx->getBufferConsumer();
    bufferConsumer->setDefaultBufferSize(width, height);
    auto* bufferItem = new BufferItem();
    bufferConsumer->acquireBuffer(bufferItem, 0);
    sp<GraphicBuffer> buffer = bufferItem->mGraphicBuffer;
    delete bufferItem;

    int bytesPerPixel = 4;
    uint32_t dataSize = buffer->getStride() * buffer->getHeight() * bytesPerPixel;

    void* pData = nullptr;
    buffer->lockAsync(0, Rect::EMPTY_RECT, &pData, 0);
    // if (dataSize == 0) {
    //     dataSize = 3 * 512 * bytesPerPixel;
    //     jniThrowException(env, "java/lang/IllegalStateException", "No dataSize has been created");
    // }

    // if (pData == nullptr) {
    //     jniThrowException(env, "java/lang/IllegalStateException", "pData No surface has been created");
    //     return nullptr;
    // }
    jobject byteBuffer = env->NewDirectByteBuffer(pData, dataSize);
    return byteBuffer;
}

static void android_view_LayoutlibRenderer_destroy(JNIEnv* env, jobject thiz) {
    auto* const currentCtx = reinterpret_cast<JNILayoutlibRendererContext*>(
            env->GetLongField(thiz, gNativeContextFieldId));
    if (currentCtx != nullptr) {
        IGraphicBufferConsumer* bufferConsumer = currentCtx->getBufferConsumer();
        bufferConsumer->detachBuffer(0);
        currentCtx->decStrong((void*)android_view_LayoutlibRenderer_createSurface);
    }
    env->SetLongField(thiz, gNativeContextFieldId, 0);
}

static const JNINativeMethod gMethods[] = {
        {"nativeCreateSurface", "()Landroid/view/Surface;",
         (void*)android_view_LayoutlibRenderer_createSurface},
        {"nativeCreateBuffer", "(II)Ljava/nio/ByteBuffer;",
         (void*)android_view_LayoutlibRenderer_createBuffer},
        {"nativeDestroy", "()V", (void*)android_view_LayoutlibRenderer_destroy},
};

int register_android_view_LayoutlibRenderer(JNIEnv* env) {

    jclass layoutlibRendererClass = FindClassOrDie(env, "android/view/LayoutlibRenderer");
    gNativeContextFieldId = GetFieldIDOrDie(env, layoutlibRendererClass, "mNativeContext", "J");

    layoutLog = FindClassOrDie(env, "com/android/ide/common/rendering/api/ILayoutLog");
    layoutLog = MakeGlobalRefOrDie(env, layoutLog);
    logMethodId = GetMethodIDOrDie(env, layoutLog, "logAndroidFramework",
                                   "(ILjava/lang/String;Ljava/lang/String;)V");
    bridge = FindClassOrDie(env, "com/android/layoutlib/bridge/Bridge");
    bridge = MakeGlobalRefOrDie(env, bridge);
    getLogId = GetStaticMethodIDOrDie(env, bridge, "getLog",
                                      "()Lcom/android/ide/common/rendering/api/ILayoutLog;");

    return RegisterMethodsOrDie(env, "android/view/LayoutlibRenderer", gMethods, NELEM(gMethods));
}

} // namespace android
