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
    final int activatedAt;
    private final int mode;
    private final boolean hideNewChats;
    private final Set<Long> selected;
    private Object messagesController;
    private HashMap<Integer, Long> encryptedChatUsers;

    HiddenFilter(int account, HideChatsConfig.AccountData data, int mode, boolean hideNewChats, int activatedAt) {
        this.account = account;
        this.data = data;
        this.mode = mode;
        this.hideNewChats = hideNewChats;
        this.selected = data.selected;
        this.activatedAt = activatedAt;
    }

    boolean isHidden(long dialogId) {
        long key = toPeerKey(dialogId);
        if (key == 0) return false;
        // a secret chat whose user could not be found: only its own selection can be checked
        if (DialogIds.isEncryptedDialog(key)) {
            boolean isSelected = selected.contains(key);
            return mode == HideChatsConfig.MODE_SHOW_ONLY_SELECTED ? !isSelected : isSelected;
        }
        return isHiddenKey(key);
    }

    /**
     * A pending chat (see ChatClassifier) is hidden whenever one of its possible classifications
     * would be: until it is known to be new, it may just as well be an old chat.
     */
    boolean isHiddenKey(long key) {
        if (mode == HideChatsConfig.MODE_SHOW_ONLY_SELECTED) {
            if (selected.contains(key)) return false;
            if (data.known.contains(key) || data.dead.contains(key)) return true;
            if (data.newChats.contains(key)) return hideNewChats;
            return data.pending.contains(key);
        }
        if (selected.contains(key)) return true;
        if (data.newChats.contains(key)) return hideNewChats;
        return hideNewChats && data.pending.contains(key);
    }

    boolean isPending(long dialogId) {
        long key = toPeerKey(dialogId);
        return key != 0 && data.pending.contains(key);
    }

    Object getMessagesController() {
        if (messagesController == null) messagesController = TgAccess.getMessagesController(account);
        return messagesController;
    }

    /**
     * Secret chats follow the user they belong to, folder entries are never hidden (returns 0).
     */
    long toPeerKey(long dialogId) {
        if (dialogId == 0 || DialogIds.isFolderDialogId(dialogId)) return 0;
        if (!DialogIds.isEncryptedDialog(dialogId)) return dialogId;
        int chatId = DialogIds.getEncryptedChatId(dialogId);
        Long cached = encryptedChatUsers != null ? encryptedChatUsers.get(chatId) : null;
        if (cached == null) {
            long userId = TgAccess.resolveEncryptedChatUserId(account, getMessagesController(), chatId);
            cached = userId != 0 ? userId : dialogId;
            if (encryptedChatUsers == null) encryptedChatUsers = new HashMap<>();
            encryptedChatUsers.put(chatId, cached);
        }
        return cached;
    }

    /**
     * Classifies a chat seen for the first time since activation (see ChatClassifier), given a date it
     * was active at (0 when unknown). Thread-safe. Returns true when the snapshot changed right away;
     * the caller persists it.
     */
    boolean observeDialog(long dialogId, int lastActivityDate) {
        return observeDialog(dialogId, lastActivityDate, null);
    }

    /**
     * @param chat the TLRPC.Chat of a group / channel when the caller has it (e.g. from a response
     *             that is not stored yet), otherwise null
     */
    boolean observeDialog(long dialogId, int lastActivityDate, Object chat) {
        long key = toPeerKey(dialogId);
        if (key == 0 || isClassified(key) || data.pending.contains(key)) return false;
        return ChatClassifier.classify(this, key, lastActivityDate, chat);
    }

    /**
     * A contact that is not in the snapshot was added after it was taken (before the account has a
     * snapshot, every contact simply exists).
     */
    boolean observeContact(long userId) {
        if (userId == 0 || data.snapshotPending || isClassified(userId) || data.pending.contains(userId)) return false;
        return ChatClassifier.store(data, userId, activatedAt > 0);
    }

    private boolean isClassified(long key) {
        return data.known.contains(key) || data.newChats.contains(key);
    }
}
