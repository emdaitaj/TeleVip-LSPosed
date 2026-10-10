package com.my.televip.settings.hook;


import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Clients.ClientManager;
import com.my.televip.Drawable.GhostDrawable;
import com.my.televip.base.BaseMethodHook;
import com.my.televip.hooks.HMethod;
import com.my.televip.language.Keys;
import com.my.televip.language.Translator;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.ArgsResolver;
import com.my.televip.obfuscate.Obfuscate;
import com.my.televip.settings.controller.SettingsController;
import com.my.televip.utils.Utils;
import com.my.televip.virtuals.Adapters.DrawerLayoutAdapter;
import com.my.televip.virtuals.SettingsIconResolver;
import com.my.televip.virtuals.ui.Components.UItem;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SettingsHook {

    private Constructor<?> itemConstructor;

    public void newSettings(Class<?> SettingsActivityClass, Class<?> SettingsActivity$SettingCell$FactoryClass, SettingsController settingsController){
        try {

            GhostDrawable ghostDrawable = new GhostDrawable();
            Class<?> UItemClass = ClassLoad.getClass(ClassNames.UITEM);

            // Telegram's own handler is where the tap is normally picked up; on a build whose name
            // for it could not be worked out the row gets a listener of its own instead
            final boolean clickHooked = HMethod.hookMethod(
                    SettingsActivityClass,
                    Obfuscate.getMethodName("SettingsActivity", "onClick"), ArgsResolver.merge("SettingsActivity", "onClick", new Class[]{UItemClass, View.class, int.class, float.class, float.class}, new BaseMethodHook() {
                        @Override
                        protected void afterMethod(final MethodHookParam param) {
                            UItem uItem = new UItem(findArg(param.args, UItemClass));
                            if (uItem.getUItem() != null) {
                                if (uItem.getID() == 8353847) {
                                    settingsController.openView();
                                }
                            }
                        }
                    })) != null;
            if (!clickHooked) {
                Logger.w("Telegram's settings click handler could not be hooked, the row carries its own listener");
            }

            Class<?> settingCellClass = ClassLoad.getClass(ClassNames.SETTINGS_ACTIVITY_SETTING_CELL);
            Class<?>[] setTypes = ArgsResolver.resolveObject("set", new Class[]{int.class, int.class, int.class, CharSequence.class, CharSequence.class, CharSequence.class});
            if (settingCellClass == null || XposedHelpers.findMethodExactIfExists(settingCellClass, Obfuscate.getMethodName("SettingsActivity$SettingCell", "set"), (Object[]) setTypes) != null) {
                HMethod.hookMethod(settingCellClass, Obfuscate.getMethodName("SettingsActivity$SettingCell", "set"), ArgsResolver.merge("set", new Class[]{int.class, int.class, int.class, CharSequence.class, CharSequence.class, CharSequence.class}, new BaseMethodHook() {
                    @Override
                    protected void afterMethod(MethodHookParam param) {
                        int id = (int) param.args[2];
                        dressRow(param.thisObject, id == 8353847, ghostDrawable, clickHooked, settingsController);
                    }
                }));
            } else {
                // R8 builds inline set() into the factory's bindView(View, UItem, ...)
                XposedBridge.hookAllMethods(SettingsActivity$SettingCell$FactoryClass, Obfuscate.getMethodName("SettingsActivity$SettingCell$Factory", "bindView"), new BaseMethodHook() {
                    @Override
                    protected void afterMethod(MethodHookParam param) {
                        Object item = findArg(param.args, UItemClass);
                        View view = findArg(param.args, settingCellClass);
                        if (view == null) view = findArg(param.args, View.class);
                        if (item != null && view != null) {
                            dressRow(view, new UItem(item).getID() == 8353847, ghostDrawable,
                                    clickHooked, settingsController);
                        }
                    }
                });
            }

            // in R8 builds fillItems is inlined into a static lambda (SettingsActivity, ArrayList), so arguments are found by type
            HMethod.hookMethod(SettingsActivityClass, Obfuscate.getMethodName("SettingsActivity", "fillItems"),
                    ArgsResolver.merge("SettingsActivity", "fillItems", new Class[]{java.util.ArrayList.class, ClassLoad.getClass(ClassNames.UNIVERSAL_ADAPTER)}, new BaseMethodHook() {
                        @Override
                        protected void afterMethod(final MethodHookParam param) {
                            ArrayList<Object> arrayList = findArg(param.args, ArrayList.class);
                            if (arrayList != null) {

                                int color1 = 0xFFF46F6F;
                                int color2 = 0xFFDF5555;

                                Object uItem = createSettingItem(SettingsActivity$SettingCell$FactoryClass, color1, color2);
                                for (int i = 0; i < arrayList.size(); i++) {
                                    UItem item = new UItem(arrayList.get(i));

                                    if (item.getText() != null && item.getSubtext() != null) {
                                        arrayList.add(i, uItem);
                                        break;
                                    }
                                }

                            }
                        }
                    }));
        } catch (Throwable t){
            Logger.e(t);
        }
    }

    /**
     * Puts the ghost on TeleVip's own row and, where Telegram's click handler is out of reach, makes
     * the row open the settings itself: a list leaves a row that handles its own taps alone. Cells
     * are reused for other rows, so the one taken over has to be handed back when that happens.
     */
    private static void dressRow(Object cell, boolean ours, GhostDrawable ghost, boolean clickHooked,
                                 SettingsController settingsController) {
        if (!(cell instanceof View)) return;
        View view = (View) cell;
        if (!ours) {
            handBack(view);
            return;
        }
        ImageView iconView = iconOf(cell);
        if (iconView != null) {
            iconView.setImageDrawable(ghost);
        } else {
            Logger.w("Cannot find the icon of TeleVip's settings row, leaving it blank");
        }
        if (!clickHooked) takeOverClicks(view, settingsController);
    }

    /** Cells TeleVip made clickable itself, with how clickable they were before. */
    private static final Map<View, Boolean> takenOver =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    private static void takeOverClicks(View cell, SettingsController settingsController) {
        if (takenOver.containsKey(cell)) return;
        takenOver.put(cell, cell.isClickable());
        cell.setOnClickListener(v -> settingsController.openView());
    }

    private static void handBack(View cell) {
        Boolean wasClickable = takenOver.remove(cell);
        if (wasClickable == null) return;
        cell.setOnClickListener(null);
        cell.setClickable(wasClickable);   // setOnClickListener made it clickable, whatever it was
    }

    /** The cell's icon, by name where the mapping has it and by what it is otherwise. */
    private static ImageView iconOf(Object cell) {
        try {
            Object named = XposedHelpers.getObjectField(cell, Obfuscate.getFieldName("SettingsActivity$SettingCell", "iconView"));
            if (named instanceof ImageView) return (ImageView) named;
        } catch (Throwable ignored) {
            // an obfuscated build whose field name could not be worked out, the search below serves
        }
        return firstImageView(cell instanceof View ? (View) cell : null);
    }

    private static ImageView firstImageView(View view) {
        if (view instanceof ImageView) return (ImageView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ImageView found = firstImageView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T findArg(Object[] args, Class<?> type) {
        if (type == null) return null;
        for (Object arg : args) {
            if (type.isInstance(arg)) return (T) arg;
        }
        return null;
    }

    private static Object createSettingItem(Class<?> factoryClass, int color1, int color2) {
        try {
            // R8 builds only keep the overload with a value parameter
            return XposedHelpers.callStaticMethod(factoryClass, Obfuscate.getMethodName("SettingsActivity$SettingCell$Factory", "of", "int,int,int,int,CharSequence,CharSequence,CharSequence"),
                    8353847, color1, color2, 8353847, Translator.get(Keys.GhostMode), Translator.get(Keys.ByMustafa), null);
        } catch (NoSuchMethodError e) {
            return XposedHelpers.callStaticMethod(factoryClass, Obfuscate.getMethodName("SettingsActivity$SettingCell$Factory", "of"),
                    8353847, color1, color2, 8353847, Translator.get(Keys.GhostMode), Translator.get(Keys.ByMustafa));
        }
    }

    public void oldSettings(SettingsController settingsController){
        final Class<?> itemClass = XposedHelpers.findClassIfExists(Obfuscate.getClassName("org.telegram.ui.Adapters.DrawerLayoutAdapter$Item"), Utils.classLoader);

        if (itemClass != null) {
            HMethod.hookMethod(
                    ClassLoad.getClass(ClassNames.DRAWER_LAYOUT_ADAPTER),
                    Obfuscate.getMethodName("DrawerLayoutAdapter", "resetItems"),
                    new BaseMethodHook() {
                        @Override
                        protected void afterMethod(MethodHookParam param) throws Throwable {

                            DrawerLayoutAdapter drawerLayoutAdapter = new DrawerLayoutAdapter(param.thisObject);

                            ArrayList<?> items = drawerLayoutAdapter.getItems();

                            if (itemConstructor == null) {
                                itemConstructor = itemClass.getDeclaredConstructor(ArgsResolver.resolveObject("item", new Class[]{int.class, CharSequence.class, int.class}));
                                itemConstructor.setAccessible(true);
                            }

                            Object newItem = itemConstructor.newInstance(8353847, Translator.get(Keys.GhostMode), SettingsIconResolver.getIconSettings());

                            if (items instanceof ArrayList<?>) {
                                ArrayList<Object> typedItems = (ArrayList<Object>) items;
                                typedItems.add(newItem);
                            }
                        }
                    }
            );

            BaseMethodHook onCreateHook = new BaseMethodHook() {
                @Override
                protected void afterMethod(final MethodHookParam param) {

                    Object Launch = param.thisObject;

                    Object drawerLayoutAdapter = XposedHelpers.getObjectField(Launch, Obfuscate.getFieldName("LaunchActivity", "drawerLayoutAdapter"));
                    if (drawerLayoutAdapter != null) {
                        Object args = param.args[1];

                        int id = (int) XposedHelpers.callMethod(drawerLayoutAdapter, Obfuscate.getMethodName("DrawerLayoutAdapter", "getId"), args);
                        if (id == 8353847) {

                            Object drawerLayoutContainer = XposedHelpers.getObjectField(Launch, Obfuscate.getFieldName("LaunchActivity", "drawerLayoutContainer"));
                            if (drawerLayoutContainer != null) {
                                if (!ClientManager.is(ClientManager.Client.ForkgramClassic)) {
                                    XposedHelpers.callMethod(drawerLayoutContainer, Obfuscate.getMethodName("DrawerLayoutContainer", "closeDrawer"));
                                } else {
                                    XposedHelpers.callMethod(drawerLayoutContainer, Obfuscate.getMethodName("DrawerLayoutContainer", "closeDrawer"), true);
                                }
                            }

                            settingsController.openView();
                        }

                    }
                }
            };

            if (ClassLoad.getClass(ClassNames.LAUNCH_ACTIVITY) != null) {

                Method onCreateMethod = null;
                for (Method method : ClassLoad.getClass(ClassNames.LAUNCH_ACTIVITY).getDeclaredMethods()) {
                    if (Arrays.equals(method.getParameterTypes(), ArgsResolver.resolveObject("onCreateMethod", new Class[]{android.view.View.class, int.class, float.class, float.class}))) {
                        onCreateMethod = method;
                        break;
                    }
                }

                if (onCreateMethod == null) {
                    Logger.w("Failed to hook onCreateMethod! Reason: No method found, " + Utils.issue);
                    return;
                }

                XposedBridge.hookMethod(onCreateMethod, onCreateHook);
            }
        }

    }
}

