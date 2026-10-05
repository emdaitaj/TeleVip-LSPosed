package com.my.televip.features.hideChats;

import com.my.televip.Class.ClassNames;
import com.my.televip.base.BaseMethodHook;
import com.my.televip.logging.Logger;
import com.my.televip.virtuals.SQLite.SQLiteCursor;
import com.my.televip.virtuals.messenger.MessagesStorage;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unread counters of folder tabs, "All chats" and the archive. MessagesStorage computes them from
 * the dialogs table: the full recount skips hidden chats and incremental updates ignore them.
 */
final class UnreadCountersHooks {

    private static final String UNREAD_DIALOGS_QUERY = "SELECT did, folder_id, unread_count, unread_count_i FROM dialogs";
    private static final String KEY_RECOUNT = "televipHideChatsRecount";
    private static final ThreadLocal<String> excludedDialogs = new ThreadLocal<>();
    // Recounts in progress on any thread. The query hook sees every SQL query of the app (it is only
    // installed while hiding is on), so it bails out on this before anything else; recounts are rare
    // (startup, folder changes, refreshes). Rewriting the one query keeps Telegram's own counting
    // logic intact, where adjusting its many per-folder counters afterwards would have to copy it.
    private static final AtomicInteger recounts = new AtomicInteger();

    private UnreadCountersHooks() {}

    static void install() {
        Class<?> messagesStorage = TgAccess.messagesStorageClass;
        Class<?> longSparseIntArray = TgAccess.longSparseIntArrayClass;

        boolean recountHooked = HideChats.hook(TgAccess.findClass(ClassNames.SQLITE_DATABASE), "SQLiteDatabase", "queryFinalized", String.class, Object[].class, new BaseMethodHook() {
            @Override
            protected void beforeMethod(MethodHookParam param) {
                if (recounts.get() == 0) return;
                String excluded = excludedDialogs.get();
                if (excluded == null) return;
                Object sql = param.args[0];
                if (sql instanceof String && ((String) sql).startsWith(UNREAD_DIALOGS_QUERY)) {
                    param.args[0] = "SELECT * FROM (" + sql + ") WHERE did NOT IN (" + excluded + ")";
                }
            }
        });

        if (recountHooked) {
            HideChats.hook(messagesStorage, "MessagesStorage", "calcUnreadCounters", boolean.class, new BaseMethodHook() {
                @Override
                protected void beforeMethod(MethodHookParam param) {
                    String excluded = buildExclusion(param.thisObject);
                    if (excluded == null) return;
                    excludedDialogs.set(excluded);
                    recounts.incrementAndGet();
                    param.setObjectExtra(KEY_RECOUNT, Boolean.TRUE);
                }

                @Override
                protected void afterMethod(MethodHookParam param) {
                    if (param.getObjectExtra(KEY_RECOUNT) == null) return;
                    excludedDialogs.remove();
                    recounts.decrementAndGet();
                }
            });
        }

        if (longSparseIntArray != null) {
            HideChats.hook(messagesStorage, "MessagesStorage", "updateFiltersReadCounter", longSparseIntArray, longSparseIntArray, boolean.class, new BaseMethodHook() {
                @Override
                protected void beforeMethod(MethodHookParam param) {
                    HiddenFilter filter = HideChatsConfig.filterFor(TgAccess.getAccount(param.thisObject));
                    if (filter == null) return;
                    param.args[0] = withoutHidden(filter, param.args[0]);
                    param.args[1] = withoutHidden(filter, param.args[1]);
                }
            });
        }
    }

    static void refresh(int account) {
        Object storage = TgAccess.getMessagesStorage(account);
        if (storage == null) return;
        new MessagesStorage(storage).getStorageQueue().postRunnable(() ->
                TgAccess.call(storage, "MessagesStorage", "resetAllUnreadCounters", false));
    }

    /**
     * Comma separated ids of hidden chats that currently have unread messages, or null.
     * Runs on the storage thread inside calcUnreadCounters; chats seen for the first time are
     * classified on the way (their last message date is right there).
     */
    private static String buildExclusion(Object messagesStorage) {
        HiddenFilter filter = HideChatsConfig.filterFor(TgAccess.getAccount(messagesStorage));
        if (filter == null) return null;
        StringBuilder builder = null;
        boolean snapshotChanged = false;
        try {
            SQLiteCursor cursor = new MessagesStorage(messagesStorage).getDatabase()
                    .queryFinalized("SELECT did, date FROM dialogs WHERE unread_count > 0 OR flags > 0 OR unread_count_i > 0", new Object[0]);
            try {
                while (cursor.next()) {
                    long dialogId = cursor.longValue(0);
                    if (filter.observeDialog(dialogId, cursor.intValue(1))) snapshotChanged = true;
                    if (!filter.isHidden(dialogId)) continue;
                    if (builder == null) {
                        builder = new StringBuilder();
                    } else {
                        builder.append(',');
                    }
                    builder.append(dialogId);
                }
            } finally {
                cursor.dispose();
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
        if (snapshotChanged) HideChatsConfig.markDirty(filter.data);
        return builder == null ? null : builder.toString();
    }

    /**
     * LongSparseIntArray (dialog id -> count) without hidden chats; the original is returned when
     * nothing is hidden and is never modified.
     */
    static Object withoutHidden(HiddenFilter filter, Object array) {
        if (array == null) return null;
        int size = TgAccess.intValue(TgAccess.call(array, "LongSparseIntArray", "size"), 0);
        Object copy = null;
        for (int i = 0; i < size; i++) {
            long dialogId = TgAccess.longValue(TgAccess.call(array, "LongSparseIntArray", "keyAt", i), 0);
            if (!filter.isHidden(dialogId)) continue;
            if (copy == null) {
                copy = TgAccess.call(array, "LongSparseIntArray", "clone");
                if (copy == null) return array;
            }
            TgAccess.call(copy, "LongSparseIntArray", "delete", dialogId);
        }
        return copy != null ? copy : array;
    }
}
