/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.os;

import android.os.SystemProperties;
import android.provider.DeviceConfig;

/**
 * Flag names for configuring the zygote.
 *
 * @hide
 */
public class ZygoteConfig {

    /** If {@code true}, enables the unspecialized app process (USAP) pool feature */
    public static final String USAP_POOL_ENABLED = "usap_pool_enabled";
    // 1. 开启功能
    public static final boolean USAP_POOL_ENABLED_DEFAULT = true;

    /** The threshold used to determine if the pool should be refilled */
    public static final String USAP_POOL_REFILL_THRESHOLD = "usap_refill_threshold";
    // 4. 提高补充灵敏度
    public static final int USAP_POOL_REFILL_THRESHOLD_DEFAULT = 2;

    /** The maximum number of processes to keep in the USAP pool */
    public static final String USAP_POOL_SIZE_MAX = "usap_pool_size_max";
    // 2. 扩大最大容量
    public static final int USAP_POOL_SIZE_MAX_DEFAULT = 7;

    public static final int USAP_POOL_SIZE_MAX_LIMIT = 100;

    /** The minimum number of processes to keep in the USAP pool */
    public static final String USAP_POOL_SIZE_MIN = "usap_pool_size_min";
    // 3. 提高常驻最小容量
    public static final int USAP_POOL_SIZE_MIN_DEFAULT = 3;

    public static final int USAP_POOL_SIZE_MIN_LIMIT = 1;

    /** The number of milliseconds to delay before refilling the USAP pool */
    public static final String USAP_POOL_REFILL_DELAY_MS = "usap_pool_refill_delay_ms";
    // 5. 缩短再填充延迟
    public static final int USAP_POOL_REFILL_DELAY_MS_DEFAULT = 500;

    public static final String PROPERTY_PREFIX_DEVICE_CONFIG = "persist.device_config";
    public static final String PROPERTY_PREFIX_SYSTEM = "dalvik.vm.";

    private static String getDeviceConfig(String name) {
        return SystemProperties.get(
            String.join(
                ".",
                PROPERTY_PREFIX_DEVICE_CONFIG,
                DeviceConfig.NAMESPACE_RUNTIME_NATIVE,
                name));
    }

    /**
     * Get a property value from SystemProperties and convert it to an integer value.
     */
    public static int getInt(String name, int defaultValue) {
        final String propString = getDeviceConfig(name);

        if (!propString.isEmpty()) {
            return Integer.parseInt(propString);
        } else {
            return SystemProperties.getInt(PROPERTY_PREFIX_SYSTEM + name, defaultValue);
        }
    }

    /**
     * Get a property value from SystemProperties and convert it to a Boolean value.
     */
    public static boolean getBool(String name, boolean defaultValue) {
        final String propString = getDeviceConfig(name);

        if (!propString.isEmpty()) {
            return Boolean.parseBoolean(propString);
        } else {
            return SystemProperties.getBoolean(PROPERTY_PREFIX_SYSTEM + name, defaultValue);
        }
    }
}
