package com.my.televip.features.hideChats;

import com.my.televip.Class.ClassNames;
import com.my.televip.base.BaseMethodHook;

import java.util.ArrayList;
import java.util.List;

/**
 * Backstop for everything that can still reach a hidden chat once it is out of the lists:
 * opening it (from contacts, a shared contact, a mention, a deep link, a shortcut, a forwarded
 * message) and its messages forwarded into chats that are not hidden.
 */
final class ChatGuardHooks {

    private ChatGuardHooks() {}

    static void install() {
        // Returning false from onFragmentCreate is how Telegram itself refuses to open a chat, so the
        // fragment is never presented no matter which screen asked for it.
        HideChats.hook(TgAccess.findClass(ClassNames.CHAT_ACTIVITY), "ChatActivity", "onFragmentCreate", new BaseMethodHook() {
            @Override
            protected void afterMethod(MethodHookParam param) {
                if (!Boolean.TRUE.equals(param.getResult())) return;
                HiddenFilter filter = HideChatsConfig.filterFor(fragmentAccount(param.thisObject));
                if (filter == null) return;
                long dialogId = TgAccess.longValue(TgAccess.get(param.thisObject, "ChatActivity", "dialog_id"), 0);
                if (dialogId != 0 && filter.isHidden(dialogId)) {
                    param.setResult(false);
                }
            }
        });

        // Messages forwarded out of a hidden chat: drop them wherever a chat loads or receives messages.
        int messagesDidLoad = TgAccess.getNotificationId("messagesDidLoad");
        int didReceivedNewMessages = TgAccess.getNotificationId("didReceiveNewMessages");
        int replaceMessagesObjects = TgAccess.getNotificationId("replaceMessagesObjects");
        HideChats.hook(TgAccess.notificationCenterClass, "NotificationCenter", "postNotificationName", int.class, Object[].class, new BaseMethodHook() {
            @Override
            protected void beforeMethod(MethodHookParam param) {
                int id = (int) param.args[0];
                if (id != messagesDidLoad && id != didReceivedNewMessages && id != replaceMessagesObjects) return;
                if (!(param.args[1] instanceof Object[])) return;
                HiddenFilter filter = HideChatsConfig.filterFor(TgAccess.getNotificationCenterAccount(param.thisObject));
                if (filter == null) return;
                for (Object arg : (Object[]) param.args[1]) {
                    // the one argument that holds message objects, identified by its contents
                    if (arg instanceof ArrayList && isMessageObjectList((ArrayList<?>) arg)) {
                        //noinspection unchecked
                        removeForwardedFromHidden(filter, (List<Object>) arg);
                        return;
                    }
                }
            }
        });
    }

    private static boolean isMessageObjectList(ArrayList<?> list) {
        if (list.isEmpty()) return false;
        Class<?> messageObject = TgAccess.findClass(ClassNames.MESSAGE_OBJECT);
        return messageObject != null && messageObject.isInstance(list.get(0));
    }

    private static int fragmentAccount(Object fragment) {
        return TgAccess.intValue(TgAccess.get(fragment, "BaseFragment", "currentAccount"), TgAccess.getSelectedAccount());
    }

    /**
     * Keeps anything that is not a message object, so lists of other things pass through untouched.
     */
    private static void removeForwardedFromHidden(HiddenFilter filter, List<Object> messageObjects) {
        if (messageObjects.isEmpty()) return;
        try {
            messageObjects.removeIf(item -> isForwardedFromHidden(filter, item));
        } catch (Throwable ignored) {
            // immutable list: nothing to filter here
        }
    }

    private static boolean isForwardedFromHidden(HiddenFilter filter, Object messageObject) {
        Object message = TgAccess.get(messageObject, "MessageObject", "messageOwner");
        if (message == null) return false;
        Object forward = TgAccess.get(message, "TLRPC$Message", "fwd_from");
        if (forward == null) return false;
        return isHiddenPeer(filter, TgAccess.get(forward, "TLRPC$MessageFwdHeader", "from_id"))
                || isHiddenPeer(filter, TgAccess.get(forward, "TLRPC$MessageFwdHeader", "saved_from_peer"));
    }

    private static boolean isHiddenPeer(HiddenFilter filter, Object peer) {
        if (peer == null) return false;
        long dialogId = TgAccess.getPeerDialogId(peer);
        return dialogId != 0 && filter.isHidden(dialogId);
    }
}
