package com.my.televip.features.hideChats;

import java.util.HashMap;
import java.util.Set;

/**
 * Immutable view of the hide rules for one account, created per hook invocation.
 * Not thread-safe: use one instance per thread / pass.
 */
final class HiddenFilter {

    final int account;
    final HideChatsConfig.AccountData data;
    private final Set<Long> hidden;
    private Object messagesController;
    private HashMap<Integer, Long> encryptedChatUsers;

    HiddenFilter(int account, HideChatsConfig.AccountData data, Set<Long> hidden) {
        this.account = account;
        this.data = data;
        this.hidden = hidden;
    }

    /**
     * Only chats the user put on the managed list and did not tick as visible are hidden;
     * everything else is left alone.
     */
    boolean isHidden(long dialogId) {
        long key = toPeerKey(dialogId);
        return key != 0 && hidden.contains(key);
    }

    boolean isHiddenKey(long key) {
        return key != 0 && hidden.contains(key);
    }

    Object getMessagesController() {
        if (messagesController == null) messagesController = TgAccess.getMessagesController(account);
        return messagesController;
    }

    /**
     * Secret chats follow the user they belong to, folder entries are never hidden (returns 0).
     * A chat whose user cannot be resolved yet keeps its own id, and is not cached under it: the
     * encrypted chat usually loads a moment later, and until then it is not hidden (only chats on
     * the managed list ever are).
     */
    long toPeerKey(long dialogId) {
        if (dialogId == 0 || DialogIds.isFolderDialogId(dialogId)) return 0;
        if (!DialogIds.isEncryptedDialog(dialogId)) return dialogId;
        int chatId = DialogIds.getEncryptedChatId(dialogId);
        Long cached = encryptedChatUsers != null ? encryptedChatUsers.get(chatId) : null;
        if (cached == null) {
            long userId = TgAccess.resolveEncryptedChatUserId(account, getMessagesController(), chatId);
            // not resolvable yet: do not cache, the chat may load in a moment
            if (userId == 0) return dialogId;
            cached = userId;
            if (encryptedChatUsers == null) encryptedChatUsers = new HashMap<>();
            encryptedChatUsers.put(chatId, cached);
        }
        return cached;
    }
}
