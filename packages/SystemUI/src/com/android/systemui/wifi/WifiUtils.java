/*
 * Copyright (C) 2012 The Android Open Source Project
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
 * limitations under the License. by xudq 
 */

package com.android.systemui.wifi;
import lineageos.waydroid.Net;
import android.content.Context;

public class WifiUtils{

    /**
     * 获取网络状态
     */
    public static int isWifiEnable(Context context){
        Net net = Net.getInstance(context);
        int status  = net.isWifiEnable();
        return status ;
    }

    /**
     * 启动和关闭WiFi
     */
    public static int enableWifi(Context context,int enable){
        Net net = Net.getInstance(context);
        int status = net.enableWifi(enable);
        return status ;
    }


    /**
     * 获取wifi详细信息
     */
    public static String getSignalAndSecurity(Context context,String ssid){
        Net net = Net.getInstance(context);
        String wifiInfo = net.getSignalAndSecurity(ssid);
        return wifiInfo ;
    }


    /**
     * 连接已保存WiFi 或者断开已保存WiFi
     */
    public static int connectActivedWifi(Context context,String ssid,int connect ){
        Net net = Net.getInstance(context);
        int status = net.connectActivedWifi(ssid,connect);
        return status;
    }

    /**
     * 连接未保存的WiFi
     */
    public static int connectSsid(Context context,String wifiName,String password){
        Net net = Net.getInstance(context);
        int status = net.connectSsid(wifiName,password);
        return status ;
    }


    /**
     * 连接隐藏的WiFi
     */
    public static int connectHidedWifi(Context context,String wifiName,String password){
        Net net = Net.getInstance(context);
        int status = net.connectHidedWifi(wifiName,password);
        return status ;
    }


    /**
     * 获取已保存列表
     */
    public static String connectedWifiList(Context context){
        Net net = Net.getInstance(context);
        String allSavelist = net.connectedWifiList();  
        return allSavelist ;
    }


    /**
     * 获取当前连接的WiFi
     */
    public static String getActivedWifi(Context context,String wifiName){
        Net net = Net.getInstance(context);
        String curNet = net.getActivedWifi();    
        return curNet ;
    }

    /**
     * 获取所有WiFi
     */
    public static String getAllSsid(Context context){
        Net net = Net.getInstance(context);
        String str = net.getAllSsid();
        return str ;
    }

}