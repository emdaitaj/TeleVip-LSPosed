package com.my.televip.virtuals.ActionBar;

import android.content.Context;
import android.content.res.Configuration;
import android.text.TextPaint;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.application.ApplicationLoaderHook;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.Obfuscate;
import com.my.televip.utils.Utils;

import de.robv.android.xposed.XposedHelpers;

public class Theme {

    public static TextPaint getTextPaint()
    {
        return (TextPaint) XposedHelpers.getStaticObjectField(ClassLoad.getClass(ClassNames.THEME), Obfuscate.getFieldName("Theme", "chat_timePaint"));
    }

    /**
     * Whether the client is showing a light theme. Every colour in TeleVip's own screens is picked
     * from this, so it must answer even on a build where the name of Telegram's own check could not
     * be worked out; the system's night mode is then used instead of leaving the screen unpainted.
     */
    public static boolean isLight(){
        if (!askedTelegram) {
            try {
                return !((boolean) XposedHelpers.callStaticMethod(ClassLoad.getClass(ClassNames.THEME), Obfuscate.getMethodName("Theme", "isCurrentThemeDark")));
            } catch (Throwable t) {
                // only stop asking when the name itself is wrong; a passing failure is worth retrying
                askedTelegram = t instanceof NoSuchMethodError;
                if (!warned) {
                    warned = true;
                    Logger.w("Cannot ask Telegram which theme it is showing, following the system instead: " + t);
                }
            }
        }
        return !isSystemDark();
    }

    private static volatile boolean askedTelegram;
    private static volatile boolean warned;

    private static boolean isSystemDark() {
        try {
            Context context = Utils.getCurrentActivity();
            if (context == null) context = ApplicationLoaderHook.getApplicationContext();
            if (context == null) return false;
            int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return mode == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }
}
