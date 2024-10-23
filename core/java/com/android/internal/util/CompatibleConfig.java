package com.android.internal.util;

import android.content.ContentResolver;
import android.net.Uri;
import android.database.Cursor;
import android.content.ContentValues;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.content.Context;
import java.util.concurrent.CountDownLatch;

import android.os.Binder;
import android.os.Process;

import android.util.Slog;
import android.os.SystemProperties;
import android.os.UserManager;
import android.app.ActivityManager;

import java.io.File;
import java.nio.file.Files;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.Scanner;


import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import com.android.internal.util.CompatibleDatabaseHelper;

/**
	compatible tool api add by xudq 

*/


public class CompatibleConfig {
	public static final String COMPATIBLE_STR  = "com.android.compatibleprovider";
    public static final String COMPATIBLE_URI = "content://"+COMPATIBLE_STR;


    private static CompatibleConfig instance;

    private CompatibleConfig() {
    }

    public static synchronized CompatibleConfig getInstance() {
        if (instance == null) {
            instance = new CompatibleConfig();
        }
        return instance;
    }

    public static Map<String, Object> queryMapValueData(Context context, String packageName, String keycode) {
        Uri uri = Uri.parse(COMPATIBLE_URI + "/COMPATIBLE_VALUE");
        Cursor cursor = null;
        Map<String, Object> result = null;
        String selection = "PACKAGE_NAME = ? AND KEY_CODE = ? AND IS_DEL != 1";
        String[] selectionArgs = { packageName, keycode };
        try {
            ContentResolver contentResolver = context.getContentResolver();
            cursor = contentResolver.query(uri, null, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                int _ID = cursor.getInt(cursor.getColumnIndex("_ID"));
                String PACKAGE_NAME = cursor.getString(cursor.getColumnIndex("PACKAGE_NAME"));
                String KEY_CODE = cursor.getString(cursor.getColumnIndex("KEY_CODE"));
                String VALUE = cursor.getString(cursor.getColumnIndex("VALUE"));
                String EDIT_DATE = cursor.getString(cursor.getColumnIndex("EDIT_DATE"));
                result = new HashMap<>();
                result.put("_ID", _ID);
                result.put("PACKAGE_NAME", PACKAGE_NAME);
                result.put("KEY_CODE", KEY_CODE);
                result.put("VALUE", VALUE);
                result.put("EDIT_DATE", EDIT_DATE);
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    static String result = "";

    public static String queryThreadWaitData(Context context, String packageName, String keycode) {
        CountDownLatch latch = new CountDownLatch(1);

        Thread thread1 = new Thread(new Runnable() {
            @Override
            public void run() {
                result = queryValueData(context, packageName, keycode);
                latch.countDown();
            }
        });
        thread1.start();
        try {
            latch.await();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    public static String queryTrainingData(Context context, String packageName, String keycode) {
		String result = null ;
		String fdebootCompleted = SystemProperties.get("fde.boot_completed", "0");
		Slog.wtf("queryListValueData", "querySyncData fdebootCompleted... " + fdebootCompleted + ",keycode "+keycode+",packageName "+packageName);
		   
		if (fdebootCompleted.equals("1")) {
			String res	= SystemProperties.get(packageName+"_"+keycode, "");
			Slog.wtf("queryListValueData", "querySyncData res... " + res);
			return res ;
		}else{
			return	null;
		}
       
    }


    public static String queryValueData(Context context, String packageName, String keycode) {
        Uri uri = Uri.parse(COMPATIBLE_URI + "/COMPATIBLE_VALUE");
        Cursor cursor = null;
        String result = null;
        String selection = "PACKAGE_NAME = ? AND KEY_CODE = ? AND IS_DEL != 1";
        String[] selectionArgs = { packageName, keycode };
        try {
            ContentResolver contentResolver = context.getContentResolver();
            cursor = contentResolver.query(uri, null, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                result = cursor.getString(cursor.getColumnIndex("VALUE"));
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }


    // public static String queryValueData(Context context, String packageName,
    // String keycode) {
    // String bootCompleted = SystemProperties.get("sys.boot_completed", "0");
    // Slog.wtf("bella", "getDisplayInfoInternal start... " + bootCompleted);

    // if (bootCompleted.equals("1")) {
    // Slog.wtf("bella", "bootCompleted is is ready open........");

    // CountDownLatch latch = new CountDownLatch(1);

    // Thread threadQuery = new Thread(() -> {
    // try {
    // Uri uri = Uri.parse(COMPATIBLE_URI + "/COMPATIBLE_VALUE");
    // Cursor cursor = null;

    // String selection = "PACKAGE_NAME = ? AND KEY_CODE = ? AND IS_DEL != 1";
    // String[] selectionArgs = { packageName, keycode };
    // try {
    // ContentResolver contentResolver = context.getContentResolver();

    // boolean isRun = isRunApp(context);
    // if (UserManager.get(context).isUserUnlocked(contentResolver.getUserId()) &&
    // isRun) {
    // Slog.wtf("bella", "11111111111111111111 isRun ");
    // // Thread.sleep(500);
    // int callId = Binder.getCallingUid();
    // int pid = Process.myUid();
    // if (callId != pid) {

    // Slog.wtf("bella",
    // "is not == my UID callId " + callId + " ,pid " + pid + " ,isRun " + isRun);
    // final long token = Binder.clearCallingIdentity();
    // try {
    // cursor = contentResolver.query(uri, null, selection, selectionArgs, null);
    // if (cursor != null && cursor.moveToFirst()) {
    // result = cursor.getString(cursor.getColumnIndex("VALUE"));
    // }
    // } catch (Exception e) {
    // e.printStackTrace();
    // } finally {
    // Binder.restoreCallingIdentity(token);
    // }
    // } else {
    // Slog.wtf("bella", "is == my UID callId " + callId + " ,pid " + pid + ",isRun
    // " + isRun);
    // cursor = contentResolver.query(uri, null, selection, selectionArgs, null);
    // if (cursor != null && cursor.moveToFirst()) {
    // result = cursor.getString(cursor.getColumnIndex("VALUE"));
    // }
    // }

    // } else {
    // Slog.wtf("bella", "00000000000000 isRun ");
    // result = null;
    // }

    // } catch (Exception e) {
    // e.printStackTrace();
    // } finally {
    // if (cursor != null) {
    // cursor.close();
    // }
    // }
    // latch.countDown();
    // } catch (Exception e) {
    // e.printStackTrace();
    // }
    // });

    // threadQuery.start();

    // try {
    // latch.await();
    // } catch (Exception e) {
    // e.printStackTrace();
    // }

    // return result;
    // } else

    // {
    // Slog.wtf("bella", "bootCompleted is not ready.....");
    // return null;
    // }
    // }
	
	public static int parseValueXML(Context context,String packageName) {
		   try{
		        InputStream inputStream = context.getResources().openRawResource(com.android.internal.R.raw.comp_config_value);
				int res = -1;
				if("".equals(packageName)){
					res = parseValue(context, inputStream);
				}else{
					res = parseValue(context, inputStream, packageName);
				}
				return res;
		   } catch (Exception e) {
			   e.printStackTrace();
			   return -1;
		   }
	   }
	
	public static int parseValue(Context context, InputStream inputStream) {
		   try {
			   DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			   DocumentBuilder builder = factory.newDocumentBuilder();
			   Document document = builder.parse(inputStream);
			   Element rootElement = document.getDocumentElement();
			   NodeList itemList = document.getElementsByTagName("item");
			   CompatibleDatabaseHelper db = new CompatibleDatabaseHelper(context);
			   for (int i = 0; i < itemList.getLength(); i++) {
				    Element keycodeElement = (Element) itemList.item(i);
					String date = keycodeElement.getAttribute("updatedate");
					String isdel = keycodeElement.getAttribute("isdel");
					String name = keycodeElement.getAttribute("keycode");
				    NodeList packageList = keycodeElement.getElementsByTagName("package");
				    for (int j = 0; j < packageList.getLength(); j++) {
					   Element packageElement = (Element) packageList.item(j);
					   String packagename = packageElement.getElementsByTagName("packagename").item(0).getTextContent();
					   String defaultvalue = packageElement.getElementsByTagName("defaultvalue").item(0).getTextContent().replaceAll("\\s", "");
					   //Slog.wtf("parseValue", "name " + name + ",packagename " + packagename + ",date  " + date + ",isDel " + isdel);
					   Map<String, Object> resMap = db.queryCompatibleByPackageNameAndKeyCode(packagename, name);	
					    if ("true".equals(isdel)) {
							db.deleteCompatible(packagename,name);
						}else if (resMap == null || resMap.get("PACKAGE_NAME") == null ) {
                            db.insertCompatible(packagename, name, defaultvalue);
                        } else {
                            String queryDate = resMap.get("FIELDS1").toString();
                            if (!date.equals(queryDate)) {
                                db.updateCompatible( packagename, name, defaultvalue, date);
                            }
                        }
						
				   }
			   }
		       db.readCompatibles();
		   } catch (Exception e) {
			   e.printStackTrace();
			   return -1 ;
		   }
		   return 0;
	   }

	public static int parseValue(Context context, InputStream inputStream, String recoPackageName) {
		   try {
			   DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			   DocumentBuilder builder = factory.newDocumentBuilder();
			   Document document = builder.parse(inputStream);
			   Element rootElement = document.getDocumentElement();
			   NodeList itemList = document.getElementsByTagName("item");
			   CompatibleDatabaseHelper db = new CompatibleDatabaseHelper(context);
			   for (int i = 0; i < itemList.getLength(); i++) {
				    Element keycodeElement = (Element) itemList.item(i);
					String date = keycodeElement.getAttribute("updatedate");
					String isdel = keycodeElement.getAttribute("isdel");
					String name = keycodeElement.getAttribute("keycode");
				    NodeList packageList = keycodeElement.getElementsByTagName("package");
				    for (int j = 0; j < packageList.getLength(); j++) {
					   Element packageElement = (Element) packageList.item(j);
					   String packagename = packageElement.getElementsByTagName("packagename").item(0).getTextContent();
					   //Slog.i("parseValue", "packagename " + packagename + ",recoPackageName " + recoPackageName);
					   if (packagename.equals(recoPackageName)) {
						   String defaultvalue = packageElement.getElementsByTagName("defaultvalue").item(0).getTextContent().replaceAll("\\s", "");
						   //Slog.i("parseValue", "name " + name + ",packagename " + packagename + ",date  " + date + ",isDel " + isdel);
						   Map<String, Object> resMap = db.queryCompatibleByPackageNameAndKeyCode(packagename, name);	
						    if ("true".equals(isdel)) {
								db.deleteCompatible(packagename,name);
							}else if (resMap == null || resMap.get("PACKAGE_NAME") == null ) {
	                            db.insertCompatible(packagename, name, defaultvalue);
	                        } else {
	                            String queryDate = resMap.get("FIELDS1").toString();
	                            if (!date.equals(queryDate)) {
	                                db.updateCompatible( packagename, name, defaultvalue, date);
	                            }
	                        }	
					   }
				   }
			   }
	
		   } catch (Exception e) {
			   e.printStackTrace();
			    Slog.e("parseValue", ""+e.toString());
			   return -1 ;
		   }
		   return 0;
	   }

    public static String getCurDateTime() {
        LocalDateTime currentTime = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String formattedTime = currentTime.format(formatter);
        return formattedTime;
    }

}
