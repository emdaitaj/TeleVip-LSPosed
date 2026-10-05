package com.my.televip.features.hideChats;

/**
 * Same bit layout as org.telegram.messenger.DialogObject, kept local so hot paths avoid reflection.
 */
final class DialogIds {

    private DialogIds() {}

    static boolean isEncryptedDialog(long dialogId) {
        return (dialogId & 0x4000000000000000L) != 0 && (dialogId & 0x8000000000000000L) == 0;
    }

    static boolean isFolderDialogId(long dialogId) {
        return (dialogId & 0x2000000000000000L) != 0 && (dialogId & 0x8000000000000000L) == 0;
    }

    static boolean isUserDialog(long dialogId) {
        return !isEncryptedDialog(dialogId) && !isFolderDialogId(dialogId) && dialogId > 0;
    }

    static boolean isChatDialog(long dialogId) {
        return !isEncryptedDialog(dialogId) && !isFolderDialogId(dialogId) && dialogId < 0;
    }

    static int getEncryptedChatId(long dialogId) {
        return (int) (dialogId & 0x00000000ffffffffL);
    }

    static long makeEncryptedDialogId(long chatId) {
        return 0x4000000000000000L | (chatId & 0x00000000ffffffffL);
    }
}
