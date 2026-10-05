package com.my.televip.features.hideChats;

import com.my.televip.Class.ClassNames;
import com.my.televip.base.BaseMethodHook;
import com.my.televip.virtuals.messenger.DispatchQueue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Optional ("Mute hidden chats"): no notifications, no badge contribution from hidden chats.
 * Installed from Application.onCreate as well, because pushes can start the process without UI.
 *
 * Notifications of chats still being classified (ChatClassifier) are held back instead of dropped and
 * delivered once the chat turns out to be visible.
 */
final class NotificationsHooks {

    private static final int MAX_HELD_MESSAGES = 100;

    /**
     * Held back notifications of one pending chat (all its dialogs: a user and its secret chats).
     */
    static final class Held {
        final ArrayList<Object> messages = new ArrayList<>();
        final ArrayList<Object> pushMessages = new ArrayList<>();
        // dialog id -> unread count
        final HashMap<Long, Integer> unread = new HashMap<>();

        boolean isEmpty() {
            return messages.isEmpty() && pushMessages.isEmpty() && unread.isEmpty();
        }
    }

    private NotificationsHooks() {}

    static void install() {
        TgAccess.init();
        Class<?> notificationsController = TgAccess.notificationsControllerClass;
        Class<?> longSparseArray = TgAccess.findClass(ClassNames.LONG_SPARES_ARRAY);
        Class<?> longSparseIntArray = TgAccess.longSparseIntArrayClass;

        HideChats.hook(notificationsController, "NotificationsController", "processNewMessages", ArrayList.class, boolean.class, boolean.class, CountDownLatch.class, new BaseMethodHook() {
            @Override
            protected void beforeMethod(MethodHookParam param) {
                HiddenFilter filter = mutingFilter(param.thisObject);
                if (filter == null || !(param.args[0] instanceof ArrayList)) return;
                //noinspection unchecked
                ArrayList<Object> messages = (ArrayList<Object>) param.args[0];
                boolean push = (boolean) param.args[2];
                ArrayList<Object> visible = new ArrayList<>(messages.size());
                boolean snapshotChanged = false;
                for (Object messageObject : messages) {
                    if (messageObject == null) {
                        visible.add(null);
                        continue;
                    }
                    long dialogId = TgAccess.getMessageObjectDialogId(messageObject);
                    // the first message of a chat may arrive before any chat list update
                    if (filter.observeDialog(dialogId, TgAccess.getMessageObjectDate(messageObject))) {
                        snapshotChanged = true;
                    }
                    if (!filter.isHidden(dialogId)) {
                        visible.add(messageObject);
                    } else if (filter.isPending(dialogId)) {
                        hold(filter, dialogId, messageObject, push);
                    }
                }
                if (snapshotChanged) {
                    HideChatsConfig.markDirty(filter.data);
                    HideChats.scheduleCountersRefresh(filter.account);
                }
                if (visible.size() != messages.size()) {
                    param.args[0] = visible;
                }
            }
        });

        if (longSparseArray != null) {
            HideChats.hook(notificationsController, "NotificationsController", "processLoadedUnreadMessages", longSparseArray, ArrayList.class, ArrayList.class, ArrayList.class, ArrayList.class, ArrayList.class, Collection.class, new BaseMethodHook() {
                @Override
                protected void beforeMethod(MethodHookParam param) {
                    HiddenFilter filter = mutingFilter(param.thisObject);
                    if (filter == null) return;
                    boolean snapshotChanged = false;
                    if (param.args[1] instanceof ArrayList) {
                        for (Object message : (ArrayList<?>) param.args[1]) {
                            if (message != null && filter.observeDialog(TgAccess.getMessageDialogId(message), TgAccess.getMessageDate(message))) {
                                snapshotChanged = true;
                            }
                        }
                    }
                    if (param.args[2] instanceof ArrayList) {
                        for (Object messageObject : (ArrayList<?>) param.args[2]) {
                            if (messageObject != null && filter.observeDialog(TgAccess.getMessageObjectDialogId(messageObject), TgAccess.getMessageObjectDate(messageObject))) {
                                snapshotChanged = true;
                            }
                        }
                    }
                    if (snapshotChanged) {
                        HideChatsConfig.markDirty(filter.data);
                        HideChats.scheduleCountersRefresh(filter.account);
                    }
                    Object dialogs = param.args[0];
                    if (dialogs != null) {
                        for (int i = TgAccess.intValue(TgAccess.call(dialogs, "LongSparseArray", "size"), 0) - 1; i >= 0; i--) {
                            long dialogId = TgAccess.longValue(TgAccess.call(dialogs, "LongSparseArray", "keyAt", i), 0);
                            if (filter.isHidden(dialogId)) {
                                TgAccess.call(dialogs, "LongSparseArray", "removeAt", i);
                            }
                        }
                    }
                    if (param.args[1] instanceof ArrayList) {
                        //noinspection unchecked
                        ((ArrayList<Object>) param.args[1]).removeIf(message -> message != null && filter.isHidden(TgAccess.getMessageDialogId(message)));
                    }
                    if (param.args[2] instanceof ArrayList) {
                        //noinspection unchecked
                        ((ArrayList<Object>) param.args[2]).removeIf(messageObject -> messageObject != null && filter.isHidden(TgAccess.getMessageObjectDialogId(messageObject)));
                    }
                    if (param.args[6] instanceof Collection) {
                        //noinspection unchecked
                        ((Collection<Object>) param.args[6]).removeIf(story -> story != null && filter.isHidden(TgAccess.longValue(TgAccess.get(story, "NotificationsController$StoryNotification", "dialogId"), 0)));
                    }
                }
            });
        }

        if (longSparseIntArray != null) {
            HideChats.hook(notificationsController, "NotificationsController", "processDialogsUpdateRead", longSparseIntArray, new BaseMethodHook() {
                @Override
                protected void beforeMethod(MethodHookParam param) {
                    HiddenFilter filter = mutingFilter(param.thisObject);
                    if (filter == null) return;
                    param.args[0] = withoutHiddenUnread(filter, param.args[0]);
                }
            });
        }

        HideChats.hook(notificationsController, "NotificationsController", "getTotalAllUnreadCount", new BaseMethodHook() {
            @Override
            protected void afterMethod(MethodHookParam param) {
                if (!(param.getResult() instanceof Integer)) return;
                int hiddenUnread = countHiddenBadgeUnread();
                if (hiddenUnread > 0) {
                    param.setResult(Math.max(0, (int) param.getResult() - hiddenUnread));
                }
            }
        });
    }

    /**
     * Delivers or drops held notifications, then clears notifications that are already shown for
     * chats that are hidden now and refreshes the badge.
     */
    static void refresh(int account) {
        HideChatsConfig.AccountData data = HideChatsConfig.getData(account);
        if (data != null) settleHeld(account, data);
        Object controller = TgAccess.getNotificationsController(account);
        Object queue = TgAccess.getStatic(TgAccess.notificationsControllerClass, "NotificationsController", "notificationsQueue");
        if (controller == null || queue == null) return;
        new DispatchQueue(queue).postRunnable(() -> {
            HiddenFilter filter = mutingFilter(controller);
            if (filter != null) {
                Object pushDialogs = TgAccess.get(controller, "NotificationsController", "pushDialogs");
                int size = TgAccess.intValue(TgAccess.call(pushDialogs, "LongSparseArray", "size"), 0);
                ArrayList<Long> hidden = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    long dialogId = TgAccess.longValue(TgAccess.call(pushDialogs, "LongSparseArray", "keyAt", i), 0);
                    if (dialogId != 0 && filter.isHidden(dialogId)) hidden.add(dialogId);
                }
                for (Long dialogId : hidden) {
                    TgAccess.call(controller, "NotificationsController", "removeNotificationsForDialog", dialogId);
                }
            }
            TgAccess.call(controller, "NotificationsController", "updateBadge");
        });
    }

    /**
     * Hands held notifications of classified chats back to Telegram when the chat is visible (or
     * muting is off now) and drops them otherwise. Chats that are still pending stay held.
     * Must run on the UI thread, like Telegram's own calls into NotificationsController.
     */
    static void settleHeld(int account, HideChatsConfig.AccountData data) {
        HiddenFilter filter = HideChatsConfig.isMuteNotifications() ? HideChatsConfig.filterFor(account) : null;
        if (filter != null && filter.data != data) return;
        ArrayList<Held> deliver = new ArrayList<>();
        synchronized (data.held) {
            if (data.held.isEmpty()) return;
            Iterator<Map.Entry<Long, Held>> iterator = data.held.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, Held> entry = iterator.next();
                long key = entry.getKey();
                if (filter != null && data.pending.contains(key)) continue;
                iterator.remove();
                if (filter == null || !filter.isHiddenKey(key)) deliver.add(entry.getValue());
            }
        }
        if (deliver.isEmpty()) return;
        Object controller = TgAccess.getNotificationsController(account);
        Class<?>[] newMessagesTypes = {ArrayList.class, boolean.class, boolean.class, CountDownLatch.class};
        for (Held held : deliver) {
            if (!held.pushMessages.isEmpty()) {
                TgAccess.callWithTypes(controller, "NotificationsController", "processNewMessages", newMessagesTypes, held.pushMessages, true, true, null);
            }
            if (!held.messages.isEmpty()) {
                TgAccess.callWithTypes(controller, "NotificationsController", "processNewMessages", newMessagesTypes, held.messages, true, false, null);
            }
            // the notification itself is posted when the unread counters change
            Object unread = held.unread.isEmpty() ? null : TgAccess.newLongSparseIntArray(held.unread);
            if (unread != null) {
                TgAccess.callWithTypes(controller, "NotificationsController", "processDialogsUpdateRead", new Class[]{TgAccess.longSparseIntArrayClass}, unread);
            }
        }
    }

    private static void hold(HiddenFilter filter, long dialogId, Object messageObject, boolean push) {
        long key = filter.toPeerKey(dialogId);
        HashMap<Long, Held> held = filter.data.held;
        synchronized (held) {
            Held entry = held.get(key);
            if (entry == null) {
                entry = new Held();
                held.put(key, entry);
            }
            ArrayList<Object> messages = push ? entry.pushMessages : entry.messages;
            if (messages.size() < MAX_HELD_MESSAGES) messages.add(messageObject);
        }
        settleIfClassified(filter, key);
    }

    /**
     * Pushes are processed off the UI thread: the chat may have been classified (and its held
     * notifications settled) between the pending check and the hold.
     */
    private static void settleIfClassified(HiddenFilter filter, long key) {
        if (filter.data.pending.contains(key)) return;
        int account = filter.account;
        HideChatsConfig.AccountData data = filter.data;
        HideChats.runOnUiThread(() -> settleHeld(account, data));
    }

    /**
     * Unread changes of a pending chat: increments are kept for later, a read (0) drops what was held
     * for that dialog, decrements adjust it.
     */
    private static void holdUnread(HiddenFilter filter, long dialogId, int count) {
        long key = filter.toPeerKey(dialogId);
        HashMap<Long, Held> held = filter.data.held;
        synchronized (held) {
            Held entry = held.get(key);
            if (count > 0) {
                if (entry == null) {
                    entry = new Held();
                    held.put(key, entry);
                }
                entry.unread.put(dialogId, count);
            } else if (entry != null) {
                if (count == 0) {
                    entry.unread.remove(dialogId);
                    entry.messages.removeIf(messageObject -> TgAccess.getMessageObjectDialogId(messageObject) == dialogId);
                    entry.pushMessages.removeIf(messageObject -> TgAccess.getMessageObjectDialogId(messageObject) == dialogId);
                } else {
                    Integer current = entry.unread.get(dialogId);
                    if (current != null) {
                        int value = current + count;
                        if (value > 0) {
                            entry.unread.put(dialogId, value);
                        } else {
                            entry.unread.remove(dialogId);
                        }
                    }
                }
                if (entry.isEmpty()) held.remove(key);
            }
        }
        if (count > 0) settleIfClassified(filter, key);
    }

    /**
     * dialog id -> unread count without the increments of hidden chats. Reads (0) and decrements must
     * pass: they only clear existing state, and removeNotificationsForDialog() works through them.
     * The original array is returned when nothing changes and is never modified.
     */
    private static Object withoutHiddenUnread(HiddenFilter filter, Object array) {
        if (array == null) return null;
        int size = TgAccess.intValue(TgAccess.call(array, "LongSparseIntArray", "size"), 0);
        Object copy = null;
        for (int i = 0; i < size; i++) {
            long dialogId = TgAccess.longValue(TgAccess.call(array, "LongSparseIntArray", "keyAt", i), 0);
            if (!filter.isHidden(dialogId)) continue;
            int count = TgAccess.intValue(TgAccess.call(array, "LongSparseIntArray", "valueAt", i), 0);
            if (filter.isPending(dialogId)) holdUnread(filter, dialogId, count);
            if (count <= 0) continue;
            if (copy == null) {
                copy = TgAccess.call(array, "LongSparseIntArray", "clone");
                if (copy == null) return array;
            }
            TgAccess.call(copy, "LongSparseIntArray", "delete", dialogId);
        }
        return copy != null ? copy : array;
    }

    private static HiddenFilter mutingFilter(Object notificationsController) {
        if (!HideChatsConfig.isEnabled() || !HideChatsConfig.isMuteNotifications()) return null;
        return HideChatsConfig.filterFor(TgAccess.getAccount(notificationsController));
    }

    /**
     * Mirrors NotificationsController.getTotalAllUnreadCount() for the "count muted chats" mode, the
     * only mode that counts from the chat list instead of the (already filtered) pushed messages.
     */
    private static int countHiddenBadgeUnread() {
        if (!HideChatsConfig.isEnabled() || !HideChatsConfig.isMuteNotifications()) return 0;
        boolean allAccounts = TgAccess.boolValue(TgAccess.getStatic(TgAccess.sharedConfigClass, "SharedConfig", "showNotificationsForAllAccounts"), true);
        int selectedAccount = TgAccess.getSelectedAccount();
        int total = 0;
        for (int account = 0, count = TgAccess.getMaxAccountCount(); account < count; account++) {
            if (!allAccounts && account != selectedAccount) continue;
            Object controller = TgAccess.getNotificationsController(account);
            if (controller == null) continue;
            if (!TgAccess.boolValue(TgAccess.get(controller, "NotificationsController", "showBadgeNumber"), false)) continue;
            if (!TgAccess.boolValue(TgAccess.get(controller, "NotificationsController", "showBadgeMuted"), false)) continue;
            HiddenFilter filter = HideChatsConfig.filterFor(account);
            if (filter == null) continue;
            boolean countMessages = TgAccess.boolValue(TgAccess.get(controller, "NotificationsController", "showBadgeMessages"), true);
            Object messagesController = TgAccess.getMessagesController(account);
            ArrayList<Object> dialogs = TgAccess.getAllDialogs(messagesController);
            if (dialogs == null) continue;
            Object[] snapshot;
            try {
                snapshot = dialogs.toArray();
            } catch (Throwable t) {
                continue;
            }
            for (Object dialog : snapshot) {
                if (dialog == null || TgAccess.isDialogFolder(dialog)) continue;
                long dialogId = TgAccess.getDialogId(dialog);
                if (!filter.isHidden(dialogId)) continue;
                int unread;
                if (DialogIds.isChatDialog(dialogId)) {
                    Object chat = TgAccess.getChat(messagesController, -dialogId);
                    if (chat == null || TgAccess.boolValue(TgAccess.callStatic(TgAccess.chatObjectClass, "ChatObject", "isNotInChat", chat), true)) continue;
                    unread = TgAccess.intValue(TgAccess.call(messagesController, "MessagesController", "getDialogUnreadCount", dialog), 0);
                } else {
                    unread = TgAccess.getDialogUnreadCount(dialog);
                }
                if (unread <= 0) continue;
                total += countMessages ? unread : 1;
            }
        }
        return total;
    }
}
