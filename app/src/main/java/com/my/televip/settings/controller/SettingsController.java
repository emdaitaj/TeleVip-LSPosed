package com.my.televip.settings.controller;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import com.my.televip.Configs.ConfigPreferences;
import com.my.televip.language.Keys;
import com.my.televip.language.Translator;
import com.my.televip.settings.ui.SettingsActivity;
import com.my.televip.logging.Logger;
import com.my.televip.utils.Utils;
import com.my.televip.virtuals.ActionBar.AlertDialog;
import com.my.televip.virtuals.messenger.browser.Browser;
import com.my.televip.virtuals.ui.LaunchActivity;

public class SettingsController {

    public FrameLayout settingsView;

    public SettingsActivity settingsActivity;

    public SettingsController() {}

    public void openView(){
        try {
            if (settingsView == null) {
                settingsView = new FrameLayout(getContext());
            }

            settingsView.removeAllViews();
            settingsActivity = new SettingsActivity(getContext());
            showJoinTeleVip();

            settingsView.addView(settingsActivity.createView(this));

            show(settingsView);
        } catch (Throwable e) {
            Logger.e(e);
        }
    }

    private void showJoinTeleVip() {
        try {
            if (!ConfigPreferences.getBoolean("JTV")) {
                AlertDialog alertDialog = new AlertDialog(getContext());

                alertDialog.setTitle(Translator.get(Keys.GhostMode));
                alertDialog.setMessage(Translator.get(Keys.JoinTeleVip));

                alertDialog.setPositiveButton(Translator.get(Keys.Join), AlertDialog.click(() -> {
                    Browser.openUrl(getContext(), "https://t.me/t_l0_e");
                    hide();
                }));

                alertDialog.setNegativeButton(Translator.get(Keys.Cancel), null);
                alertDialog.setNeutralButton(Translator.get(Keys.DontShowAgain), AlertDialog.click(() -> ConfigPreferences.putBoolean("JTV", true)));
                alertDialog.show();
            }
        } catch (Throwable e) {
            Logger.e(e);
        }
    }

    public void show(View target) {
        FrameLayout container = new LaunchActivity(Utils.getCurrentActivity()).frameLayout;
        if (container == null) return;

        if (target.getParent() == null) {
            container.addView(target);
        }

        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            child.setVisibility(child == target ? View.VISIBLE : View.GONE);
        }

        target.bringToFront();
    }

    public void hide() {
        SettingsActivity.isSettings = false;
        if (settingsView == null) return;

        ViewParent parent = settingsView.getParent();
        if (!(parent instanceof ViewGroup)) return;
        ViewGroup container = (ViewGroup) parent;

        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            child.setVisibility(child == settingsView ? View.GONE : View.VISIBLE);
        }

        container.removeView(settingsView);
    }

    public Context getContext() {
        return Utils.getCurrentActivity();
    }

}
