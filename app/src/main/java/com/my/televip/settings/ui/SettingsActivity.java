package com.my.televip.settings.ui;


import static com.my.televip.application.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.Drawable.ArrowDrawable;
import com.my.televip.base.BaseMethodHook;
import com.my.televip.dex.DexInjector;
import com.my.televip.features.hideChats.HiddenSettings;
import com.my.televip.features.hideChats.ui.ChatPicker;
import com.my.televip.hooks.HMethod;
import com.my.televip.language.Keys;
import com.my.televip.language.Translator;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.Obfuscate;
import com.my.televip.settings.adapter.ListAdapter;
import com.my.televip.settings.controller.SettingsController;
import com.my.televip.ui.ThemeColors;
import com.my.televip.ui.toolBar.MainToolBar;
import com.my.televip.virtuals.ActionBar.Theme;
import com.my.televip.virtuals.TeleVip.Bridge.Bridge;
import com.my.televip.virtuals.ui.Components.RecyclerListView;

import de.robv.android.xposed.XposedHelpers;

public class SettingsActivity {

    public static boolean isSettings;

    private final Context context;

    public RecyclerListView listView;


    public View createView(SettingsController settingsController) {

        LinearLayout layout = new LinearLayout(context);

        try {
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setBackgroundColor(ThemeColors.getBackgroundGrayColor());

            MainToolBar toolbar = new MainToolBar(context);

            toolbar.setColorTitle(ThemeColors.getTextToolBarColor());
            toolbar.setRippleColor(ThemeColors.getToolBarRippleColor());
            toolbar.setTextTitle(Translator.get(Keys.GhostMode));

            ArrowDrawable arrow = new ArrowDrawable();
            toolbar.setImageDrawable(arrow);
            toolbar.getImage().setOnClickListener(v -> settingsController.hide());

            layout.addView(toolbar);
            layout.addView(createSearchField(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            listView = new RecyclerListView(context);
            if (DexInjector.classLoader != null) {
                Object adapter = XposedHelpers.newInstance(
                        ClassLoad.getClass(ClassNames.SETTINGS_ADAPTER_LIST_ADAPTER, DexInjector.classLoader),
                        context);

                listView.setAdapter(adapter);
                listView.setLayoutManager(Bridge.getLayoutManager(context));
            } else {
                listView.setAdapter(new ListAdapter(context, settingsController));
                listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
            }

            listView.setBackgroundColor(ThemeColors.getBackgroundWhiteOrBlueColor());

            listView.setVerticalScrollBarEnabled(false);

            LinearLayout.LayoutParams recyclerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
            );
            recyclerParams.setMargins(10, 10, 10, 0);

            layout.addView(listView.getRecyclerListView(), recyclerParams);

        } catch (Throwable e) {
            Logger.e(e);
        }

        return layout;
    }

    /**
     * Filters the rows by name. It is also where the hidden Hide Chats section is unlocked: the field
     * is TeleVip's own, so what is typed never reaches Telegram's search or its servers.
     */
    private View createSearchField() {
        ConfigManager.setFilter("");
        LinearLayout container = new LinearLayout(context);
        container.setBackgroundColor(ThemeColors.getToolBarColor());
        container.setPadding(dp(14), dp(4), dp(14), dp(10));
        EditText field = new EditText(context);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setHint(Translator.get(Keys.SearchSettings));
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(ThemeColors.getTextColor());
        field.setHintTextColor(ThemeColors.getTextGrayColor());
        field.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        field.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(20));
        background.setColor(Theme.isLight() ? 0xFFF0F2F5 : 0xFF17212B);
        field.setBackground(background);
        field.setPadding(dp(16), dp(9), dp(16), dp(9));
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (HiddenSettings.onSearchText(s.toString())) {
                    // the code is not left on screen; the emptied field lists every row again
                    s.clear();
                    return;
                }
                ConfigManager.setFilter(s.toString());
                try {
                    if (listView != null) listView.getAdapter().notifyDataSetChanged();
                } catch (Throwable t) {
                    Logger.e(t);
                }
            }
        });
        container.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return container;
    }

    public SettingsActivity(Context context) {
        this.context = context;
        isSettings = true;
    }

    public static void init(SettingsController settingsController) {
        try {
            HMethod.hookMethod(ClassLoad.getClass(ClassNames.LAUNCH_ACTIVITY), "onBackPressed", new BaseMethodHook() {
                @Override
                protected void beforeMethod(MethodHookParam param) {
                    if (ChatPicker.dismissCurrent()) {
                        param.setResult(null);
                        return;
                    }
                    if (isSettings) {
                        settingsController.hide();
                        settingsController.settingsView = null;
                        param.setResult(null);
                    }
                }
            });

            HMethod.hookMethod(ClassLoad.getClass(ClassNames.ANDROID_UTILITIES), Obfuscate.getMethodName("AndroidUtilities", "isTabletInternal"), new BaseMethodHook() {
                @Override
                protected void beforeMethod(MethodHookParam param) {
                    if (isSettings) {
                        param.setResult(true);
                    }
                }
            });
        } catch (Throwable e) {
            Logger.e(e);
        }
    }
}
