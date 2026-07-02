/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "FileSystemProvider"
//#define LOG_NDEBUG 0

#include <androidfw/ApkParsing.h>
#include <androidfw/ZipFileRO.h>
#include <androidfw/ZipUtils.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/fs.h>
#include <nativehelper/ScopedUtfChars.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>
#include <utils/Log.h>
#include <zlib.h>
#include <vector>
#include <queue>
#include <cstring>
#include <dirent.h>


#include <memory>

#include "core_jni_helpers.h"

#define RS_BITCODE_SUFFIX ".bc"

#define TMP_FILE_PATTERN "/tmp.XXXXXX"
#define TMP_FILE_PATTERN_LEN (sizeof(TMP_FILE_PATTERN) - 1)

namespace android {


// isDir?
bool isDirectory(const std::string& path) {
    struct stat statbuf;
    if (stat(path.c_str(), &statbuf) != 0) return false;
    return S_ISDIR(statbuf.st_mode);
}

// extern "C"
JNIEXPORT jobject JNICALL
com_android_internal_content_FileSystemProvider_nativeSearchFiles(
        JNIEnv *env, 
        jobject thiz, 
        jstring start_dir, 
        jstring keyword){
                // 
                const char* c_start_dir = env->GetStringUTFChars(start_dir, nullptr);
                const char* c_keyword = env->GetStringUTFChars(keyword, nullptr);
                
                std::string startDirStr(c_start_dir);
                std::string keywordStr(c_keyword);
                
                env->ReleaseStringUTFChars(start_dir, c_start_dir);
                env->ReleaseStringUTFChars(keyword, c_keyword);

                // 2. 
                jclass arrayListClass = env->FindClass("java/util/ArrayList");
                jmethodID arrayListInit = env->GetMethodID(arrayListClass, "<init>", "()V");
                jobject resultList = env->NewObject(arrayListClass, arrayListInit);
                jmethodID arrayListAdd = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");

                // 3. 
                jclass fileClass = env->FindClass("java/io/File");
                jmethodID fileInit = env->GetMethodID(fileClass, "<init>", "(Ljava/lang/String;)V");

                // 4. Native BFS 核心
                std::queue<std::string> pending;
                pending.push(startDirStr);

                while (!pending.empty()) {
                                std::string currentDir = pending.front();
                                pending.pop();

                                DIR* dp = opendir(currentDir.c_str());
                                if (dp == nullptr) {
                                continue; 
                                }

                                struct dirent* entry;
                                while ((entry = readdir(dp)) != nullptr) {
                                if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
                                        continue;
                                }

                                std::string childPath = currentDir;
                                if (childPath.back() != '/') {
                                        childPath += "/";
                                }
                                childPath += entry->d_name;

                                bool isDir = false;
                                if (entry->d_type != DT_UNKNOWN) {
                                        isDir = (entry->d_type == DT_DIR);
                                } else {
                                        isDir = isDirectory(childPath);
                                }

                                if (isDir) {
                                        pending.push(childPath);
                                } else {
                                        std::string fileName(entry->d_name);
                                        if (fileName.find(keywordStr) != std::string::npos) {
                                                // 
                                                jstring jPath = env->NewStringUTF(childPath.c_str());
                                                
                                                // 
                                                jobject jFile = env->NewObject(fileClass, fileInit, jPath);
                                                
                                                // 
                                                env->CallBooleanMethod(resultList, arrayListAdd, jFile);
                                                
                                                // 
                                                env->DeleteLocalRef(jPath);
                                                env->DeleteLocalRef(jFile);

                                        }
                                }
                        }
                        closedir(dp);
                }
                return resultList;

        }

JNIEXPORT jobject JNICALL
com_android_internal_content_FileSystemProvider_nativeListFiles(
        JNIEnv *env, 
        jobject thiz, 
        jstring parent_path) {
    
    if (parent_path == nullptr) return nullptr;

    // 1. 转换输入路径
    const char* c_parent_path = env->GetStringUTFChars(parent_path, nullptr);
    std::string parentPathStr(c_parent_path);
    env->ReleaseStringUTFChars(parent_path, c_parent_path);

    // 2. 获取 Java 的 ArrayList 类和方法
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID arrayListInit = env->GetMethodID(arrayListClass, "<init>", "()V");
    jobject resultList = env->NewObject(arrayListClass, arrayListInit);
    jmethodID arrayListAdd = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");

    // 3. 获取 java.io.File 类及其构造函数 File(String pathname)
    jclass fileClass = env->FindClass("java/io/File");
    jmethodID fileInit = env->GetMethodID(fileClass, "<init>", "(Ljava/lang/String;)V");

    // 4. 打开底层目录
    DIR* dp = opendir(parentPathStr.c_str());
    if (dp == nullptr) {
        return resultList; // 打开失败则返回空列表
    }

    struct dirent* entry;
    while ((entry = readdir(dp)) != nullptr) {
        // 过滤掉当前目录 "." 和上级目录 ".."
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
            continue;
        }

        // 拼接子项的绝对路径
        std::string childPath = parentPathStr;
        if (childPath.back() != '/') {
            childPath += "/";
        }
        childPath += entry->d_name;

        // 5. 核心：在 Native 层直接构造 Java 的 File 对象
        jstring jPath = env->NewStringUTF(childPath.c_str());
        jobject jFile = env->NewObject(fileClass, fileInit, jPath);

        // 将构造好的 File 对象加进 ArrayList
        env->CallBooleanMethod(resultList, arrayListAdd, jFile);
        
        // 关键：必须手动释放这两个局部引用，否则同目录下文件多时会直接导致本地引用表溢出（崩溃）
        env->DeleteLocalRef(jPath);
        env->DeleteLocalRef(jFile);
    }

    closedir(dp);
    return resultList;
}        

JNIEXPORT jstring JNICALL
com_android_internal_content_FileSystemProvider_nativeListFilesEfficient(
        JNIEnv *env, jobject thiz, jstring parent_path) {
    
    if (parent_path == nullptr) return nullptr;

    const char* c_parent_path = env->GetStringUTFChars(parent_path, nullptr);
    std::string parentPathStr(c_parent_path);
    env->ReleaseStringUTFChars(parent_path, c_parent_path);

    DIR* dp = opendir(parentPathStr.c_str());
    if (dp == nullptr) return nullptr;

    // 预分配 1MB 左右的连续内存，防止一万个文件在拼接时频繁扩容造成的内存拷贝
    std::string bulkPaths;
    bulkPaths.reserve(1024 * 1024); 

    if (parentPathStr.back() != '/') {
        parentPathStr += "/";
    }

    struct dirent* entry;
    while ((entry = readdir(dp)) != nullptr) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
            continue;
        }

        // 纯 C++ 内存追加，速度极快
        bulkPaths.append(parentPathStr);
        bulkPaths.append(entry->d_name);
        bulkPaths.append("\n");
    }

    closedir(dp);
    return env->NewStringUTF(bulkPaths.c_str());
}

static const JNINativeMethod gMethods[] = {
    {"nativeSearchFiles",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/util/List;",
            (void *)com_android_internal_content_FileSystemProvider_nativeSearchFiles},

    {"nativeListFiles",
            "(Ljava/lang/String;)Ljava/util/List;",
            (void *)com_android_internal_content_FileSystemProvider_nativeListFiles},        

    {"nativeListFilesEfficient",
            "(Ljava/lang/String;)Ljava/lang/String;",
            (void *)com_android_internal_content_FileSystemProvider_nativeListFilesEfficient},               
};


int register_com_android_internal_content_FileSystemProvider(JNIEnv *env)
{
    return RegisterMethodsOrDie(env,
            "com/android/internal/content/FileSystemProvider", gMethods, NELEM(gMethods));
}

};
