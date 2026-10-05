package com.my.televip.features.hideChats;

import android.os.Looper;
import android.util.SparseArray;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.Obfuscate;
import com.my.televip.utils.Utils;
import com.my.televip.virtuals.SQLite.SQLiteCursor;
import com.my.televip.virtuals.messenger.MessagesStorage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedHelpers;

/**
 * Null-safe reflection helpers for the Telegram internals the hide chats feature touches.
 * Field handles used inside per-dialog loops are cached so hooks stay cheap on large chat lists.
 */
final class TgAccess {

    private static final Map<String, Field> fieldCache = new ConcurrentHashMap<>();
    private static final Set<String> missingFields = ConcurrentHashMap.newKeySet();

    // Resolved once on the UI thread: ClassLoad's cache is not safe for concurrent first lookups,
    // and several hooks run on network / search / storage threads.
    static volatile Class<?> userClass;
    static volatile Class<?> chatClass;
    static volatile Class<?> encryptedChatClass;
    static volatile Class<?> dialogClass;
    static volatile Class<?> dialogFolderClass;
    static volatile Class<?> userConfigClass;
    static volatile Class<?> notificationCenterClass;
    static volatile Class<?> messagesControllerClass;
    static volatile Class<?> contactsControllerClass;
    static volatile Class<?> mediaDataControllerClass;
    static volatile Class<?> messagesStorageClass;
    static volatile Class<?> notificationsControllerClass;
    static volatile Class<?> connectionsManagerClass;
    static volatile Class<?> messageObjectClass;
    static volatile Class<?> messageClass;
    static volatile Class<?> sharedConfigClass;
    static volatile Class<?> chatObjectClass;
    static volatile Class<?> tlObjectClass;
    static volatile Class<?> requestDelegateClass;
    static volatile Class<?> longSparseIntArrayClass;
    static volatile Class<?> getHistoryClass;
    static volatile Class<?> inputPeerSelfClass;
    static volatile Class<?> inputPeerUserClass;
    static volatile Class<?> inputPeerChatClass;
    static volatile Class<?> inputPeerChannelClass;
    private static volatile boolean initialized;

    private static volatile Field dialogIdField;
    private static volatile Field dialogFolderIdField;
    private static volatile Field dialogLastMessageDateField;
    private static volatile Field dialogUnreadCountField;
    private static volatile Field dialogUnreadMarkField;
    private static volatile Field dialogPinnedField;
    private static volatile Field dialogPinnedNumField;
    private static volatile Field contactUserIdField;

    private TgAccess() {}

    static synchronized void init() {
        if (initialized) return;
        userClass = findClass(ClassNames.TLRPC_USER);
        chatClass = findClass(ClassNames.TLRPC_CHAT);
        encryptedChatClass = findClass(ClassNames.TLRPC_ENCRYPTED_CHAT);
        dialogClass = findClass(ClassNames.TLRPC_DIALOG);
        dialogFolderClass = findClass(ClassNames.TLRPC_DIALOG_FOLDER);
        userConfigClass = findClass(ClassNames.USER_CONFIG);
        notificationCenterClass = findClass(ClassNames.NOTIFICATION_CENTER);
        messagesControllerClass = findClass(ClassNames.MESSAGES_CONTROLLER);
        contactsControllerClass = findClass(ClassNames.CONTACTS_CONTROLLER);
        mediaDataControllerClass = findClass(ClassNames.MEDIA_DATA_CONTROLLER);
        messagesStorageClass = findClass(ClassNames.MESSAGES_STORAGE);
        notificationsControllerClass = findClass(ClassNames.NOTIFICATIONS_CONTROLLER);
        connectionsManagerClass = findClass(ClassNames.CONNECTIONS_MANAGER);
        messageObjectClass = findClass(ClassNames.MESSAGE_OBJECT);
        messageClass = findClass(ClassNames.MESSAGE);
        sharedConfigClass = findClass(ClassNames.SHARED_CONFIG);
        chatObjectClass = findClass(ClassNames.CHAT_OBJECT);
        tlObjectClass = findClass(ClassNames.TL_OBJECT);
        requestDelegateClass = findClass(ClassNames.REQUEST_DELEGATE);
        longSparseIntArrayClass = findClass(ClassNames.LONG_SPARSE_INT_ARRAY);
        getHistoryClass = findClass(ClassNames.TL_MESSAGES_GET_HISTORY);
        inputPeerSelfClass = findClass(ClassNames.TL_INPUT_PEER_SELF);
        inputPeerUserClass = findClass(ClassNames.TL_INPUT_PEER_USER);
        inputPeerChatClass = findClass(ClassNames.TL_INPUT_PEER_CHAT);
        inputPeerChannelClass = findClass(ClassNames.TL_INPUT_PEER_CHANNEL);
        initialized = true;
    }

    static Class<?> findClass(String name) {
        return ClassLoad.getClass(name, Utils.classLoader, false);
    }

    static Field field(Class<?> cls, String classKey, String name) {
        if (cls == null) return null;
        String resolved = Obfuscate.getFieldName(classKey, name);
        String cacheKey = cls.getName() + '#' + resolved;
        Field cached = fieldCache.get(cacheKey);
        if (cached != null) return cached;
        if (missingFields.contains(cacheKey)) return null;
        Field found = null;
        try {
            found = XposedHelpers.findFieldIfExists(cls, resolved);
        } catch (Throwable ignored) {}
        if (found == null) {
            missingFields.add(cacheKey);
        } else {
            fieldCache.put(cacheKey, found);
        }
        return found;
    }

    static Object get(Object obj, String classKey, String name) {
        if (obj == null) return null;
        Field f = field(obj.getClass(), classKey, name);
        if (f == null) return null;
        try {
            return f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean set(Object obj, String classKey, String name, Object value) {
        if (obj == null) return false;
        Field f = field(obj.getClass(), classKey, name);
        if (f == null) return false;
        try {
            f.set(obj, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static Object getStatic(Class<?> cls, String classKey, String name) {
        Field f = field(cls, classKey, name);
        if (f == null) return null;
        try {
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static ArrayList<Object> getList(Object obj, String classKey, String name) {
        Object value = get(obj, classKey, name);
        return value instanceof ArrayList ? (ArrayList<Object>) value : null;
    }

    static Object call(Object obj, String classKey, String method, Object... args) {
        if (obj == null) return null;
        try {
            return XposedHelpers.callMethod(obj, Obfuscate.getMethodName(classKey, method), args);
        } catch (Throwable t) {
            Logger.w("HideChats: call " + classKey + "#" + method + " failed: " + t);
            return null;
        }
    }

    static Object callWithTypes(Object obj, String classKey, String method, Class<?>[] parameterTypes, Object... args) {
        if (obj == null) return null;
        try {
            return XposedHelpers.callMethod(obj, Obfuscate.getMethodName(classKey, method), parameterTypes, args);
        } catch (Throwable t) {
            Logger.w("HideChats: call " + classKey + "#" + method + " failed: " + t);
            return null;
        }
    }

    static Object callStatic(Class<?> cls, String classKey, String method, Object... args) {
        if (cls == null) return null;
        try {
            return XposedHelpers.callStaticMethod(cls, Obfuscate.getMethodName(classKey, method), args);
        } catch (Throwable t) {
            Logger.w("HideChats: call " + classKey + "#" + method + " failed: " + t);
            return null;
        }
    }

    static Object newInstance(Class<?> cls) {
        if (cls == null) return null;
        try {
            return XposedHelpers.newInstance(cls);
        } catch (Throwable t) {
            Logger.w("HideChats: unable to create " + cls.getName() + ": " + t);
            return null;
        }
    }

    static int intValue(Object value, int def) {
        return value instanceof Integer ? (Integer) value : def;
    }

    static long longValue(Object value, long def) {
        return value instanceof Long ? (Long) value : def;
    }

    static boolean boolValue(Object value, boolean def) {
        return value instanceof Boolean ? (Boolean) value : def;
    }

    // Accounts and controllers

    static int getAccount(Object controller) {
        return intValue(get(controller, "BaseController", "currentAccount"), -1);
    }

    static int getNotificationCenterAccount(Object notificationCenter) {
        return intValue(get(notificationCenter, "NotificationCenter", "currentAccount"), -1);
    }

    static int getSelectedAccount() {
        return intValue(getStatic(userConfigClass, "UserConfig", "selectedAccount"), 0);
    }

    static int getMaxAccountCount() {
        int count = intValue(getStatic(userConfigClass, "UserConfig", "MAX_ACCOUNT_COUNT"), 0);
        return count > 0 ? count : 4;
    }

    static Object getUserConfig(int account) {
        return callStatic(userConfigClass, "UserConfig", "getInstance", account);
    }

    static long getClientUserId(int account) {
        Object userConfig = getUserConfig(account);
        if (userConfig == null) return 0;
        if (!boolValue(call(userConfig, "UserConfig", "isClientActivated"), false)) return 0;
        return longValue(get(userConfig, "UserConfig", "clientUserId"), 0);
    }

    static Object getMessagesController(int account) {
        return callStatic(messagesControllerClass, "MessagesController", "getInstance", account);
    }

    static Object getContactsController(int account) {
        return callStatic(contactsControllerClass, "ContactsController", "getInstance", account);
    }

    static Object getMediaDataController(int account) {
        return callStatic(mediaDataControllerClass, "MediaDataController", "getInstance", account);
    }

    static Object getMessagesStorage(int account) {
        return callStatic(messagesStorageClass, "MessagesStorage", "getInstance", account);
    }

    static Object getNotificationsController(int account) {
        return callStatic(notificationsControllerClass, "NotificationsController", "getInstance", account);
    }

    static Object getNotificationCenter(int account) {
        return callStatic(notificationCenterClass, "NotificationCenter", "getInstance", account);
    }

    static Object getConnectionsManager(int account) {
        return callStatic(connectionsManagerClass, "ConnectionsManager", "getInstance", account);
    }

    static int getServerTime(int account) {
        int time = intValue(call(getConnectionsManager(account), "ConnectionsManager", "getCurrentTime"), 0);
        return time > 0 ? time : (int) (System.currentTimeMillis() / 1000);
    }

    interface ResponseCallback {
        void run(Object response, Object error);
    }

    /**
     * Sends a request on the account's connection. The callback runs on the network thread.
     */
    static boolean sendRequest(int account, Object request, ResponseCallback callback) {
        Class<?> delegateClass = requestDelegateClass;
        Class<?> requestClass = tlObjectClass;
        Object connectionsManager = getConnectionsManager(account);
        if (request == null || delegateClass == null || requestClass == null || connectionsManager == null) return false;
        Object delegate = Proxy.newProxyInstance(delegateClass.getClassLoader(), new Class[]{delegateClass}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return proxyObjectMethod(proxy, method, args);
            if (args != null && args.length == 2) {
                try {
                    callback.run(args[0], args[1]);
                } catch (Throwable t) {
                    Logger.e(t);
                }
            }
            return null;
        });
        return callWithTypes(connectionsManager, "ConnectionsManager", "sendRequest", new Class[]{requestClass, delegateClass}, request, delegate) != null;
    }

    /**
     * equals / hashCode / toString of a proxy that has no object to forward them to.
     */
    static Object proxyObjectMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals":
                return args != null && args.length == 1 && args[0] == proxy;
            case "hashCode":
                return System.identityHashCode(proxy);
            default:
                return "TeleVipHideChatsDelegate";
        }
    }

    static int getNotificationId(String name) {
        return intValue(getStatic(notificationCenterClass, "NotificationCenter", name), -1);
    }

    static void postNotification(int account, String name, Object... args) {
        int id = getNotificationId(name);
        Object center = getNotificationCenter(account);
        if (id < 0 || center == null) return;
        try {
            XposedHelpers.callMethod(center, Obfuscate.getMethodName("NotificationCenter", "postNotificationName"), id, args);
        } catch (Throwable t) {
            Logger.w("HideChats: post " + name + " failed: " + t);
        }
    }

    // Dialogs

    private static Class<?> dialogClass() {
        return dialogClass;
    }

    static boolean isDialogFolder(Object dialog) {
        Class<?> cls = dialogFolderClass;
        return cls != null && cls.isInstance(dialog);
    }

    static long getDialogId(Object dialog) {
        if (dialog == null) return 0;
        Field f = dialogIdField;
        if (f == null) {
            f = field(dialogClass(), "TLRPC$Dialog", "id");
            if (f == null) return 0;
            dialogIdField = f;
        }
        try {
            return f.getLong(dialog);
        } catch (Throwable t) {
            return 0;
        }
    }

    static int getDialogFolderId(Object dialog) {
        Field f = dialogFolderIdField;
        if (f == null) {
            f = field(dialogClass(), "TLRPC$Dialog", "folder_id");
            if (f == null) return 0;
            dialogFolderIdField = f;
        }
        try {
            return f.getInt(dialog);
        } catch (Throwable t) {
            return 0;
        }
    }

    static int getDialogLastMessageDate(Object dialog) {
        Field f = dialogLastMessageDateField;
        if (f == null) {
            f = field(dialogClass(), "TLRPC$Dialog", "last_message_date");
            if (f == null) return 0;
            dialogLastMessageDateField = f;
        }
        try {
            return f.getInt(dialog);
        } catch (Throwable t) {
            return 0;
        }
    }

    static int getDialogUnreadCount(Object dialog) {
        Field f = dialogUnreadCountField;
        if (f == null) {
            f = field(dialogClass(), "TLRPC$Dialog", "unread_count");
            if (f == null) return 0;
            dialogUnreadCountField = f;
        }
        try {
            return f.getInt(dialog);
        } catch (Throwable t) {
            return 0;
        }
    }

    static boolean getDialogUnreadMark(Object dialog) {
        Field f = dialogUnreadMarkField;
        if (f == null) {
            f = field(dialogClass(), "TLRPC$Dialog", "unread_mark");
            if (f == null) return false;
            dialogUnreadMarkField = f;
        }
        try {
            return f.getBoolean(dialog);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isDialogPinned(Object dialog) {
        Field f = dialogPinnedField;
        if (f == null) {
            f = field(dialogClass(), "TLRPC$Dialog", "pinned");
            if (f == null) return false;
            dialogPinnedField = f;
        }
        try {
            return f.getBoolean(dialog);
        } catch (Throwable t) {
            return false;
        }
    }

    static int getDialogPinnedNum(Object dialog) {
        Field f = dialogPinnedNumField;
        if (f == null) {
            f = field(dialogClass(), "TLRPC$Dialog", "pinnedNum");
            if (f == null) return 0;
            dialogPinnedNumField = f;
        }
        try {
            return f.getInt(dialog);
        } catch (Throwable t) {
            return 0;
        }
    }

    static int getFolderEntryId(Object dialogFolder) {
        Object folder = get(dialogFolder, "TLRPC$TL_dialogFolder", "folder");
        return intValue(get(folder, "TLRPC$TL_folder", "id"), -1);
    }

    static ArrayList<Object> getAllDialogs(Object messagesController) {
        return getList(messagesController, "MessagesController", "allDialogs");
    }

    @SuppressWarnings("unchecked")
    static SparseArray<ArrayList<Object>> getDialogsByFolder(Object messagesController) {
        Object value = get(messagesController, "MessagesController", "dialogsByFolder");
        return value instanceof SparseArray ? (SparseArray<ArrayList<Object>>) value : null;
    }

    static Object getDialogsDict(Object messagesController) {
        return get(messagesController, "MessagesController", "dialogs_dict");
    }

    // Peers

    static Object getUser(Object messagesController, long userId) {
        return call(messagesController, "MessagesController", "getUser", userId);
    }

    static Object getChat(Object messagesController, long chatId) {
        return call(messagesController, "MessagesController", "getChat", chatId);
    }

    /**
     * Channels / supergroups: when we joined. Basic groups: when the group was created.
     */
    static int getChatDate(Object chat) {
        return intValue(get(chat, "TLRPC$Chat", "date"), 0);
    }

    static boolean isChannel(Object chat) {
        if (chat == null) return false;
        Object result = callStatic(chatObjectClass, "ChatObject", "isChannel", chat);
        if (result instanceof Boolean) return (Boolean) result;
        return boolValue(get(chat, "TLRPC$Chat", "broadcast"), false) || boolValue(get(chat, "TLRPC$Chat", "megagroup"), false);
    }

    static Object getInputPeer(Object messagesController, long dialogId) {
        return callWithTypes(messagesController, "MessagesController", "getInputPeer", new Class[]{long.class}, dialogId);
    }

    /**
     * User of a secret chat. When it is not in memory it is read from the database, except on the UI
     * thread, which must not wait for the storage queue. Returns 0 when unknown.
     */
    static long resolveEncryptedChatUserId(int account, Object messagesController, int encryptedChatId) {
        long userId = getEncryptedChatUserId(messagesController, encryptedChatId);
        if (userId != 0 || Looper.myLooper() == Looper.getMainLooper()) return userId;
        Object storage = getMessagesStorage(account);
        if (storage == null) return 0;
        if (Thread.currentThread() == get(storage, "MessagesStorage", "storageQueue")) {
            // inside a storage task: waiting for another one would deadlock, so query directly
            try {
                SQLiteCursor cursor = new MessagesStorage(storage).getDatabase()
                        .queryFinalized("SELECT user FROM enc_chats WHERE uid = " + encryptedChatId, new Object[0]);
                try {
                    if (cursor.next()) userId = cursor.longValue(0);
                } finally {
                    cursor.dispose();
                }
            } catch (Throwable t) {
                Logger.e(t);
            }
            return userId;
        }
        // loads it from the database through the storage queue (and keeps it in memory), as Telegram does
        Object chat = callWithTypes(messagesController, "MessagesController", "getEncryptedChatDB", new Class[]{int.class, boolean.class}, encryptedChatId, false);
        return longValue(get(chat, "TLRPC$EncryptedChat", "user_id"), 0);
    }

    static long getEncryptedChatUserId(Object messagesController, int encryptedChatId) {
        Object encryptedChat = call(messagesController, "MessagesController", "getEncryptedChat", encryptedChatId);
        return longValue(get(encryptedChat, "TLRPC$EncryptedChat", "user_id"), 0);
    }

    /**
     * Secret chats map to their user, folder entries to 0, everything else stays as is.
     */
    static long toPeerKey(Object messagesController, long dialogId) {
        if (dialogId == 0 || DialogIds.isFolderDialogId(dialogId)) return 0;
        if (!DialogIds.isEncryptedDialog(dialogId)) return dialogId;
        long userId = getEncryptedChatUserId(messagesController, DialogIds.getEncryptedChatId(dialogId));
        return userId != 0 ? userId : dialogId;
    }

    static long getPeerDialogId(Object peer) {
        if (peer == null) return 0;
        long userId = longValue(get(peer, "TLRPC$Peer", "user_id"), 0);
        if (userId != 0) return userId;
        long chatId = longValue(get(peer, "TLRPC$Peer", "chat_id"), 0);
        if (chatId != 0) return -chatId;
        long channelId = longValue(get(peer, "TLRPC$Peer", "channel_id"), 0);
        return channelId != 0 ? -channelId : 0;
    }

    /**
     * Dialog id of a search result / recent search object (TLRPC.User, TLRPC.Chat, TLRPC.EncryptedChat).
     * Returns 0 for anything else.
     */
    static long getObjectDialogId(Object object) {
        if (object == null) return 0;
        if (userClass != null && userClass.isInstance(object)) {
            return longValue(get(object, "TLRPC$User", "id"), 0);
        }
        if (chatClass != null && chatClass.isInstance(object)) {
            long id = longValue(get(object, "TLRPC$Chat", "id"), 0);
            return id != 0 ? -id : 0;
        }
        if (encryptedChatClass != null && encryptedChatClass.isInstance(object)) {
            int id = intValue(get(object, "TLRPC$EncryptedChat", "id"), 0);
            return id != 0 ? DialogIds.makeEncryptedDialogId(id) : 0;
        }
        return 0;
    }

    static long getContactUserId(Object contact) {
        if (contact == null) return 0;
        Field f = contactUserIdField;
        if (f == null) {
            f = field(contact.getClass(), "TLRPC$TL_contact", "user_id");
            if (f == null) return 0;
            contactUserIdField = f;
        }
        try {
            return f.getLong(contact);
        } catch (Throwable t) {
            return 0;
        }
    }

    static long getMessageDialogId(Object message) {
        Object result = callStatic(messageObjectClass, "MessageObject", "getDialogId", message);
        return longValue(result, 0);
    }

    static long getMessageObjectDialogId(Object messageObject) {
        return longValue(call(messageObject, "MessageObject", "getDialogId"), 0);
    }

    /**
     * new MessageObject(account, message, false, false), as Telegram builds restored notifications.
     */
    static Object newMessageObject(int account, Object message) {
        if (messageObjectClass == null || messageClass == null || message == null) return null;
        try {
            return messageObjectClass.getConstructor(int.class, messageClass, boolean.class, boolean.class)
                    .newInstance(account, message, false, false);
        } catch (Throwable t) {
            Logger.w("HideChats: unable to create a MessageObject: " + t);
            return null;
        }
    }

    static int getMessageObjectDate(Object messageObject) {
        return getMessageDate(get(messageObject, "MessageObject", "messageOwner"));
    }

    static int getMessageDate(Object message) {
        return intValue(get(message, "TLRPC$Message", "date"), 0);
    }

    static int getMessageId(Object message) {
        return intValue(get(message, "TLRPC$Message", "id"), 0);
    }

    /**
     * Chat the message was sent in (peer_id), unlike getMessageDialogId never a secret chat.
     */
    static long getMessagePeerDialogId(Object message) {
        return getPeerDialogId(get(message, "TLRPC$Message", "peer_id"));
    }

    // org.telegram.messenger.support.LongSparseIntArray

    static Object newLongSparseIntArray(Map<Long, Integer> values) {
        Object array = newInstance(longSparseIntArrayClass);
        if (array == null) return null;
        for (Map.Entry<Long, Integer> entry : values.entrySet()) {
            callWithTypes(array, "LongSparseIntArray", "put", new Class[]{long.class, int.class}, entry.getKey(), entry.getValue());
        }
        return array;
    }
}
