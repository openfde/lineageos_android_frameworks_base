/*
 * Copyright (C) 2007 The Android Open Source Project
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

#define LOG_TAG "BootAnimation"

#include <stdint.h>
#include <inttypes.h>

#include <binder/IPCThreadState.h>
#include <binder/ProcessState.h>
#include <binder/IServiceManager.h>
#include <cutils/properties.h>
#include <sys/resource.h>
#include <utils/Log.h>
#include <utils/SystemClock.h>

#include "BootAnimation.h"
#include "BootAnimationUtil.h"
#include "audioplay.h"
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <string.h>

using namespace android;

// 静态封装的本地 HTTP POST 函数
static void sendLocalUnlockRequest() {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        ALOGE("Unlock request: create socket failed (%s)", strerror(errno));
        return;
    }

    // 设置超时，防止因接口未准备好导致开机动画无限卡死
    struct timeval timeout;
    timeout.tv_sec = 2; // 2秒超时
    timeout.tv_usec = 0;
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));

    struct sockaddr_in serv_addr;
    memset(&serv_addr, 0, sizeof(serv_addr));
    serv_addr.sin_family = AF_INET;
    serv_addr.sin_port = htons(18080);

    // 连接到 127.0.0.1
    if (inet_pton(AF_INET, "127.0.0.1", &serv_addr.sin_addr) <= 0) {
        ALOGE("Unlock request: inet_pton failed");
        close(sock);
        return;
    }

    if (connect(sock, (struct sockaddr *)&serv_addr, sizeof(serv_addr)) < 0) {
        ALOGE("Unlock request: connect failed (%s). Target service might not be ready yet.", strerror(errno));
        close(sock);
        return;
    }

    // 构造标准的 HTTP POST 请求数据
    const char* http_request =
            "POST /api/v1/user_manager/unlock HTTP/1.1\r\n"
            "Host: 127.0.0.1:18080\r\n"
            "User-Agent: BootAnimation/1.0\r\n"
            "Accept: */*\r\n"
            "Content-Length: 0\r\n" // POST 请求通常需要带上 Content-Length
            "\r\n";

    ssize_t bytes_sent = send(sock, http_request, strlen(http_request), 0);
    if (bytes_sent < 0) {
        ALOGE("Unlock request: send failed (%s)", strerror(errno));
    } else {
        ALOGI("Unlock request: sent successfully (%zx bytes)", bytes_sent);
    }

    // 接收响应（可选，主要为了确保请求完整发送）
    char buffer[256];
    memset(buffer, 0, sizeof(buffer));
    if (recv(sock, buffer, sizeof(buffer) - 1, 0) > 0) {
        ALOGI("Unlock request response received");
    }

    close(sock);
}

int main()
{
    setpriority(PRIO_PROCESS, 0, ANDROID_PRIORITY_DISPLAY);

    bool noBootAnimation = bootAnimationDisabled();
    ALOGI_IF(noBootAnimation,  "boot animation disabled");
    if (!noBootAnimation) {

        sp<ProcessState> proc(ProcessState::self());
        ProcessState::self()->startThreadPool();

        // create the boot animation object (may take up to 200ms for 2MB zip)
        sp<BootAnimation> boot = new BootAnimation(audioplay::createAnimationCallbacks());

        waitForSurfaceFlinger();
        // 插入触发代码
        ALOGI("Triggering local user unlock request...");
        sendLocalUnlockRequest();

        boot->run("BootAnimation", PRIORITY_DISPLAY);

        ALOGV("Boot animation set up. Joining pool.");

        IPCThreadState::self()->joinThreadPool();
    }
    return 0;
}
