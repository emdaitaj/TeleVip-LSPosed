package com.my.televip.features.hideChats;

import android.os.Handler;
import android.os.Looper;

import com.my.televip.language.Keys;
import com.my.televip.language.Translator;
import com.my.televip.logging.Logger;
import com.my.televip.virtuals.SQLite.SQLiteCursor;
import com.my.televip.virtuals.SQLite.SQLiteDatabase;
import com.my.televip.virtuals.messenger.MessagesStorage;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XposedHelpers;

/**
 * Data for the chat picker: chats loaded in memory, contacts without a chat, then (asynchronously)
 * every chat cached in the local database that is not loaded yet.
 */
public class ChatPickerSource {

    public interface Callback {
        void onLoaded(ArrayList<ChatEntry> entries);
    }

    private static final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * UI thread. Chats first (list order, selected ones on top), then contacts sorted by name.
     */
    public static ArrayList<ChatEntry> loadInMemory(int account, Set<Long> selected) {
        TgAccess.init();
        ArrayList<ChatEntry> selectedChats = new ArrayList<>();
        ArrayList<ChatEntry> chats = new ArrayList<>();
        ArrayList<ChatEntry> contacts = new ArrayList<>();
        HashSet<Long> seen = new HashSet<>();
        Object messagesController = TgAccess.getMessagesController(account);
        long selfId = TgAccess.getClientUserId(account);

        ArrayList<Object> dialogs = TgAccess.getAllDialogs(messagesController);
        if (dialogs != null) {
            for (Object dialog : new ArrayList<>(dialogs)) {
                if (dialog == null || TgAccess.isDialogFolder(dialog)) continue;
                long dialogId = TgAccess.getDialogId(dialog);
                long key = TgAccess.toPeerKey(messagesController, dialogId);
                if (key == 0 || !seen.add(key)) continue;
                ChatEntry entry = build(messagesController, key, selfId, DialogIds.isEncryptedDialog(dialogId), TgAccess.getDialogFolderId(dialog) == 1, false);
                (selected.contains(key) ? selectedChats : chats).add(entry);
            }
        }

        for (Long key : selected) {
            if (key == null || !seen.add(key)) continue;
            selectedChats.add(build(messagesController, key, selfId, false, false, false));
        }

        ArrayList<Object> contactList = TgAccess.getList(TgAccess.getContactsController(account), "ContactsController", "contacts");
        if (contactList != null) {
            for (Object contact : new ArrayList<>(contactList)) {
                long userId = TgAccess.getContactUserId(contact);
                if (userId == 0 || !seen.add(userId)) continue;
                contacts.add(build(messagesController, userId, selfId, false, false, true));
            }
        }
        Collator collator = Collator.getInstance();
        Collections.sort(contacts, (a, b) -> collator.compare(a.title, b.title));

        ArrayList<ChatEntry> result = new ArrayList<>(selectedChats.size() + chats.size() + contacts.size());
        result.addAll(selectedChats);
        result.addAll(chats);
        result.addAll(contacts);
        return result;
    }

    /**
     * Chats stored in the local database but not loaded into memory yet. The callback runs on the
     * UI thread and is skipped when nothing new was found.
     */
    public static void loadCached(int account, Set<Long> existing, Callback callback) {
        Object storageObject = TgAccess.getMessagesStorage(account);
        if (storageObject == null) return;
        MessagesStorage storage = new MessagesStorage(storageObject);
        long selfId = TgAccess.getClientUserId(account);
        HashSet<Long> skip = new HashSet<>(existing);
        try {
            storage.getStorageQueue().postRunnable(() -> {
                LinkedHashMap<Long, Boolean> archivedByKey = new LinkedHashMap<>();
                HashSet<Long> secretKeys = new HashSet<>();
                ArrayList<Long> userIds = new ArrayList<>();
                StringBuilder chatIds = new StringBuilder();
                ArrayList<Object> users = new ArrayList<>();
                ArrayList<Object> chats = new ArrayList<>();
                try {
                    SQLiteDatabase database = storage.getDatabase();
                    HashMap<Integer, Long> encryptedChatUsers = HideChatsConfig.readEncryptedChatUsers(database);
                    SQLiteCursor cursor = database.queryFinalized("SELECT did, folder_id FROM dialogs ORDER BY date DESC", new Object[0]);
                    try {
                        while (cursor.next()) {
                            long dialogId = cursor.longValue(0);
                            if (dialogId == 0 || DialogIds.isFolderDialogId(dialogId)) continue;
                            long key = dialogId;
                            boolean secret = DialogIds.isEncryptedDialog(dialogId);
                            if (secret) {
                                Long userId = encryptedChatUsers.get(DialogIds.getEncryptedChatId(dialogId));
                                if (userId == null || userId == 0) continue;
                                key = userId;
                            }
                            if (skip.contains(key) || archivedByKey.containsKey(key)) continue;
                            archivedByKey.put(key, cursor.intValue(1) == 1);
                            if (secret) secretKeys.add(key);
                            if (key > 0) {
                                userIds.add(key);
                            } else {
                                if (chatIds.length() > 0) chatIds.append(',');
                                chatIds.append(-key);
                            }
                        }
                    } finally {
                        cursor.dispose();
                    }
                    if (!userIds.isEmpty()) {
                        XposedHelpers.callMethod(storageObject, "getUsersInternal", userIds, users);
                    }
                    if (chatIds.length() > 0) {
                        XposedHelpers.callMethod(storageObject, "getChatsInternal", chatIds.toString(), chats);
                    }
                } catch (Throwable t) {
                    Logger.e(t);
                }
                if (archivedByKey.isEmpty()) return;
                handler.post(() -> {
                    Object messagesController = TgAccess.getMessagesController(account);
                    if (!users.isEmpty()) {
                        TgAccess.call(messagesController, "MessagesController", "putUsers", users, true);
                    }
                    if (!chats.isEmpty()) {
                        TgAccess.call(messagesController, "MessagesController", "putChats", chats, true);
                    }
                    ArrayList<ChatEntry> entries = new ArrayList<>(archivedByKey.size());
                    for (Map.Entry<Long, Boolean> item : archivedByKey.entrySet()) {
                        long key = item.getKey();
                        entries.add(build(messagesController, key, selfId, secretKeys.contains(key), item.getValue(), false));
                    }
                    callback.onLoaded(entries);
                });
            });
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    private static ChatEntry build(Object messagesController, long key, long selfId, boolean secret, boolean archived, boolean contactOnly) {
        String title;
        String subtitle;
        String username = null;
        Object peer;
        boolean self = false;
        if (key > 0 && !DialogIds.isEncryptedDialog(key)) {
            peer = TgAccess.getUser(messagesController, key);
            self = key == selfId;
            if (peer == null) {
                title = Translator.get(Keys.HideChatsUnknownChat);
                subtitle = "";
            } else {
                username = (String) TgAccess.get(peer, "TLRPC$User", "username");
                if (self) {
                    title = Translator.get(Keys.HideChatsSavedMessages);
                } else if (TgAccess.boolValue(TgAccess.get(peer, "TLRPC$User", "deleted"), false)) {
                    title = Translator.get(Keys.HideChatsDeletedAccount);
                } else {
                    title = formatName((String) TgAccess.get(peer, "TLRPC$User", "first_name"), (String) TgAccess.get(peer, "TLRPC$User", "last_name"), username);
                }
                if (self) {
                    subtitle = "";
                } else if (TgAccess.boolValue(TgAccess.get(peer, "TLRPC$User", "bot"), false)) {
                    subtitle = Translator.get(Keys.HideChatsTypeBot);
                } else if (TgAccess.boolValue(TgAccess.get(peer, "TLRPC$User", "contact"), false)) {
                    subtitle = Translator.get(Keys.HideChatsTypeContact);
                } else {
                    subtitle = Translator.get(Keys.HideChatsTypePrivate);
                }
            }
            if (secret) {
                subtitle = join(subtitle, Translator.get(Keys.HideChatsTypeSecret));
            }
        } else if (key < 0) {
            peer = TgAccess.getChat(messagesController, -key);
            if (peer == null) {
                title = Translator.get(Keys.HideChatsUnknownChat);
                subtitle = "";
            } else {
                username = (String) TgAccess.get(peer, "TLRPC$Chat", "username");
                String chatTitle = (String) TgAccess.get(peer, "TLRPC$Chat", "title");
                title = chatTitle == null || chatTitle.trim().isEmpty() ? Translator.get(Keys.HideChatsUnknownChat) : chatTitle.replace('\n', ' ');
                boolean channel = TgAccess.boolValue(TgAccess.get(peer, "TLRPC$Chat", "broadcast"), false)
                        && !TgAccess.boolValue(TgAccess.get(peer, "TLRPC$Chat", "megagroup"), false);
                subtitle = Translator.get(channel ? Keys.HideChatsTypeChannel : Keys.HideChatsTypeGroup);
            }
        } else {
            peer = null;
            title = Translator.get(Keys.HideChatsUnknownChat);
            subtitle = Translator.get(Keys.HideChatsTypeSecret);
        }
        if (archived) {
            subtitle = join(subtitle, Translator.get(Keys.HideChatsTypeArchived));
        }
        return new ChatEntry(key, peer, title, subtitle, username, self, contactOnly);
    }

    private static String formatName(String firstName, String lastName, String username) {
        StringBuilder builder = new StringBuilder();
        if (firstName != null) builder.append(firstName.trim());
        if (lastName != null && !lastName.trim().isEmpty()) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(lastName.trim());
        }
        if (builder.length() == 0 && username != null && !username.isEmpty()) builder.append('@').append(username);
        if (builder.length() == 0) return Translator.get(Keys.HideChatsUnknownChat);
        return builder.toString().replace('\n', ' ');
    }

    private static String join(String first, String second) {
        if (first == null || first.isEmpty()) return second;
        return first + " · " + second;
    }
}
