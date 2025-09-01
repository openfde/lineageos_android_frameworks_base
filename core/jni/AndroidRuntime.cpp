/*
 * Copyright (C) 2006 The Android Open Source Project
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

#define ATRACE_TAG ATRACE_TAG_DALVIK
#define LOG_TAG "AndroidRuntime"
#define LOG_NDEBUG 1

#include <android-base/macros.h>
#include <android-base/parsebool.h>
#include <android-base/properties.h>
#include <android/graphics/jni_runtime.h>
#include <android_runtime/AndroidRuntime.h>
#include <assert.h>
#include <binder/IBinder.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/Parcel.h>
#include <malloc.h>
#include <cutils/properties.h>
#include <dirent.h>
#include <dlfcn.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/JniInvocation.h>
#include <server_configurable_flags/get_flags.h>
#include <signal.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <utils/Log.h>
#include <utils/Trace.h>
#include <utils/misc.h>
#include <utils/threads.h>

#include <string>
#include <vector>

#include "android_util_Binder.h"
#include "jni.h"

#include "android_view_InputDevice.h"
#include "core_jni_helpers.h"

#include <fcntl.h>
#include <sys/mman.h>


#include <iostream>

using namespace android;
using android::base::GetBoolProperty;
using android::base::GetProperty;
using android::base::ParseBool;
using android::base::ParseBoolResult;

static JNIEnv* mJniEnv;

extern int register_android_os_Binder(JNIEnv* env);

extern int register_android_graphics_Bitmap(JNIEnv*);
extern int register_android_graphics_BitmapFactory(JNIEnv*);
extern int register_android_graphics_ByteBufferStreamAdaptor(JNIEnv* env);
extern int register_android_graphics_CreateJavaOutputStreamAdaptor(JNIEnv* env);
extern int register_android_graphics_Graphics(JNIEnv* env);
extern int register_android_graphics_ImageDecoder(JNIEnv*);
extern int register_android_graphics_Interpolator(JNIEnv* env);
extern int register_android_graphics_MaskFilter(JNIEnv* env);
extern int register_android_graphics_NinePatch(JNIEnv*);
extern int register_android_graphics_PathEffect(JNIEnv* env);
extern int register_android_graphics_Shader(JNIEnv* env);
extern int register_android_graphics_RenderEffect(JNIEnv* env);
extern int register_android_graphics_Typeface(JNIEnv* env);

namespace android {

/*
 * JNI-based registration functions.  Note these are properly contained in
 * namespace android.
 */
extern int register_android_content_AssetManager(JNIEnv* env);
extern int register_android_util_EventLog(JNIEnv* env);
extern int register_android_util_Log(JNIEnv* env);
extern int register_android_content_StringBlock(JNIEnv* env);
extern int register_android_content_XmlBlock(JNIEnv* env);
extern int register_android_content_res_ApkAssets(JNIEnv* env);
extern int register_android_database_CursorWindow(JNIEnv* env);
extern int register_android_database_SQLiteConnection(JNIEnv* env);
extern int register_android_database_SQLiteGlobal(JNIEnv* env);
extern int register_android_database_SQLiteDebug(JNIEnv* env);
extern int register_android_os_MessageQueue(JNIEnv* env);
extern int register_android_os_Parcel(JNIEnv* env);
extern int register_android_os_SystemProperties(JNIEnv *env);
extern int register_android_os_SystemClock(JNIEnv* env);
extern int register_android_os_Trace(JNIEnv* env);
extern int register_android_os_FileObserver(JNIEnv *env);
extern int register_android_text_AndroidCharacter(JNIEnv *env);
extern int register_android_util_jar_StrictJarFile(JNIEnv* env);
extern int register_android_view_InputDevice(JNIEnv* env);
extern int register_android_view_KeyCharacterMap(JNIEnv *env);
extern int register_android_view_KeyEvent(JNIEnv* env);
extern int register_android_view_MotionEvent(JNIEnv* env);
extern int register_android_view_VelocityTracker(JNIEnv* env);
extern int register_android_view_ThreadedRenderer(JNIEnv* env);
extern int register_android_graphics_HardwareBufferRenderer(JNIEnv* env);
extern int register_android_animation_PropertyValuesHolder(JNIEnv *env);
extern int register_com_android_internal_util_VirtualRefBasePtr(JNIEnv *env);
extern int register_android_graphics_Region(JNIEnv *env);
extern int register_android_view_LayoutlibRenderer(JNIEnv *env);
extern int register_android_view_Surface(JNIEnv *env);

extern int register_android_graphics_Canvas(JNIEnv* env);
extern int register_android_graphics_ColorFilter(JNIEnv* env);
extern int register_android_graphics_ColorSpace(JNIEnv* env);
extern int register_android_graphics_DrawFilter(JNIEnv* env);
extern int register_android_graphics_FontFamily(JNIEnv* env);
extern int register_android_graphics_Matrix(JNIEnv* env);
extern int register_android_graphics_Paint(JNIEnv* env);
extern int register_android_graphics_Path(JNIEnv* env);
extern int register_android_graphics_PathMeasure(JNIEnv* env);
extern int register_android_graphics_Picture(JNIEnv* env);
//extern int register_android_graphics_Region(JNIEnv* env);
extern int register_android_graphics_animation_NativeInterpolatorFactory(JNIEnv* env);
extern int register_android_graphics_animation_RenderNodeAnimator(JNIEnv* env);
extern int register_android_graphics_drawable_AnimatedVectorDrawable(JNIEnv* env);
extern int register_android_graphics_drawable_VectorDrawable(JNIEnv* env);
extern int register_android_graphics_fonts_Font(JNIEnv* env);
extern int register_android_graphics_fonts_FontFamily(JNIEnv* env);
extern int register_android_graphics_text_LineBreaker(JNIEnv* env);
extern int register_android_graphics_text_MeasuredText(JNIEnv* env);
extern int register_android_util_PathParser(JNIEnv* env);
extern int register_android_view_RenderNode(JNIEnv* env);
extern int register_android_view_DisplayListCanvas(JNIEnv* env);
extern int register_android_graphics_text_TextShaper(JNIEnv* env);
extern int register_android_text_Hyphenator(JNIEnv* env);

//

#if 0
extern int register_libcore_util_NativeAllocationRegistry_Delegate(JNIEnv *env);


typedef void (*FreeFunction)(void*);

static void NativeAllocationRegistry_Delegate_nativeApplyFreeFunction(JNIEnv*, jclass,
                                                                      jlong freeFunction,
                                                                      jlong ptr) {
    void* nativePtr = reinterpret_cast<void*>(static_cast<uintptr_t>(ptr));
    FreeFunction nativeFreeFunction =
            reinterpret_cast<FreeFunction>(static_cast<uintptr_t>(freeFunction));
    nativeFreeFunction(nativePtr);
}

static JNINativeMethod gMethods[] = {
        NATIVE_METHOD(NativeAllocationRegistry_Delegate, nativeApplyFreeFunction, "(JJ)V"),
};

int register_libcore_util_NativeAllocationRegistry_Delegate(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "libcore/util/NativeAllocationRegistry_Delegate", gMethods,
                                    NELEM(gMethods));
}
#endif

// Namespace for Android Runtime flags applied during boot time.
static const char* RUNTIME_NATIVE_BOOT_NAMESPACE = "runtime_native_boot";
// Feature flag name to enable/disable generational garbage collection in ART's
// Concurrent Copying (CC) garbage collector.
static const char* ENABLE_GENERATIONAL_CC = "enable_generational_cc";
// Runtime option enabling generational garbage collection in ART's Concurrent
// Copying (CC) garbage collector.
static const char* kGenerationalCCRuntimeOption = "-Xgc:generational_cc";
// Runtime option disabling generational garbage collection in ART's Concurrent
// Copying (CC) garbage collector.
static const char* kNoGenerationalCCRuntimeOption = "-Xgc:nogenerational_cc";

// Phenotype property name for enabling profiling the boot class path.
static const char* PROFILE_BOOT_CLASS_PATH = "profilebootclasspath";

// Feature flag name for running the JIT in Zygote experiment, b/119800099.
// TODO: Rename the server-level flag or remove.
static const char* ENABLE_JITZYGOTE_IMAGE = "enable_apex_image";
// Flag to pass to the runtime when using the JIT Zygote image.
static const char* kJitZygoteImageOption = "-Xforcejitzygote";

// Feature flag name for disabling lock profiling.
static const char* DISABLE_LOCK_PROFILING = "disable_lock_profiling";
// Runtime option disabling lock profiling.
static const char* kLockProfThresholdRuntimeOption = "-Xlockprofthreshold:0";

static AndroidRuntime* gCurRuntime = NULL;

// ----------------------------------------------------------------------

/*static*/ JavaVM* AndroidRuntime::mJavaVM = NULL;

AndroidRuntime::AndroidRuntime(char* argBlockStart, const size_t argBlockLength) :
        mExitWithoutCleanup(false),
        mArgBlockStart(argBlockStart),
        mArgBlockLength(argBlockLength)
{
    // init_android_graphics();

    // Pre-allocate enough space to hold a fair number of options.
    // mOptions.setCapacity(20);

    assert(gCurRuntime == NULL);        // one per process
    gCurRuntime = this;
}

AndroidRuntime::~AndroidRuntime()
{
}

/*
 * The VM calls this through the "exit" hook.
 */
static void runtime_exit(int code)
{
    gCurRuntime->exit(code);
}

/*
 * The VM calls this through the "vfprintf" hook.
 *
 * We ignore "fp" and just write the results to the log file.
 */
static void runtime_vfprintf(FILE* fp, const char* format, va_list ap)
{
    LOG_PRI_VA(ANDROID_LOG_INFO, "vm-printf", format, ap);
}

/**
 * The VM calls this when mutex contention debugging is enabled to
 * determine whether or not the blocked thread was a "sensitive thread"
 * for user responsiveness/smoothess.
 *
 * Our policy for this is whether or not we're tracing any StrictMode
 * events on this thread (which we might've inherited via Binder calls
 * into us)
 */
static bool runtime_isSensitiveThread() {
    IPCThreadState* state = IPCThreadState::selfOrNull();
    return state && state->getStrictModePolicy() != 0;
}

static int hasDir(const char* dir)
{
    struct stat s;
    int res = stat(dir, &s);
    if (res == 0) {
        return S_ISDIR(s.st_mode);
    }
    return 0;
}

static bool hasFile(const char* file) {
    struct stat s;
    int res = stat(file, &s);
    if (res == 0) {
        return S_ISREG(s.st_mode);
    }
    return false;
}


char* AndroidRuntime::toSlashClassName(const char* className)
{
    char* result = strdup(className);
    for (char* cp = result; *cp != '\0'; cp++) {
        if (*cp == '.') {
            *cp = '/';
        }
    }
    return result;
}

/*
 * Start the Android runtime.  This involves starting the virtual machine
 * and calling the "static void main(String[] args)" method in the class
 * named by "className".
 *
 * Passes the main function two arguments, the class name and the specified
 * options string.
 */
void AndroidRuntime::start(const char* className, const Vector<String8>& options, bool zygote)
{
    ALOGD(">>>>>> START %s uid %d <<<<<<\n",
            className != NULL ? className : "(unknown)", getuid());

    JNIEnv* env = mJniEnv;
    /*
     * Register android functions.
     */
    if (startReg(env) < 0) {
        ALOGE("Unable to register all android natives\n");
        return;
    } else {
        ALOGE("start Reg success!!");
        return ;
    }

    ALOGE("Shutting down VM\n");
    if (mJavaVM->DetachCurrentThread() != JNI_OK)
        ALOGW("Warning: unable to detach main thread\n");
    if (mJavaVM->DestroyJavaVM() != 0)
        ALOGW("Warning: VM did not shut down cleanly\n");
}

void AndroidRuntime::exit(int code)
{
    if (mExitWithoutCleanup) {
        ALOGI("VM exiting with result code %d, cleanup skipped.", code);
    } else {
        ALOGI("VM exiting with result code %d.", code);
        onExit(code);
    }

#ifdef __ANDROID_CLANG_COVERAGE__
    // When compiled with coverage, a function is registered with atexit to call
    // `__llvm_profile_write_file` when the process exit.
    // For Clang code coverage to work, call exit instead of _exit to run hooks
    // registered with atexit.
    ::exit(code);
#else
    ::_exit(code);
#endif
}

void AndroidRuntime::onJavaVmCreated(JavaVM* vm)
{
    AndroidRuntime::mJavaVM = vm;
}

void AndroidRuntime::onVmCreated(JNIEnv* env)
{
    // If AndroidRuntime had anything to do here, we'd have done it in 'start'.
    mJniEnv = env;
}

/*static*/ JavaVM* AndroidRuntime::getJavaVM() {
    return AndroidRuntime::mJavaVM;
}

extern "C" JavaVM* AndroidRuntimeGetJavaVM() {
    return AndroidRuntime::getJavaVM();
}

/*
 * Get the JNIEnv pointer for this thread.
 *
 * Returns NULL if the slot wasn't allocated or populated.
 */
/*static*/ JNIEnv* AndroidRuntime::getJNIEnv()
{
    JNIEnv* env;
    JavaVM* vm = AndroidRuntime::getJavaVM();
    assert(vm != NULL);

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK)
        return NULL;
    return env;
}

/*
 * Makes the current thread visible to the VM.
 *
 * The JNIEnv pointer returned is only valid for the current thread, and
 * thus must be tucked into thread-local storage.
 */
static int javaAttachThread(const char* threadName, JNIEnv** pEnv)
{
    JavaVMAttachArgs args;
    JavaVM* vm;
    jint result;

    vm = AndroidRuntime::getJavaVM();
    assert(vm != NULL);

    args.version = JNI_VERSION_1_4;
    args.name = (char*) threadName;
    args.group = NULL;

    result = vm->AttachCurrentThread(pEnv, (void*) &args);
    if (result != JNI_OK)
        ALOGI("NOTE: attach of thread '%s' failed\n", threadName);

    return result;
}

/*
 * Detach the current thread from the set visible to the VM.
 */
static int javaDetachThread(void)
{
    JavaVM* vm;
    jint result;

    vm = AndroidRuntime::getJavaVM();
    assert(vm != NULL);

    result = vm->DetachCurrentThread();
    if (result != JNI_OK)
        ALOGE("ERROR: thread detach failed\n");
    return result;
}

/*
 * When starting a native thread that will be visible from the VM, we
 * bounce through this to get the right attach/detach action.
 * Note that this function calls free(args)
 */
/*static*/ int AndroidRuntime::javaThreadShell(void* args) {
    void* start = ((void**)args)[0];
    void* userData = ((void **)args)[1];
    char* name = (char*) ((void **)args)[2];        // we own this storage
    free(args);
    JNIEnv* env;
    int result;

    /* hook us into the VM */
    if (javaAttachThread(name, &env) != JNI_OK)
        return -1;

    /* start the thread running */
    result = (*(android_thread_func_t)start)(userData);

    /* unhook us */
    javaDetachThread();
    free(name);

    return result;
}

/*
 * This is invoked from androidCreateThreadEtc() via the callback
 * set with androidSetCreateThreadFunc().
 *
 * We need to create the new thread in such a way that it gets hooked
 * into the VM before it really starts executing.
 */
/*static*/ int AndroidRuntime::javaCreateThreadEtc(
                                android_thread_func_t entryFunction,
                                void* userData,
                                const char* threadName,
                                int32_t threadPriority,
                                size_t threadStackSize,
                                android_thread_id_t* threadId)
{
    void** args = (void**) malloc(3 * sizeof(void*));   // javaThreadShell must free
    int result;

    LOG_ALWAYS_FATAL_IF(threadName == nullptr, "threadName not provided to javaCreateThreadEtc");

    args[0] = (void*) entryFunction;
    args[1] = userData;
    args[2] = (void*) strdup(threadName);   // javaThreadShell must free

    result = androidCreateRawThreadEtc(AndroidRuntime::javaThreadShell, args,
        threadName, threadPriority, threadStackSize, threadId);
    return result;
}

/*
 * Create a thread that is visible from the VM.
 *
 * This is called from elsewhere in the library.
 */
/*static*/ android_thread_id_t AndroidRuntime::createJavaThread(const char* name,
    void (*start)(void *), void* arg)
{
    android_thread_id_t threadId = 0;
    javaCreateThreadEtc((android_thread_func_t) start, arg, name,
        ANDROID_PRIORITY_DEFAULT, 0, &threadId);
    return threadId;
}

#if 0
static void quickTest(void* arg)
{
    const char* str = (const char*) arg;

    printf("In quickTest: %s\n", str);
}
#endif

// #ifdef NDEBUG
    #define REG_JNI(name)      { name }
    struct RegJNIRec {
        int (*mProc)(JNIEnv*);
    };
// #else
//     #define REG_JNI(name)      { name, #name }
//     struct RegJNIRec {
//         int (*mProc)(JNIEnv*);
//         const char* mName;
//     };
// #endif

typedef void (*RegJAMProc)();

static int register_jni_procs(const RegJNIRec array[], size_t count, JNIEnv* env)
{
    for (size_t i = 0; i < count; i++) {
        if (array[i].mProc(env) < 0) {
            return -1;
        }
    }
    return 0;
}

static const RegJNIRec gRegJNI[] = {
        // REG_JNI(register_android_os_SystemClock),
        REG_JNI(register_android_util_EventLog),
        REG_JNI(register_android_util_Log),
        // REG_JNI(register_android_content_AssetManager),
        REG_JNI(register_android_content_StringBlock),
        REG_JNI(register_android_content_XmlBlock),
        // REG_JNI(register_android_content_res_ApkAssets),
        REG_JNI(register_android_text_AndroidCharacter),
        REG_JNI(register_android_view_InputDevice),
        REG_JNI(register_android_view_KeyCharacterMap),
        REG_JNI(register_android_os_SystemProperties),
        // REG_JNI(register_android_os_Parcel),
        // REG_JNI(register_android_database_CursorWindow),
        // REG_JNI(register_android_database_SQLiteConnection),
        // REG_JNI(register_android_database_SQLiteGlobal),
        // REG_JNI(register_android_database_SQLiteDebug),
        // REG_JNI(register_android_os_FileObserver),
        // REG_JNI(register_android_os_MessageQueue),
        REG_JNI(register_android_os_Trace),
        REG_JNI(register_com_android_internal_util_VirtualRefBasePtr),
        // REG_JNI(register_android_util_jar_StrictJarFile),
        REG_JNI(register_android_view_KeyEvent),
        REG_JNI(register_android_view_MotionEvent),
        REG_JNI(register_android_view_VelocityTracker),
        REG_JNI(register_android_animation_PropertyValuesHolder),
        REG_JNI(register_android_graphics_Region),
        REG_JNI(register_android_view_LayoutlibRenderer),
        REG_JNI(register_android_view_Surface),
        REG_JNI(register_android_view_ThreadedRenderer),
        REG_JNI(register_android_graphics_Bitmap),
        REG_JNI(register_android_graphics_BitmapFactory),
        REG_JNI(register_android_graphics_ByteBufferStreamAdaptor),
        REG_JNI(register_android_graphics_CreateJavaOutputStreamAdaptor),
        REG_JNI(register_android_graphics_Graphics),
        REG_JNI(register_android_graphics_ImageDecoder),
        REG_JNI(register_android_graphics_Interpolator),
        REG_JNI(register_android_graphics_MaskFilter),
        REG_JNI(register_android_graphics_NinePatch),
        REG_JNI(register_android_graphics_PathEffect),
        REG_JNI(register_android_graphics_Shader),
        REG_JNI(register_android_graphics_RenderEffect),
        REG_JNI(register_android_graphics_Typeface),
        REG_JNI(register_android_graphics_Canvas),
        REG_JNI(register_android_graphics_ColorFilter),
        REG_JNI(register_android_graphics_ColorSpace),
        REG_JNI(register_android_graphics_DrawFilter),
        REG_JNI(register_android_graphics_FontFamily),
        REG_JNI(register_android_graphics_Matrix),
        REG_JNI(register_android_graphics_Paint),
        REG_JNI(register_android_graphics_Path),
        REG_JNI(register_android_graphics_PathMeasure),
        REG_JNI(register_android_graphics_Picture),
        REG_JNI(register_android_graphics_animation_NativeInterpolatorFactory),
        REG_JNI(register_android_graphics_animation_RenderNodeAnimator),
        REG_JNI(register_android_graphics_drawable_AnimatedVectorDrawable),
        REG_JNI(register_android_graphics_drawable_VectorDrawable),
        REG_JNI(register_android_graphics_fonts_Font),
        REG_JNI(register_android_graphics_fonts_FontFamily),
        REG_JNI(register_android_graphics_text_LineBreaker),
        REG_JNI(register_android_graphics_text_MeasuredText),
        REG_JNI(register_android_util_PathParser),
        REG_JNI(register_android_view_RenderNode),
        REG_JNI(register_android_view_DisplayListCanvas),
        REG_JNI(register_android_graphics_text_TextShaper),
        REG_JNI(register_android_text_Hyphenator),

        // 
};

/*
 * Register android native functions with the VM.
 */
/*static*/ int AndroidRuntime::startReg(JNIEnv* env)
{
    ATRACE_NAME("RegisterAndroidNatives");
    /*
     * This hook causes all future threads created in this process to be
     * attached to the JavaVM.  (This needs to go away in favor of JNI
     * Attach calls.)
     */
    androidSetCreateThreadFunc((android_create_thread_fn) javaCreateThreadEtc);

    ALOGE("--- registering native functions ---\n");

    /*
     * Every "register" function calls one or more things that return
     * a local reference (e.g. FindClass).  Because we haven't really
     * started the VM yet, they're all getting stored in the base frame
     * and never released.  Use Push/Pop to manage the storage.
     */
    env->PushLocalFrame(200);

    if (register_jni_procs(gRegJNI, NELEM(gRegJNI), env) < 0) {
        env->PopLocalFrame(NULL);
        return -1;
    }
    env->PopLocalFrame(NULL);

    //createJavaThread("fubar", quickTest, (void*) "hello");

    return 0;
}

AndroidRuntime* AndroidRuntime::getRuntime()
{
    return gCurRuntime;
}

/**
 * Used by surface flinger's DdmConnection to register native methods from
 * the framework.
 */
extern "C" jint registerFrameworkNatives(JNIEnv* env) {
    return register_jni_procs(gRegJNI, NELEM(gRegJNI), env);
}

}   // namespace android
