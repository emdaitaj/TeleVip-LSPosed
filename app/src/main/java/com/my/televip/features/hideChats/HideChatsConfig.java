package com.my.televip.features.hideChats;

import android.content.Context;
import android.content.SharedPreferences;

import com.my.televip.Configs.ConfigPreferences;
import com.my.televip.application.ApplicationLoaderHook;
import com.my.televip.language.Keys;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent state of the hide chats feature.
 *
 * Two sets per account: "managed" are the chats the user put under the feature's control, "visible"
 * are the managed chats that stay visible while hiding is on. A chat that was never added to the
 * managed list is never touched, so chats appearing later are unaffected.
 *
 *   hidden = enabled ? managed \ visible : {}
 *
 * Both are stored per Telegram user id (not per account slot) so they survive account re-ordering
 * and never leak onto another logged in account.
 */
public class HideChatsConfig {

    private static final String PREFS_NAME = "TeleVip_HideChats";
    private static final String KEY_MANAGED = "managed_";
    private static final String KEY_VISIBLE = "visible_";
    // selection of the previous (mode based) design, migrated into the managed list once
    private static final String LEGACY_KEY_SELECTED = "selected_";
    private static final String LEGACY_KEY_ACTIVATED_AT = "activated_at";
    // ConfigPreferences key of the removed mode setting, and the value that meant "hide selected"
    private static final String LEGACY_KEY_MODE = "HideChatsModeInt";
    private static final int LEGACY_MODE_HIDE_SELECTED = 1;

    static final class AccountData {
        final long userId;
        volatile Set<Long> managed = Collections.emptySet();
        volatile Set<Long> visible = Collections.emptySet();

        AccountData(long userId) {
            this.userId = userId;
        }

        /**
         * Managed chats that are hidden right now. Computed per read, both sets are immutable.
         */
        Set<Long> hiddenKeys() {
            Set<Long> currentManaged = managed;
            Set<Long> currentVisible = visible;
            if (currentManaged.isEmpty()) return Collections.emptySet();
            if (currentVisible.isEmpty()) return currentManaged;
            HashSet<Long> hidden = new HashSet<>(currentManaged);
            hidden.removeAll(currentVisible);
            return hidden;
        }
    }

    private static final Map<Long, AccountData> accounts = new ConcurrentHashMap<>();

    private static SharedPreferences preferences;
    private static volatile boolean enabled;
    private static volatile boolean muteNotifications;

    public static synchronized boolean load() {
        TgAccess.init();
        if (preferences != null) {
            reloadFlags();
            return true;
        }
        Context context = ApplicationLoaderHook.getApplicationContext();
        if (context == null) return false;
        ConfigPreferences.ensureInit();
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (!(entry.getValue() instanceof String)) continue;
            String text = (String) entry.getValue();
            if (key.startsWith(KEY_MANAGED)) {
                AccountData data = getOrCreate(parseUserId(key, KEY_MANAGED));
                if (data != null) data.managed = Collections.unmodifiableSet(decode(text));
            } else if (key.startsWith(KEY_VISIBLE)) {
                AccountData data = getOrCreate(parseUserId(key, KEY_VISIBLE));
                if (data != null) data.visible = Collections.unmodifiableSet(decode(text));
            }
        }
        preferences = prefs;
        migrateLegacySelection(prefs);
        reloadFlags();
        return true;
    }

    /**
     * Drops the state of the previous (mode based) design.
     *
     * Its "hide selected chats" mode maps exactly onto the new one, so that selection becomes the
     * managed list with nothing visible. Its "show only selected chats" mode hid every chat that was
     * not selected, which the new design cannot express (only listed chats are ever hidden), and
     * carrying the selection over would hide exactly the chats the user had kept visible. Those
     * accounts therefore start with an empty list and the user picks the chats to hide.
     */
    private static void migrateLegacySelection(SharedPreferences prefs) {
        if (!prefs.contains(LEGACY_KEY_ACTIVATED_AT) && !hasLegacySelection(prefs)) return;
        boolean selectionMeantHidden = ConfigPreferences.getInt(LEGACY_KEY_MODE) == LEGACY_MODE_HIDE_SELECTED;
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(KEY_MANAGED) || key.startsWith(KEY_VISIBLE)) continue;
            editor.remove(key);
            if (!selectionMeantHidden || !key.startsWith(LEGACY_KEY_SELECTED) || !(entry.getValue() instanceof String)) continue;
            long userId = parseUserId(key, LEGACY_KEY_SELECTED);
            AccountData data = getOrCreate(userId);
            if (data == null || !data.managed.isEmpty()) continue;
            Set<Long> selected = Collections.unmodifiableSet(decode((String) entry.getValue()));
            if (selected.isEmpty()) continue;
            data.managed = selected;
            data.visible = Collections.emptySet();
            editor.putString(KEY_MANAGED + userId, encode(selected));
            editor.putString(KEY_VISIBLE + userId, "");
        }
        editor.apply();
    }

    private static boolean hasLegacySelection(SharedPreferences prefs) {
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(LEGACY_KEY_SELECTED)) return true;
        }
        return false;
    }

    static void reloadFlags() {
        enabled = ConfigPreferences.getBoolean(Keys.HideChats);
        muteNotifications = ConfigPreferences.getBoolean(Keys.HideChatsMuteNotifications, true);
    }

    public static boolean isLoaded() {
        return preferences != null;
    }

    public static boolean isEnabled() {
        return enabled && preferences != null;
    }

    public static boolean isMuteNotifications() {
        return muteNotifications;
    }

    /**
     * Rules for the given account slot, or null when nothing is hidden there.
     */
    static HiddenFilter filterFor(int account) {
        if (!isEnabled() || account < 0) return null;
        long userId = TgAccess.getClientUserId(account);
        if (userId == 0) return null;
        AccountData data = getOrCreate(userId);
        if (data == null) return null;
        Set<Long> hidden = data.hiddenKeys();
        if (hidden.isEmpty()) return null;
        return new HiddenFilter(account, data, hidden);
    }

    public static Set<Long> getManaged(int account) {
        AccountData data = getOrCreate(TgAccess.getClientUserId(account));
        return data == null ? Collections.<Long>emptySet() : data.managed;
    }

    public static Set<Long> getVisible(int account) {
        AccountData data = getOrCreate(TgAccess.getClientUserId(account));
        return data == null ? Collections.<Long>emptySet() : data.visible;
    }

    /**
     * Replaces the managed list. Chats that leave it also leave the visible set; chats that join it
     * start out hidden, which is what the user asked for by adding them.
     */
    public static void setManaged(int account, Set<Long> managed) {
        AccountData data = getOrCreate(TgAccess.getClientUserId(account));
        if (data == null || preferences == null) return;
        HashSet<Long> newManaged = new HashSet<>(managed);
        HashSet<Long> newVisible = new HashSet<>(data.visible);
        newVisible.retainAll(newManaged);
        data.managed = Collections.unmodifiableSet(newManaged);
        data.visible = Collections.unmodifiableSet(newVisible);
        preferences.edit()
                .putString(KEY_MANAGED + data.userId, encode(data.managed))
                .putString(KEY_VISIBLE + data.userId, encode(data.visible))
                .apply();
    }

    public static void setVisible(int account, Set<Long> visible) {
        AccountData data = getOrCreate(TgAccess.getClientUserId(account));
        if (data == null || preferences == null) return;
        HashSet<Long> newVisible = new HashSet<>(visible);
        newVisible.retainAll(data.managed);
        data.visible = Collections.unmodifiableSet(newVisible);
        preferences.edit().putString(KEY_VISIBLE + data.userId, encode(data.visible)).apply();
    }

    private static AccountData getOrCreate(long userId) {
        if (userId == 0) return null;
        AccountData data = accounts.get(userId);
        if (data == null) {
            data = new AccountData(userId);
            AccountData previous = accounts.putIfAbsent(userId, data);
            if (previous != null) data = previous;
        }
        return data;
    }

    private static long parseUserId(String key, String prefix) {
        try {
            return Long.parseLong(key.substring(prefix.length()));
        } catch (Throwable t) {
            return 0;
        }
    }

    static String encode(Set<Long> ids) {
        StringBuilder builder = new StringBuilder(ids.size() * 12);
        for (Long id : ids) {
            if (id == null) continue;
            if (builder.length() > 0) builder.append(',');
            builder.append(id.longValue());
        }
        return builder.toString();
    }

    static HashSet<Long> decode(String value) {
        HashSet<Long> result = new HashSet<>();
        if (value == null || value.isEmpty()) return result;
        int start = 0;
        int length = value.length();
        while (start < length) {
            int end = value.indexOf(',', start);
            if (end < 0) end = length;
            if (end > start) {
                try {
                    result.add(Long.parseLong(value.substring(start, end)));
                } catch (NumberFormatException ignored) {}
            }
            start = end + 1;
        }
        return result;
    }
}
