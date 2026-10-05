package com.my.televip.Configs;

import android.app.Activity;
import android.content.SharedPreferences;

import com.my.televip.application.ApplicationLoaderHook;
import com.my.televip.logging.Logger;

public class ConfigPreferences {

    private static SharedPreferences sharedPreferences;


    public static void init(){
        sharedPreferences = ApplicationLoaderHook.getApplicationContext().getSharedPreferences("TeleVip", Activity.MODE_PRIVATE);
    }

    public static void ensureInit() {
        if (sharedPreferences == null) init();
    }

    public static boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public static boolean getBoolean(String key, boolean defValue) {
        try {
            return sharedPreferences.getBoolean(key, defValue);
        } catch (ClassCastException e) {
            sharedPreferences.edit().remove(key).apply();
            return defValue;
        }
    }

    public static void putBoolean(String key, boolean b) {
        try {
            sharedPreferences.edit().putBoolean(key, b).apply();
        } catch (ClassCastException e) {
            sharedPreferences.edit().remove(key).apply();
        }
    }
    public static int getInt(String key) {
        try {
            return sharedPreferences.getInt(key, 0);
        } catch (ClassCastException e) {
            sharedPreferences.edit().remove(key).apply();
            return 0;
        }
    }

    public static void putInt(String key, int var) {
        try {
            sharedPreferences.edit().putInt(key, var).apply();
        } catch (ClassCastException e) {
            sharedPreferences.edit().remove(key).apply();
        }
    }

    public static String getString(String key) {
        try {
            return sharedPreferences.getString(key, null);
        } catch (ClassCastException e) {

            sharedPreferences.edit().remove(key).apply();
            return null;
        }
    }

    public static void putString(String key, String v) {
        try {
            sharedPreferences.edit().putString(key, v).apply();
        } catch (ClassCastException e) {
            sharedPreferences.edit().remove(key).apply();
        }
    }

    public static void remove(String key){
        try {
            if (key == null) return;
            sharedPreferences.edit().remove(key).apply();
        } catch (Throwable t){
            Logger.e(t);
        }
    }

}
