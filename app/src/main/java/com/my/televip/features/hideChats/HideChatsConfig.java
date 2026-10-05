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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent state of the hide chats feature.
 *
 * Selections are stored per Telegram user id (not per account slot) so they survive account
 * re-ordering and never leak onto another logged in account. "known" is the snapshot of chats and
 * contacts that existed when hiding was activated for the account, "newChats" collects the ones that
 * appeared later. Chats first seen after activation are sorted into one of them by ChatClassifier.
 */
public class HideChatsConfig {

    public static final int MODE_SHOW_ONLY_SELECTED = 0;
    public static final int MODE_HIDE_SELECTED = 1;

    private static final String PREFS_NAME = "TeleVip_HideChats";
    private static final String KEY_ACTIVATED_AT = "activated_at";
    private static final String KEY_ACCOUNT_ACTIVATED_AT = "activated_at_";
    private static final String KEY_SELECTED = "selected_";
    private static final String KEY_KNOWN = "known_";
    private static final String KEY_NEW = "new_";
    private static final long FLUSH_DELAY_MS = 2000;

    static final class AccountData {
        final long userId;
        volatile Set<Long> selected = Collections.emptySet();
        final Set<Long> known = ConcurrentHashMap.newKeySet();
        final Set<Long> newChats = ConcurrentHashMap.newKeySet();
        // first seen after activation and waiting for ChatClassifier; kept in memory only
        final Set<Long> pending = ConcurrentHashMap.newKeySet();
        // ChatClassifier requests in flight (UI thread only)
        final HashMap<Long, ChatClassifier.Probe> probes = new HashMap<>();
        // notifications of pending chats (guarded by itself)
        final HashMap<Long, NotificationsHooks.Held> held = new HashMap<>();
        // When the snapshot of this account was taken: the feature activation, or the first time the
        // account was seen afterwards (logged in later). 0 = no snapshot yet: everything seen exists.
        volatile int activatedAt;
        volatile boolean snapshotRequested;
        // contacts have no date: never classify them as new before the database snapshot is merged
        volatile boolean snapshotPending;

        AccountData(long userId) {
            this.userId = userId;
        }
    }

    private static final Map<Long, AccountData> accounts = new ConcurrentHashMap<>();
    private static final Set<Long> dirtyAccounts = ConcurrentHashMap.newKeySet();
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable flushRunnable = HideChatsConfig::flush;

    private static SharedPreferences preferences;
    private static volatile boolean enabled;
    private static volatile int mode;
    private static volatile boolean hideNewChats;
    private static volatile boolean muteNotifications;
    private static volatile int activatedAt;

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
        activatedAt = prefs.getInt(KEY_ACTIVATED_AT, 0);
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Integer) {
                if (key.startsWith(KEY_ACCOUNT_ACTIVATED_AT)) {
                    AccountData data = getOrCreate(parseUserId(key, KEY_ACCOUNT_ACTIVATED_AT));
                    if (data != null) data.activatedAt = (Integer) value;
                }
                continue;
            }
            if (!(value instanceof String)) continue;
            String text = (String) value;
            if (key.startsWith(KEY_SELECTED)) {
                AccountData data = getOrCreate(parseUserId(key, KEY_SELECTED));
                if (data != null) data.selected = Collections.unmodifiableSet(decode(text));
            } else if (key.startsWith(KEY_KNOWN)) {
                AccountData data = getOrCreate(parseUserId(key, KEY_KNOWN));
                if (data != null) data.known.addAll(decode(text));
            } else if (key.startsWith(KEY_NEW)) {
                AccountData data = getOrCreate(parseUserId(key, KEY_NEW));
                if (data != null) data.newChats.addAll(decode(text));
            }
        }
        if (activatedAt != 0) {
            // snapshots stored without their own activation time belong to the feature activation
            for (AccountData data : accounts.values()) {
                if (data.activatedAt == 0 && !data.known.isEmpty()) data.activatedAt = activatedAt;
            }
        }
        preferences = prefs;
        reloadFlags();
        return true;
    }

    static void reloadFlags() {
        enabled = ConfigPreferences.getBoolean(Keys.HideChats);
        mode = ConfigPreferences.getInt(Keys.HideChatsMode + "Int") == MODE_HIDE_SELECTED ? MODE_HIDE_SELECTED : MODE_SHOW_ONLY_SELECTED;
        hideNewChats = ConfigPreferences.getBoolean(Keys.HideChatsHideNew);
        muteNotifications = ConfigPreferences.getBoolean(Keys.HideChatsMuteNotifications, true);
    }

    public static boolean isLoaded() {
        return preferences != null;
    }

    public static boolean isEnabled() {
        return enabled && preferences != null;
    }

    public static int getMode() {
        return mode;
    }

    public static boolean isHideNewChats() {
        return hideNewChats;
    }

    public static boolean isMuteNotifications() {
        return muteNotifications;
    }

    static boolean needsSnapshot() {
        return activatedAt == 0;
    }

    /**
     * State of the account in the given slot (also while the feature is off), or null when logged out.
     */
    static AccountData getData(int account) {
        return account < 0 ? null : getOrCreate(TgAccess.getClientUserId(account));
    }

    /**
     * Rules for the given account slot, or null when nothing can be hidden there.
     */
    static HiddenFilter filterFor(int account) {
        if (!isEnabled() || account < 0) return null;
        long userId = TgAccess.getClientUserId(account);
        if (userId == 0) return null;
        AccountData data = getOrCreate(userId);
        if (data == null) return null;
        if (data.activatedAt == 0 && activatedAt != 0) requestSnapshot(account, data);
        int currentMode = mode;
        boolean currentHideNew = hideNewChats;
        if (currentMode == MODE_HIDE_SELECTED && !currentHideNew && data.selected.isEmpty()) return null;
        return new HiddenFilter(account, data, currentMode, currentHideNew, data.activatedAt);
    }

    public static Set<Long> getSelected(int account) {
        AccountData data = getOrCreate(TgAccess.getClientUserId(account));
        return data == null ? Collections.<Long>emptySet() : data.selected;
    }

    public static void setSelected(int account, Set<Long> selected) {
        AccountData data = getOrCreate(TgAccess.getClientUserId(account));
        if (data == null || preferences == null) return;
        data.selected = Collections.unmodifiableSet(new HashSet<>(selected));
        preferences.edit().putString(KEY_SELECTED + data.userId, encode(data.selected)).apply();
    }

    /**
     * Activates hiding: snapshot of the existing chats of every logged in account. UI thread only.
     */
    static void activate() {
        if (preferences == null) return;
        activatedAt = TgAccess.getServerTime(TgAccess.getSelectedAccount());
        preferences.edit().putInt(KEY_ACTIVATED_AT, activatedAt).apply();
        for (AccountData data : accounts.values()) {
            reset(data);
            markDirty(data);
        }
        int count = TgAccess.getMaxAccountCount();
        for (int account = 0; account < count; account++) {
            AccountData data = getData(account);
            if (data != null) captureSnapshot(account, data, activatedAt);
        }
    }

    /**
     * UI thread only, like activate.
     */
    static void deactivate() {
        if (preferences == null) return;
        activatedAt = 0;
        handler.removeCallbacks(flushRunnable);
        dirtyAccounts.clear();
        SharedPreferences.Editor editor = preferences.edit();
        editor.remove(KEY_ACTIVATED_AT);
        for (AccountData data : accounts.values()) {
            // held notifications stay: the next refresh delivers them
            reset(data);
            editor.remove(KEY_ACCOUNT_ACTIVATED_AT + data.userId);
            editor.remove(KEY_KNOWN + data.userId);
            editor.remove(KEY_NEW + data.userId);
        }
        editor.apply();
    }

    private static void reset(AccountData data) {
        synchronized (data) {
            data.known.clear();
            data.newChats.clear();
            data.pending.clear();
            data.activatedAt = 0;
        }
        data.snapshotRequested = false;
        data.snapshotPending = false;
        data.probes.clear();
    }

    /**
     * An account logged in after hiding was activated: its chats existed before it was seen.
     */
    private static void requestSnapshot(int account, AccountData data) {
        if (data.snapshotRequested) return;
        data.snapshotRequested = true;
        HideChats.post(() -> {
            if (data.activatedAt != 0 || activatedAt == 0 || !isEnabled()
                    || TgAccess.getClientUserId(account) != data.userId) {
                data.snapshotRequested = false;
                return;
            }
            captureSnapshot(account, data, TgAccess.getServerTime(account));
            HideChats.refreshAccount(account);
        });
    }

    static void markDirty(AccountData data) {
        if (data == null) return;
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
            if (data == null || activatedAt == 0 || data.activatedAt == 0) continue;
            editor.putInt(KEY_ACCOUNT_ACTIVATED_AT + userId, data.activatedAt);
            editor.putString(KEY_KNOWN + userId, encode(data.known));
            editor.putString(KEY_NEW + userId, encode(data.newChats));
        }
        editor.apply();
    }

    /**
     * Everything the account has right now is "known": chats in memory, contacts and (asynchronously)
     * the chats and contacts cached in its database. UI thread only.
     */
    private static void captureSnapshot(int account, AccountData data, int time) {
        Object messagesController = TgAccess.getMessagesController(account);
        synchronized (data) {
            data.activatedAt = time;
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
        }
        markDirty(data);
        data.snapshotPending = true;
        loadSnapshotFromDatabase(account, data);
    }

    /**
     * Dialogs that are cached locally but not loaded into memory yet belong to the snapshot too.
     */
    private static void loadSnapshotFromDatabase(int account, AccountData data) {
        Object storage = TgAccess.getMessagesStorage(account);
        if (storage == null) {
            data.snapshotPending = false;
            return;
        }
        int snapshotActivatedAt = data.activatedAt;
        String dialogsQuery = "SELECT did FROM dialogs WHERE date <= " + snapshotActivatedAt;
        MessagesStorage messagesStorage = new MessagesStorage(storage);
        try {
            messagesStorage.getStorageQueue().postRunnable(() -> {
                HashSet<Long> keys = new HashSet<>();
                try {
                    SQLiteDatabase database = messagesStorage.getDatabase();
                    HashMap<Integer, Long> encryptedChatUsers = readEncryptedChatUsers(database);
                    // the query may run seconds later: chats active since then are classified as they show up
                    SQLiteCursor cursor = database.queryFinalized(dialogsQuery, new Object[0]);
                    try {
                        while (cursor.next()) {
                            long dialogId = cursor.longValue(0);
                            if (DialogIds.isFolderDialogId(dialogId)) continue;
                            if (DialogIds.isEncryptedDialog(dialogId)) {
                                Long peerUserId = encryptedChatUsers.get(DialogIds.getEncryptedChatId(dialogId));
                                keys.add(peerUserId != null && peerUserId != 0 ? peerUserId : dialogId);
                            } else {
                                keys.add(dialogId);
                            }
                        }
                    } finally {
                        cursor.dispose();
                    }
                    cursor = database.queryFinalized("SELECT uid FROM contacts", new Object[0]);
                    try {
                        while (cursor.next()) {
                            keys.add(cursor.longValue(0));
                        }
                    } finally {
                        cursor.dispose();
                    }
                } catch (Throwable t) {
                    Logger.e(t);
                }
                handler.post(() -> {
                    if (data.activatedAt != snapshotActivatedAt) return;
                    data.snapshotPending = false;
                    if (!isEnabled() || keys.isEmpty()) return;
                    // Cached when the snapshot was taken, so it existed: this beats any classification
                    // made in the meantime (e.g. by message date while this query was running).
                    synchronized (data) {
                        for (Long key : keys) {
                            data.newChats.remove(key);
                            data.pending.remove(key);
                            data.known.add(key);
                        }
                    }
                    markDirty(data);
                    HideChats.refreshAccount(account);
                });
            });
        } catch (Throwable t) {
            data.snapshotPending = false;
            Logger.e(t);
        }
    }

    /**
     * Secret chat id -> user id, straight from the database. Storage thread only.
     */
    static HashMap<Integer, Long> readEncryptedChatUsers(SQLiteDatabase database) throws Exception {
        HashMap<Integer, Long> encryptedChatUsers = new HashMap<>();
        SQLiteCursor cursor = database.queryFinalized("SELECT uid, user FROM enc_chats", new Object[0]);
        try {
            while (cursor.next()) {
                encryptedChatUsers.put(cursor.intValue(0), cursor.longValue(1));
            }
        } finally {
            cursor.dispose();
        }
        return encryptedChatUsers;
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
