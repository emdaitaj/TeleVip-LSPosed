package com.my.televip.features.hideChats;

import static com.my.televip.application.AndroidUtilities.dp;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.my.televip.Configs.ConfigItem;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.Configs.ConfigPreferences;
import com.my.televip.features.hideChats.ui.ChatPicker;
import com.my.televip.language.Keys;
import com.my.televip.language.Translator;
import com.my.televip.logging.Logger;
import com.my.televip.settings.controller.SettingsController;
import com.my.televip.settings.ui.SettingsActivity;
import com.my.televip.ui.ThemeColors;
import com.my.televip.utils.Utils;
import com.my.televip.virtuals.ActionBar.AlertDialog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Takes the Hide Chats section out of TeleVip's settings until a secret code the user chose is
 * typed into the search field of those settings. That field is TeleVip's own and only filters the
 * settings list, so nothing typed into it goes through Telegram or reaches its servers.
 *
 * The switch and the code are committed to disk the moment they change, so a process killed right
 * afterwards, or a reboot, keeps them. Being unlocked is never stored: it lasts only while the
 * settings screen is attached, ends when Telegram leaves the screen, and every new process starts
 * locked. Only a salted hash of the code is kept.
 */
public final class HiddenSettings {

    private static final String KEY_CODE_HASH = "HideChatsSecretCodeHash";
    private static final String KEY_CODE_SALT = "HideChatsSecretCodeSalt";
    private static final int MIN_CODE_LENGTH = 4;

    private static final Handler handler = new Handler(Looper.getMainLooper());

    private static volatile boolean loaded;
    private static volatile boolean hidden;
    private static volatile byte[] salt;
    private static volatile byte[] codeHash;
    // true only while the settings screen is attached: the code is typed there, and detaching relocks
    private static volatile boolean unlocked;
    private static boolean lifecycleRegistered;
    private static SettingsController settingsController;
    // the window this class made FLAG_SECURE, never one Telegram secured itself
    private static Window securedWindow;

    private HiddenSettings() {}

    public static void attach(SettingsController controller) {
        settingsController = controller;
        Activity activity = Utils.getCurrentActivity();
        if (lifecycleRegistered || activity == null) return;
        lifecycleRegistered = true;
        activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStopped(Activity stopped) {
                // left unlocked in the background, the section would greet whoever opens Telegram next
                if (!unlocked || settingsController == null) return;
                // the chat picker sits on top of the settings and lists the managed chats
                ChatPicker.dismissCurrent();
                settingsController.hide();
            }

            @Override public void onActivityCreated(Activity a, Bundle savedInstanceState) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    public static boolean isHidden() {
        load();
        return hidden && codeHash != null && salt != null;
    }

    /**
     * Whether the settings screen lists the Hide Chats section right now.
     */
    public static boolean isSectionShown() {
        return !isHidden() || unlocked;
    }

    /**
     * Text typed into the search field of TeleVip's settings. True when it is the code: the section
     * is shown and the caller empties the field. UI thread.
     */
    public static boolean onSearchText(String text) {
        if (!isHidden() || !matches(text)) return false;
        if (!unlocked) {
            unlocked = true;
            setSecure(true);
            applyLockState();
        }
        return true;
    }

    /**
     * The settings screen went away. UI thread.
     */
    public static void relock() {
        setSecure(false);
        if (!unlocked) return;
        unlocked = false;
        applyLockState();
    }

    /**
     * The switch inside the section was tapped. Hiding asks for the code first; showing needs
     * nothing more, the switch can only be reached while the section is unlocked.
     */
    public static void onSwitchClicked(Activity activity, ConfigItem item) {
        if (item.isEnable()) {
            if (!disable()) {
                Toast.makeText(activity, Translator.get(Keys.HideChatsHideSettingsSaveFailed), Toast.LENGTH_LONG).show();
                return;
            }
            item.setEnable(false);
            applyLockState();
            return;
        }
        showCodeDialog(activity, null, code -> {
            if (!enable(code)) {
                Toast.makeText(activity, Translator.get(Keys.HideChatsHideSettingsSaveFailed), Toast.LENGTH_LONG).show();
                return;
            }
            item.setEnable(true);
            applyLockState();
            Toast.makeText(activity, Translator.get(Keys.HideChatsSettingsHidden), Toast.LENGTH_LONG).show();
        });
    }

    /**
     * @return false when the change could not be written, nothing changed then
     */
    private static boolean enable(String code) {
        byte[] newSalt = new byte[16];
        new SecureRandom().nextBytes(newSalt);
        byte[] newHash = hash(newSalt, code);
        boolean written = ConfigPreferences.edit()
                .putString(KEY_CODE_SALT, encode(newSalt))
                .putString(KEY_CODE_HASH, encode(newHash))
                .putBoolean(Keys.HideChatsHideSettings, true)
                .commit();
        if (!written) return false;
        salt = newSalt;
        codeHash = newHash;
        hidden = true;
        relock();
        return true;
    }

    /**
     * @return false when the change could not be written, nothing changed then
     */
    private static boolean disable() {
        boolean written = ConfigPreferences.edit()
                .remove(KEY_CODE_SALT)
                .remove(KEY_CODE_HASH)
                .putBoolean(Keys.HideChatsHideSettings, false)
                .commit();
        if (!written) return false;
        hidden = false;
        salt = null;
        codeHash = null;
        return true;
    }

    /**
     * While unlocked the window is kept out of screenshots and the recents thumbnail, which is taken
     * before Telegram gets to know it is leaving the screen.
     */
    private static void setSecure(boolean secure) {
        try {
            if (!secure) {
                if (securedWindow != null) securedWindow.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                securedWindow = null;
                return;
            }
            Activity activity = Utils.getCurrentActivity();
            Window window = activity != null ? activity.getWindow() : null;
            if (window == null || securedWindow != null
                    || (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) return;
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            securedWindow = window;
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    private static void applyLockState() {
        ConfigManager.updateShownItems();
        try {
            SettingsActivity screen = settingsController != null ? settingsController.settingsActivity : null;
            if (screen != null && screen.listView != null) screen.listView.getAdapter().notifyDataSetChanged();
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    private interface CodeCallback {
        void onCode(String code);
    }

    private static void showCodeDialog(Activity activity, String error, CodeCallback callback) {
        try {
            LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(dp(24), dp(4), dp(24), dp(8));
            EditText codeField = createCodeField(activity, Translator.get(Keys.HideChatsSecretCode));
            EditText repeatField = createCodeField(activity, Translator.get(Keys.HideChatsSecretCodeRepeat));
            layout.addView(codeField, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams repeatParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            repeatParams.topMargin = dp(8);
            layout.addView(repeatField, repeatParams);

            AlertDialog builder = new AlertDialog(activity);
            builder.setTitle(Translator.get(Keys.HideChatsHideSettings));
            builder.setMessage(error != null ? error : Translator.get(Keys.HideChatsSecretCodeMessage, MIN_CODE_LENGTH));
            builder.setView(layout);
            builder.setPositiveButton(Translator.get(Keys.HideChatsHideSettingsConfirm), AlertDialog.click(() -> {
                String code = normalize(String.valueOf(codeField.getText()));
                String repeat = normalize(String.valueOf(repeatField.getText()));
                InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(codeField.getWindowToken(), 0);
                String problem = code.length() < MIN_CODE_LENGTH ? Translator.get(Keys.HideChatsSecretCodeTooShort, MIN_CODE_LENGTH)
                        : !code.equals(repeat) ? Translator.get(Keys.HideChatsSecretCodeMismatch)
                        : null;
                if (problem != null) {
                    // this dialog closes itself once the click returns
                    handler.post(() -> showCodeDialog(activity, problem, callback));
                } else {
                    callback.onCode(code);
                }
            }));
            builder.setNegativeButton(Translator.get(Keys.Cancel), null);
            Dialog dialog = builder.create();
            Window window = dialog.getWindow();
            if (window != null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            dialog.show();
            codeField.requestFocus();
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    private static EditText createCodeField(Activity activity, String hint) {
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setHint(hint);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(ThemeColors.getTextColor());
        field.setHintTextColor(ThemeColors.getTextGrayColor());
        return field;
    }

    /**
     * Case-insensitive, ignoring surrounding spaces. The Turkish dotless and dotted i fold to a plain
     * i, so the code matches however a Turkish keyboard capitalises it.
     */
    private static String normalize(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replace('ı', 'i').replace("̇", "");
    }

    static boolean matches(String text) {
        byte[] currentSalt = salt;
        byte[] expected = codeHash;
        if (text == null || currentSalt == null || expected == null) return false;
        String code = normalize(text);
        return code.length() >= MIN_CODE_LENGTH && MessageDigest.isEqual(expected, hash(currentSalt, code));
    }

    private static byte[] hash(byte[] salt, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            return digest.digest(code.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static void load() {
        if (loaded) return;
        synchronized (HiddenSettings.class) {
            if (loaded) return;
            ConfigPreferences.ensureInit();
            hidden = ConfigPreferences.getBoolean(Keys.HideChatsHideSettings);
            salt = decode(ConfigPreferences.getString(KEY_CODE_SALT));
            codeHash = decode(ConfigPreferences.getString(KEY_CODE_HASH));
            loaded = true;
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private static byte[] decode(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Base64.decode(value, Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
