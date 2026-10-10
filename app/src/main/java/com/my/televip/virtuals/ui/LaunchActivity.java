package com.my.televip.virtuals.ui;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;

import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.Obfuscate;

import de.robv.android.xposed.XposedHelpers;

public class LaunchActivity {

    Object launchActivity;
    /** The layout Telegram fills its window with, and what TeleVip puts its own screens next to. */
    public FrameLayout frameLayout;

    public LaunchActivity(Object obj){
       launchActivity = obj;
       frameLayout = telegramContent(obj);
       if (frameLayout == null) frameLayout = androidContent(obj);
    }

    private static FrameLayout telegramContent(Object activity) {
        try {
            Object value = XposedHelpers.getObjectField(activity, Obfuscate.getFieldName("LaunchActivity", "frameLayout"));
            // the class has more than one layout of this type, so take it only where it is the one
            // Telegram passed to setContentView; anything else would put our screen out of sight
            if (value instanceof FrameLayout && isWindowContent(activity, (View) value)) {
                return (FrameLayout) value;
            }
        } catch (Throwable ignored) {
            // an obfuscated build whose field name could not be worked out, the window itself serves
        }
        return null;
    }

    private static boolean isWindowContent(Object activity, View view) {
        if (!(activity instanceof Activity)) return false;
        View content = ((Activity) activity).findViewById(android.R.id.content);
        return content != null && (view == content || view.getParent() == content);
    }

    /**
     * Every activity has this one, and on Telegram it holds nothing but the layout above, so showing
     * a screen in it comes to the same thing.
     */
    private static FrameLayout androidContent(Object activity) {
        if (!(activity instanceof Activity)) return null;
        View content = ((Activity) activity).findViewById(android.R.id.content);
        if (content instanceof FrameLayout) return (FrameLayout) content;
        Logger.w("Cannot find a layout to show TeleVip's screens in");
        return null;
    }

}
