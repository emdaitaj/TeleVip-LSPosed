package com.my.televip.features.hideChats;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.my.televip.Configs.ConfigPreferences;
import com.my.televip.application.ApplicationLoaderHook;
import com.my.televip.language.Keys;
import com.my.televip.logging.Logger;
import com.my.televip.virtuals.SQLite.SQLiteCursor;
import com.my.televip.virtuals.SQLite.SQLiteDatabase;
import com.my.televip.virtuals.messenger.MessagesStorage;

import java.util.ArrayList;
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
    private static final String KEY_KNOWN = "known_";
    private static final long FLUSH_DELAY_MS = 2000;
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
        // chats that existed when "hide new chats" was switched on, so later ones can be told apart
        final Set<Long> known = ConcurrentHashMap.newKeySet();
        // true while the snapshot is still being read from the database: classify nothing until then
        volatile boolean knownPending;
        volatile boolean hasKnown;
        // managed minus visible, cached because the hot paths ask for it on every dialog
        private volatile Set<Long> hidden = Collections.emptySet();

        AccountData(long userId) {
            this.userId = userId;
        }

        /**
         * Managed chats that are hidden right now.
         */
        Set<Long> hiddenKeys() {
            return hidden;
        }

        void recomputeHidden() {
            Set<Long> currentManaged = managed;
            Set<Long> currentVisible = visible;
            if (currentManaged.isEmpty()) {
                hidden = Collections.emptySet();
            } else if (currentVisible.isEmpty()) {
                hidden = currentManaged;
            } else {
                HashSet<Long> result = new HashSet<>(currentManaged);
                result.removeAll(currentVisible);
                hidden = Collections.unmodifiableSet(result);
            }
        }
    }

    private static final Map<Long, AccountData> accounts = new ConcurrentHashMap<>();

    private static SharedPreferences preferences;
    private static volatile boolean enabled;
    private static volatile boolean muteNotifications;
    private static volatile boolean hideNewChats;
    private static final Set<Long> dirtyAccounts = ConcurrentHashMap.newKeySet();
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable flushRunnable = HideChatsConfig::flush;

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
            } else if (key.startsWith(KEY_KNOWN)) {
                AccountData data = getOrCreate(parseUserId(key, KEY_KNOWN));
                if (data != null) {
                    data.known.addAll(decode(text));
                    data.hasKnown = true;
                }
            }
        }
        preferences = prefs;
        migrateLegacySelection(prefs);
        for (AccountData data : accounts.values()) data.recomputeHidden();
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
        hideNewChats = ConfigPreferences.getBoolean(Keys.HideChatsHideNew);
    }

    public static boolean isHideNewChats() {
        return hideNewChats;
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
        data.recomputeHidden();
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
        data.recomputeHidden();
        preferences.edit().putString(KEY_VISIBLE + data.userId, encode(data.visible)).apply();
    }

    /**
     * A chat seen in a list. While "hide new chats" is on, anything that was not there when the
     * option was switched on joins the managed list, hidden, where the user can see and untick it.
     */
    static synchronized void observeDialog(int account, long key) {
        if (!hideNewChats || key == 0 || preferences == null) return;
        AccountData data = getOrCreate(TgAccess.getClientUserId(account));
        if (data == null || data.knownPending || !data.hasKnown) return;
        if (!data.known.add(key)) return;
        if (!data.managed.contains(key)) {
            HashSet<Long> managed = new HashSet<>(data.managed);
            managed.add(key);
            data.managed = Collections.unmodifiableSet(managed);
            data.recomputeHidden();
        }
        markDirty(data);
    }

    /**
     * Snapshot of everything the account has right now, so later chats can be told apart. Taken when
     * "hide new chats" is switched on, and for an account that logs in afterwards. UI thread only.
     */
    static void captureKnown(int account) {
        AccountData data = getOrCreate(TgAccess.getClientUserId(account));
        if (data == null || data.hasKnown || data.knownPending) return;
        data.knownPending = true;
        Object messagesController = TgAccess.getMessagesController(account);
        data.known.add(data.userId);
        ArrayList<Object> dialogs = TgAccess.getAllDialogs(messagesController);
        if (dialogs != null) {
            for (Object dialog : new ArrayList<>(dialogs)) {
                if (dialog == null || TgAccess.isDialogFolder(dialog)) continue;
                long key = TgAccess.toPeerKey(messagesController, TgAccess.getDialogId(dialog));
                if (key != 0) data.known.add(key);
            }
        }
        ArrayList<Object> contacts = TgAccess.getList(TgAccess.getContactsController(account), "ContactsController", "contacts");
        if (contacts != null) {
            for (Object contact : new ArrayList<>(contacts)) {
                long contactId = TgAccess.getContactUserId(contact);
                if (contactId != 0) data.known.add(contactId);
            }
        }
        loadKnownFromDatabase(account, data);
    }

    /**
     * Chats that are only in the local database yet existed all along.
     */
    private static void loadKnownFromDatabase(int account, AccountData data) {
        Object storage = TgAccess.getMessagesStorage(account);
        if (storage == null) {
            finishKnown(data);
            return;
        }
        MessagesStorage messagesStorage = new MessagesStorage(storage);
        try {
            messagesStorage.getStorageQueue().postRunnable(() -> {
                HashSet<Long> keys = new HashSet<>();
                try {
                    SQLiteDatabase database = messagesStorage.getDatabase();
                    readIds(database, "SELECT did FROM dialogs", keys);
                    readIds(database, "SELECT uid FROM contacts", keys);
                } catch (Throwable t) {
                    Logger.e(t);
                }
                handler.post(() -> {
                    data.known.addAll(keys);
                    finishKnown(data);
                    HideChats.refreshAccount(account);
                });
            });
        } catch (Throwable t) {
            Logger.e(t);
            finishKnown(data);
        }
    }

    private static void readIds(SQLiteDatabase database, String query, HashSet<Long> out) throws Exception {
        SQLiteCursor cursor = database.queryFinalized(query, new Object[0]);
        try {
            while (cursor.next()) {
                long id = cursor.longValue(0);
                if (id != 0 && !DialogIds.isFolderDialogId(id)) out.add(id);
            }
        } finally {
            cursor.dispose();
        }
    }

    private static void finishKnown(AccountData data) {
        data.knownPending = false;
        data.hasKnown = true;
        markDirty(data);
    }

    /**
     * "Hide new chats" was switched off: stop telling new chats apart, and forget the snapshot so
     * switching it on again starts from what exists then.
     */
    static void clearKnown() {
        if (preferences == null) return;
        SharedPreferences.Editor editor = preferences.edit();
        for (AccountData data : accounts.values()) {
            data.known.clear();
            data.hasKnown = false;
            data.knownPending = false;
            editor.remove(KEY_KNOWN + data.userId);
        }
        dirtyAccounts.clear();
        handler.removeCallbacks(flushRunnable);
        editor.apply();
    }

    private static void markDirty(AccountData data) {
        dirtyAccounts.add(data.userId);
        handler.removeCallbacks(flushRunnable);
        handler.postDelayed(flushRunnable, FLUSH_DELAY_MS);
    }

    private static void flush() {
        if (preferences == null || dirtyAccounts.isEmpty()) return;
        SharedPreferences.Editor editor = preferences.edit();
        for (Long userId : new ArrayList<>(dirtyAccounts)) {
            dirtyAccounts.remove(userId);
            AccountData data = accounts.get(userId);
            if (data == null) continue;
            editor.putString(KEY_MANAGED + userId, encode(data.managed));
            if (data.hasKnown) editor.putString(KEY_KNOWN + userId, encode(data.known));
        }
        editor.apply();
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
