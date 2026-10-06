package com.my.televip.features.otherFeatures;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.base.BaseMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.ArgsResolver;
import com.my.televip.obfuscate.Obfuscate;
import com.my.televip.utils.Utils;

import java.lang.reflect.Field;

import de.robv.android.xposed.XposedHelpers;

public class FeatureInitializer {

    public static void init() {

        try {
            if (!FeatureStateManager.isChatEnabled() || !FeatureStateManager.isProfileEnabled()) {

                Class<?> actionBarClass = XposedHelpers.findClassIfExists(
                        Obfuscate.getClassName("org.telegram.ui.ActionBar.ActionBar"),
                        Utils.classLoader
                );

                HMethod.hookMethod(
                        actionBarClass,
                        Obfuscate.getMethodName("ActionBar", "setActionBarMenuOnItemClick"), ClassLoad.getClass(ClassNames.ACTION_BAR_MENU_ON_ITEM_CLICK),
                        new BaseMethodHook() {
                            @Override
                            protected void beforeMethod(MethodHookParam param) {

                                Object clazz = param.args[0];

                                if (clazz == null) return;

                                String name = clazz.getClass().getName();

                                if (isInnerOf(clazz.getClass(), name, "ChatActivity", ClassNames.CHAT_ACTIVITY) && !FeatureStateManager.isChatEnabled()) {
                                    FeatureStateManager.saveChat(name);
                                    ChatHook.init(name);
                                }

                                if (isInnerOf(clazz.getClass(), name, "ProfileActivity", ClassNames.PROFILE_ACTIVITY) && !FeatureStateManager.isProfileEnabled()) {
                                    FeatureStateManager.saveProfile(name);
                                    ProfileHook.init(name);
                                }
                            }
                        });

            } else {
                ChatHook.init(FeatureStateManager.getChatClass());
                ProfileHook.init(FeatureStateManager.getProfileClass());
            }

        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    // R8 flattens inner class names in obfuscated builds (ChatActivity$9 -> ab1), so fall back to the type of the outer instance field
    private static boolean isInnerOf(Class<?> cls, String name, String outerSimpleName, String outerClassName) {
        if (name.contains(outerSimpleName)) return true;
        Class<?> outer = ClassLoad.getClass(outerClassName);
        return outer != null && !name.equals(outer.getName()) && findOuterField(cls, outer) != null;
    }

    static Field findOuterField(Class<?> cls, Class<?> outer) {
        for (Field field : cls.getDeclaredFields()) {
            if (field.getType() == outer) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    static Object getOuterInstance(Object inner, String classKey, String outerClassName) {
        Field field = XposedHelpers.findFieldIfExists(inner.getClass(), Obfuscate.getFieldName(classKey, "this$0"));
        if (field == null) {
            Class<?> outer = ClassLoad.getClass(outerClassName);
            if (outer != null) field = findOuterField(inner.getClass(), outer);
        }
        if (field == null) return null;
        try {
            return field.get(inner);
        } catch (Throwable t) {
            Logger.e(t);
            return null;
        }
    }
}