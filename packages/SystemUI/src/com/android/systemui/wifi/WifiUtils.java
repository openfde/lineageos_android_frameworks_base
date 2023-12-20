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
     * get wifi status
     */
    public static int isWifiEnable(Context context){
        Net net = Net.getInstance(context);
        int status  = net.isWifiEnable();
        return status ;
    }

    /**
     * enable or disable wifi
     */
    public static int enableWifi(Context context,int enable){
        Net net = Net.getInstance(context);
        int status = net.enableWifi(enable);
        return status ;
    }


    /**
     * get wifi details
     */
    public static String getSignalAndSecurity(Context context,String ssid){
        Net net = Net.getInstance(context);
        String wifiInfo = net.getSignalAndSecurity(ssid);
        return wifiInfo ;
    }


    /**
     * connect  or disconect wifi
     */
    public static int connectActivedWifi(Context context,String ssid,int connect ){
        Net net = Net.getInstance(context);
        int status = net.connectActivedWifi(ssid,connect);
        return status;
    }

    /**
     * connect wifi if not saved  
     */
    public static int connectSsid(Context context,String wifiName,String password){
        Net net = Net.getInstance(context);
        int status = net.connectSsid(wifiName,password);
        return status ;
    }


    /**
     * connect hide wifi 
     */
    public static int connectHidedWifi(Context context,String wifiName,String password){
        Net net = Net.getInstance(context);
        int status = net.connectHidedWifi(wifiName,password);
        return status ;
    }


    /**
     * get all saved wifi list
     */
    public static String connectedWifiList(Context context){
        Net net = Net.getInstance(context);
        String allSavelist = net.connectedWifiList();  
        return allSavelist ;
    }


    /**
     * get actived wifi 
     */
    public static String getActivedWifi(Context context){
        Net net = Net.getInstance(context);
        String curNet = net.getActivedWifi();    
        return curNet ;
    }

    /**
     * get all wifi 
     */
    public static String getAllSsid(Context context){
        Net net = Net.getInstance(context);
        String str = net.getAllSsid();
        return str ;
    }

    /**
     * delete saved wifi password
     */
    public static int forgetWifi(Context context,String wifiName){
        Net net = Net.getInstance(context);
        int status = net.forgetWifi(wifiName);
        return status ;
    }


    /**
     * get static ip config
     */
    public static String getStaticIpConf(Context context,String getStaticIpConf){
        Net net = Net.getInstance(context);
        String str = net.getStaticIpConf(getStaticIpConf);
        return str ;
    }

    /**
     * set dhcp IP
     */
    public static int setDHCP(Context context,String interfaceName){
        Net net = Net.getInstance(context);
        int status = net.setDHCP(interfaceName);
        return status ;
    }

    /**
     * set static IP
     */
    public static int setStaticIp(Context context,String interfaceName, String ipAddress, int networkPrefixLength, String gateway, String dns1, String dns2){
        Net net = Net.getInstance(context);
        int status = net.setStaticIp(interfaceName,ipAddress,networkPrefixLength,gateway,dns1,dns2);
        return status ;
    }
}