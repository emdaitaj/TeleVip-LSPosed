package com.my.televip.utils;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;

import com.google.gson.Gson;
import com.my.televip.logging.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.XposedHelpers;

public class Utils {
    public static String pkgName = null;
    public static String modulePath = null;
    public static ClassLoader classLoader = null;
    public static long versionCode = -1;
    public static final String issue = "Your Telegram client may be an incompatible version with TeleVip. Please download the latest version that is compatible with TeleVip.";

    private static WeakReference<Activity> currentActivity;

    // Read before the hooked app has an Application context, the obfuscation mapping is chosen by it.
    public static long readVersionCode(ApplicationInfo appInfo, String packageName) {
        try {
            Object activityThread = XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread");
            Context systemContext = (Context) XposedHelpers.callMethod(activityThread, "getSystemContext");
            PackageInfo info = systemContext.getPackageManager().getPackageInfo(packageName, 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
        } catch (Throwable ignored) {}
        try {
            return XposedHelpers.getIntField(appInfo, "versionCode");
        } catch (Throwable t) {
            Logger.e(t);
        }
        return -1;
    }

    public static void setCurrentActivity(Activity activity) {
        currentActivity = new WeakReference<>(activity);
    }

    public static Activity getCurrentActivity() {
        return currentActivity != null ? currentActivity.get() : null;
    }

    public static String getFieldAsString(Object value) {

        if (value == null) {
            return null;
        }

        if (value instanceof CharSequence) {
            return value.toString();
        }

        return String.valueOf(value);
    }

    public static void objectToJson(Object object) {
        try {
            Gson gson = new Gson();

            String json = gson.toJson(object);

            File dir = new File(Utils.getCurrentActivity().getFilesDir(), "backup");
            if (!dir.exists()) {
                dir.mkdirs();
            }

            File file = new File(
                    dir,
                    object.getClass().getSimpleName() + "_" + System.currentTimeMillis() + ".json"
            );

            FileOutputStream fos = new FileOutputStream(file);
            fos.write(json.getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Throwable throwable) {
            Logger.e(throwable);
        }
    }

}
