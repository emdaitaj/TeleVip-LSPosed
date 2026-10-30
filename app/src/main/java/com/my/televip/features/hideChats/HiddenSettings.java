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
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.my.televip.Class.ClassNames;
import com.my.televip.Configs.ConfigItem;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.Configs.ConfigPreferences;
import com.my.televip.base.BaseMethodHook;
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
import java.util.ArrayList;
import java.util.Locale;

/**
 * Takes the Hide Chats section out of TeleVip's settings until a secret code the user chose is
 * typed into the chat search.
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
    // true only while the settings screen is attached, see unlock()
    private static volatile boolean unlocked;
    private static boolean hooksInstalled;
    private static boolean searchHooked;
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
     * Runnable of the switch: at startup while the section is hidden, and when it gets hidden.
     */
    public static void install() {
        if (hooksInstalled || !isHidden()) return;
        hooksInstalled = true;
        TgAccess.init();
        searchHooked = HideChats.hook(TgAccess.findClass(ClassNames.MESSAGES_STORAGE), "MessagesStorage", "localSearch",
                int.class, String.class, ArrayList.class, ArrayList.class, ArrayList.class, ArrayList.class, int.class, new BaseMethodHook() {
                    @Override
                    protected void beforeMethod(MethodHookParam param) {
                        Object query = param.args[1];
                        if (query instanceof String && isHidden() && matches((String) query)) {
                            handler.post(HiddenSettings::unlock);
                        }
                    }
                });
        if (!searchHooked) {
            // the code could never be typed: rather turn the option off than lose the section
            Logger.w("HideChats: chat search could not be hooked, hiding the settings is turned off");
            disable();
            hidden = false;
            ConfigItem item = ConfigManager.hideChatsHideSettings;
            if (item != null) item.setEnable(false);
            return;
        }
        installRequestGuard();
    }

    /**
     * The chat search sends what was typed to Telegram's servers. A search for the code itself is
     * dropped, so the whole code never leaves the device.
     */
    private static void installRequestGuard() {
        Class<?> contactsSearch = TgAccess.findClass(ClassNames.TL_CONTACTS_SEARCH);
        Class<?> messagesSearchGlobal = TgAccess.findClass(ClassNames.TL_MESSAGES_SEARCH_GLOBAL);
        Class<?> messagesSearch = TgAccess.findClass(ClassNames.TL_MESSAGES_SEARCH);
        Class<?> channelsSearchPosts = TgAccess.findClass(ClassNames.TL_CHANNELS_SEARCH_POSTS);
        boolean hooked = SearchHooks.hookSendRequestInternal(new BaseMethodHook() {
            @Override
            protected void beforeMethod(MethodHookParam param) {
                Object request = param.args[0];
                if (request == null || !isHidden()) return;
                Object query = null;
                if (contactsSearch != null && contactsSearch.isInstance(request)) {
                    query = TgAccess.get(request, "TLRPC$TL_contacts_search", "q");
                } else if (messagesSearchGlobal != null && messagesSearchGlobal.isInstance(request)) {
                    query = TgAccess.get(request, "TLRPC$TL_messages_searchGlobal", "q");
                } else if (messagesSearch != null && messagesSearch.isInstance(request)) {
                    query = TgAccess.get(request, "TLRPC$TL_messages_search", "q");
                } else if (channelsSearchPosts != null && channelsSearchPosts.isInstance(request)) {
                    query = TgAccess.get(request, "TLRPC$TL_channels_searchPosts", "query");
                }
                // only dropped: unlocking is left to the chat search, which runs on this device anyway
                if (query instanceof String && matches((String) query)) param.setResult(null);
            }
        });
        if (!hooked) {
            Logger.w("HideChats: search requests cannot be checked for the secret code");
        }
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
            String error = enable(code);
            if (error != null) {
                Toast.makeText(activity, Translator.get(error), Toast.LENGTH_LONG).show();
                return;
            }
            item.setEnable(true);
            applyLockState();
            Toast.makeText(activity, Translator.get(Keys.HideChatsSettingsHidden), Toast.LENGTH_LONG).show();
        });
    }

    /**
     * @return null once the section is hidden, otherwise the key of the reason it is not
     */
    private static String enable(String code) {
        byte[] newSalt = new byte[16];
        new SecureRandom().nextBytes(newSalt);
        byte[] newHash = hash(newSalt, code);
        boolean written = ConfigPreferences.edit()
                .putString(KEY_CODE_SALT, encode(newSalt))
                .putString(KEY_CODE_HASH, encode(newHash))
                .putBoolean(Keys.HideChatsHideSettings, true)
                .commit();
        if (!written) return Keys.HideChatsHideSettingsSaveFailed;
        salt = newSalt;
        codeHash = newHash;
        hidden = true;
        install();
        if (!searchHooked) {
            disable();
            hidden = false;
            return Keys.HideChatsHideSettingsFailed;
        }
        relock();
        return null;
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
     * The code was typed into the chat search: show the section and open the settings on it. UI thread.
     */
    private static void unlock() {
        if (!isHidden() || unlocked || settingsController == null) return;
        unlocked = true;
        clearCodeFromScreen();
        applyLockState();
        settingsController.openView();
        View screen = settingsController.settingsView;
        if (screen == null || !screen.isAttachedToWindow()) {
            // the screen did not come up, nothing would ever lock the section again
            relock();
            return;
        }
        setSecure(true);
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

    /**
     * Empties the search field the code is still in, so it is not on screen when the user comes back.
     */
    private static void clearCodeFromScreen() {
        Activity activity = Utils.getCurrentActivity();
        Window window = activity != null ? activity.getWindow() : null;
        if (window == null) return;
        View root = window.getDecorView();
        clearMatchingFields(root);
        hideKeyboard(activity, root);
    }

    private static void clearMatchingFields(View view) {
        if (view instanceof EditText) {
            EditText field = (EditText) view;
            if (matches(String.valueOf(field.getText()))) {
                field.setText("");
                field.clearFocus();
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                clearMatchingFields(group.getChildAt(i));
            }
        }
    }

    private static void hideKeyboard(Activity activity, View view) {
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
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
                hideKeyboard(activity, codeField);
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
     * Telegram trims and lower-cases what it searches for, in the device's locale, so the code is
     * compared case-insensitively. The Turkish dotless and dotted i fold to a plain i, otherwise a
     * code with an I would never match on a Turkish or Azerbaijani phone.
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
