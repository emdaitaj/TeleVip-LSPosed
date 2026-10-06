package com.my.televip.features.hideChats;

import com.my.televip.Class.ClassNames;
import com.my.televip.base.BaseMethodHook;
import com.my.televip.virtuals.messenger.DispatchQueue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;

/**
 * Optional ("Mute hidden chats"): no notifications, no badge contribution from hidden chats.
 * Installed from Application.onCreate as well, because pushes can start the process without UI.
 */
final class NotificationsHooks {

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
                ArrayList<Object> visible = new ArrayList<>(messages.size());
                for (Object messageObject : messages) {
                    if (messageObject == null || !filter.isHidden(TgAccess.getMessageObjectDialogId(messageObject))) {
                        visible.add(messageObject);
                    }
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
                    // Telegram stores these first thing in the method; secret chats have to resolve now
                    Object messagesController = filter.getMessagesController();
                    if (param.args[3] instanceof ArrayList) {
                        TgAccess.callWithTypes(messagesController, "MessagesController", "putUsers", new Class[]{ArrayList.class, boolean.class}, param.args[3], true);
                    }
                    if (param.args[4] instanceof ArrayList) {
                        TgAccess.callWithTypes(messagesController, "MessagesController", "putChats", new Class[]{ArrayList.class, boolean.class}, param.args[4], true);
                    }
                    if (param.args[5] instanceof ArrayList) {
                        TgAccess.callWithTypes(messagesController, "MessagesController", "putEncryptedChats", new Class[]{ArrayList.class, boolean.class}, param.args[5], true);
                    }
                    // notifications restored from the database at startup
                    Object dialogs = param.args[0];
                    if (dialogs != null) {
                        for (int i = TgAccess.intValue(TgAccess.call(dialogs, "LongSparseArray", "size"), 0) - 1; i >= 0; i--) {
                            long dialogId = TgAccess.longValue(TgAccess.call(dialogs, "LongSparseArray", "keyAt", i), 0);
                            if (filter.isHidden(dialogId)) TgAccess.call(dialogs, "LongSparseArray", "remove", dialogId);
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
     * Clears notifications that are already shown for chats that are hidden now, and refreshes the badge.
     */
    static void refresh(int account) {
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
