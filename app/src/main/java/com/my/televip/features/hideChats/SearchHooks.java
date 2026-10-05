package com.my.televip.features.hideChats;

import com.my.televip.Class.ClassNames;
import com.my.televip.base.BaseMethodHook;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.ArgsResolver;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;

import de.robv.android.xposed.XposedHelpers;

/**
 * Search: recent searches (history), top peers (hints + launcher/direct share shortcuts),
 * local results, contact/username search, global message search and the calls log.
 */
final class SearchHooks {

    private static final String KEY_HINTS_FILTERED = "televipHideChatsHintsFiltered";

    private SearchHooks() {}

    static void install() {
        Class<?> messagesStorage = TgAccess.findClass(ClassNames.MESSAGES_STORAGE);
        HideChats.hook(messagesStorage, "MessagesStorage", "localSearch", int.class, String.class, ArrayList.class, ArrayList.class, ArrayList.class, ArrayList.class, int.class, new BaseMethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void afterMethod(MethodHookParam param) {
                HiddenFilter filter = HideChatsConfig.filterFor(TgAccess.getAccount(param.thisObject));
                if (filter == null) return;
                removeHiddenResults(filter, (ArrayList<Object>) param.args[2], (ArrayList<Object>) param.args[3]);
                ArrayList<Object> encUsers = (ArrayList<Object>) param.args[4];
                if (encUsers != null) {
                    encUsers.removeIf(user -> filter.isHidden(TgAccess.getObjectDialogId(user)));
                }
            }
        });

        Class<?> dialogsSearchAdapter = TgAccess.findClass(ClassNames.DIALOGS_SEARCH_ADAPTER);
        HideChats.hook(dialogsSearchAdapter, "DialogsSearchAdapter", "updateSearchResults", ArrayList.class, ArrayList.class, ArrayList.class, ArrayList.class, int.class, new BaseMethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void beforeMethod(MethodHookParam param) {
                HiddenFilter filter = HideChatsConfig.filterFor(getAdapterAccount(param.thisObject));
                if (filter == null) return;
                removeHiddenResults(filter, (ArrayList<Object>) param.args[0], (ArrayList<Object>) param.args[1]);
            }
        });

        // Used for the recent search list (history) and as the result filter of the adapter.
        HideChats.hook(dialogsSearchAdapter, "DialogsSearchAdapter", "filter", Object.class, new BaseMethodHook() {
            @Override
            protected void afterMethod(MethodHookParam param) {
                if (!Boolean.TRUE.equals(param.getResult())) return;
                HiddenFilter filter = HideChatsConfig.filterFor(getAdapterAccount(param.thisObject));
                if (filter == null) return;
                long dialogId = TgAccess.getObjectDialogId(param.args[0]);
                if (dialogId != 0 && filter.isHidden(dialogId)) {
                    param.setResult(false);
                }
            }
        });

        Class<?> recentLoaded = TgAccess.findClass(ClassNames.DIALOGS_SEARCH_ADAPTER_RECENT_LOADED);
        if (recentLoaded != null) {
            HideChats.hook(dialogsSearchAdapter, "DialogsSearchAdapter", "loadRecentSearch", int.class, int.class, recentLoaded, new BaseMethodHook() {
                @Override
                protected void beforeMethod(MethodHookParam param) {
                    Object callback = param.args[2];
                    if (callback == null || !HideChatsConfig.isEnabled()) return;
                    param.args[2] = wrapRecentSearchCallback(recentLoaded, (int) param.args[0], callback);
                }
            });
        }

        Class<?> searchAdapterHelper = TgAccess.findClass(ClassNames.SEARCH_ADAPTER_HELPER);
        // "+phone" searches list matching contacts without going through filter()
        HideChats.hook(searchAdapterHelper, "SearchAdapterHelper", "getPhoneSearch", new BaseMethodHook() {
            @Override
            protected void afterMethod(MethodHookParam param) {
                if (!(param.getResult() instanceof ArrayList)) return;
                //noinspection unchecked
                ArrayList<Object> phoneSearch = (ArrayList<Object>) param.getResult();
                if (phoneSearch.isEmpty() || !HideChatsConfig.isEnabled()) return;
                int account = TgAccess.intValue(TgAccess.get(param.thisObject, "SearchAdapterHelper", "currentAccount"), TgAccess.getSelectedAccount());
                HiddenFilter filter = HideChatsConfig.filterFor(account);
                if (filter == null) return;
                boolean removed = phoneSearch.removeIf(item -> {
                    long dialogId = TgAccess.getObjectDialogId(item);
                    return dialogId != 0 && filter.isHidden(dialogId);
                });
                if (removed && !phoneSearch.isEmpty() && "section".equals(phoneSearch.get(0))) {
                    phoneSearch.remove(0);
                }
            }
        });
        Class<?> tlObject = TgAccess.findClass(ClassNames.TL_OBJECT);
        if (tlObject != null) {
            HideChats.hook(searchAdapterHelper, "SearchAdapterHelper", "filter", tlObject, new BaseMethodHook() {
                @Override
                protected void afterMethod(MethodHookParam param) {
                    if (!Boolean.TRUE.equals(param.getResult())) return;
                    int account = TgAccess.intValue(TgAccess.get(param.thisObject, "SearchAdapterHelper", "currentAccount"), TgAccess.getSelectedAccount());
                    HiddenFilter filter = HideChatsConfig.filterFor(account);
                    if (filter == null) return;
                    long dialogId = TgAccess.getObjectDialogId(param.args[0]);
                    if (dialogId != 0 && filter.isHidden(dialogId)) {
                        param.setResult(false);
                    }
                }
            });
        }

        Class<?> mediaDataController = TgAccess.findClass(ClassNames.MEDIA_DATA_CONTROLLER);
        HideChats.hook(mediaDataController, "MediaDataController", "buildShortcuts", new BaseMethodHook() {
            @Override
            protected void beforeMethod(MethodHookParam param) {
                filterHints(param.thisObject);
            }
        });
        // Rating a hidden peer would re-add it to the in-memory hints and reset its stored rating.
        HideChats.hook(mediaDataController, "MediaDataController", "increasePeerRaiting", long.class, new BaseMethodHook() {
            @Override
            protected void beforeMethod(MethodHookParam param) {
                HiddenFilter filter = HideChatsConfig.filterFor(TgAccess.getAccount(param.thisObject));
                if (filter != null && filter.isHidden((long) param.args[0])) {
                    param.setResult(null);
                }
            }
        });

        installNetworkFilter();
    }

    static void refresh(int account) {
        Object mediaDataController = TgAccess.getMediaDataController(account);
        if (mediaDataController != null) {
            if (Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(mediaDataController, KEY_HINTS_FILTERED))) {
                // Peers removed earlier may be visible now: reload the untouched hints from the database
                // (loadHints(true) is a no-op once loaded). The buildShortcuts / reloadHints hooks filter again.
                XposedHelpers.setAdditionalInstanceField(mediaDataController, KEY_HINTS_FILTERED, false);
                TgAccess.set(mediaDataController, "MediaDataController", "loaded", false);
                TgAccess.callWithTypes(mediaDataController, "MediaDataController", "loadHints", new Class[]{boolean.class}, true);
            } else if (HideChatsConfig.filterFor(account) != null) {
                filterHints(mediaDataController);
                TgAccess.call(mediaDataController, "MediaDataController", "buildShortcuts");
                TgAccess.postNotification(account, "reloadHints");
            }
        }
        TgAccess.postNotification(account, "needReloadRecentDialogsSearch");
    }

    static void onHintsReloaded(int account) {
        Object mediaDataController = TgAccess.getMediaDataController(account);
        if (mediaDataController != null) filterHints(mediaDataController);
    }

    static void removeHiddenResults(HiddenFilter filter, ArrayList<Object> results, ArrayList<Object> names) {
        if (results == null) return;
        for (int i = results.size() - 1; i >= 0; i--) {
            long dialogId = TgAccess.getObjectDialogId(results.get(i));
            if (dialogId != 0 && filter.isHidden(dialogId)) {
                results.remove(i);
                if (names != null && i < names.size()) names.remove(i);
            }
        }
    }

    /**
     * The chats of a response (not stored by Telegram yet when the hooks look at it).
     */
    static HashMap<Long, Object> chatsById(ArrayList<Object> chats) {
        HashMap<Long, Object> result = new HashMap<>();
        if (chats == null) return result;
        for (Object chat : chats) {
            long id = TgAccess.longValue(TgAccess.get(chat, "TLRPC$Chat", "id"), 0);
            if (id != 0) result.put(id, chat);
        }
        return result;
    }

    private static int getAdapterAccount(Object adapter) {
        return TgAccess.intValue(TgAccess.get(adapter, "DialogsSearchAdapter", "currentAccount"), TgAccess.getSelectedAccount());
    }

    /**
     * Removes hidden peers from MediaDataController.hints in place. The database copy is untouched.
     */
    private static void filterHints(Object mediaDataController) {
        HiddenFilter filter = HideChatsConfig.filterFor(TgAccess.getAccount(mediaDataController));
        if (filter == null) return;
        ArrayList<Object> hints = TgAccess.getList(mediaDataController, "MediaDataController", "hints");
        if (hints == null) return;
        boolean removed = hints.removeIf(topPeer -> {
            long dialogId = TgAccess.getPeerDialogId(TgAccess.get(topPeer, "TLRPC$TL_topPeer", "peer"));
            return dialogId != 0 && filter.isHidden(dialogId);
        });
        if (removed) {
            XposedHelpers.setAdditionalInstanceField(mediaDataController, KEY_HINTS_FILTERED, true);
        }
    }

    private static Object wrapRecentSearchCallback(Class<?> callbackClass, int account, Object original) {
        return Proxy.newProxyInstance(callbackClass.getClassLoader(), new Class[]{callbackClass}, (proxy, method, args) -> {
            if (args != null && args.length == 2 && args[0] instanceof ArrayList) {
                try {
                    //noinspection unchecked
                    filterRecentSearch(account, (ArrayList<Object>) args[0], args[1]);
                } catch (Throwable t) {
                    Logger.e(t);
                }
            }
            return invokeDelegate(method, original, args);
        });
    }

    private static void filterRecentSearch(int account, ArrayList<Object> recent, Object recentById) {
        HiddenFilter filter = HideChatsConfig.filterFor(account);
        if (filter == null) return;
        recent.removeIf(item -> {
            long dialogId = TgAccess.longValue(TgAccess.get(item, "DialogsSearchAdapter$RecentSearchObject", "did"), 0);
            if (dialogId == 0 || !filter.isHidden(dialogId)) return false;
            if (recentById != null) TgAccess.call(recentById, "LongSparseArray", "remove", dialogId);
            return true;
        });
    }

    // Global searches are filtered where their responses come in, before any adapter sees them
    // (messages through SearchPager, which keeps pages full).

    private static void installNetworkFilter() {
        contactsSearchClass = TgAccess.findClass(ClassNames.TL_CONTACTS_SEARCH);
        contactsFoundClass = TgAccess.findClass(ClassNames.TL_CONTACTS_FOUND);
        messagesSearchGlobalClass = TgAccess.findClass(ClassNames.TL_MESSAGES_SEARCH_GLOBAL);
        channelsSearchPostsClass = TgAccess.findClass(ClassNames.TL_CHANNELS_SEARCH_POSTS);
        messagesSearchClass = TgAccess.findClass(ClassNames.TL_MESSAGES_SEARCH);
        inputPeerEmptyClass = TgAccess.findClass(ClassNames.TL_INPUT_PEER_EMPTY);
        messagesMessagesClass = TgAccess.findClass(ClassNames.TL_MESSAGES_MESSAGES);
        Class<?> connectionsManager = TgAccess.findClass(ClassNames.CONNECTIONS_MANAGER);
        Class<?> requestDelegate = TgAccess.findClass(ClassNames.REQUEST_DELEGATE);
        Class<?>[] parameters = sendRequestInternalParameters();
        boolean available = requestDelegate != null;
        for (Class<?> parameter : parameters) {
            if (parameter == null) available = false;
        }
        if (!available) {
            Logger.w("HideChats: network search filter unavailable");
            return;
        }
        Object[] parametersAndCallback = new Object[parameters.length + 1];
        System.arraycopy(parameters, 0, parametersAndCallback, 0, parameters.length);
        parametersAndCallback[parameters.length] = new BaseMethodHook() {
            @Override
            protected void beforeMethod(MethodHookParam param) {
                Object request = param.args[0];
                if (request == null || param.args[1] == null) return;
                int type = getSearchRequestType(request);
                if (type == REQUEST_NONE || SearchPager.isOwnRequest(request)) return;
                boolean continued = type == REQUEST_MESSAGES && SearchPager.onRequest(request, getPagerKind(request));
                if (!continued && !HideChatsConfig.isEnabled()) return;
                int account = TgAccess.getAccount(param.thisObject);
                param.args[1] = wrapRequestDelegate(requestDelegate, account, type, request, param.args[1]);
            }
        };
        HideChats.hook(connectionsManager, "ConnectionsManager", "sendRequestInternal", parametersAndCallback);
    }

    /**
     * Same signature GhostMode hooks; clients with a different one register it in their ParameterResolver.
     * The request is always the first parameter and its RequestDelegate the second.
     */
    static Class<?>[] sendRequestInternalParameters() {
        Class<?>[] parameters = ArgsResolver.resolveObject("sendRequestInternal", new Class[]{
                TgAccess.findClass(ClassNames.TL_OBJECT),
                TgAccess.findClass(ClassNames.REQUEST_DELEGATE),
                TgAccess.findClass(ClassNames.REQUEST_DELEGATE_TIMESTAMP),
                TgAccess.findClass(ClassNames.QUICK_ACK_DELEGATE),
                TgAccess.findClass(ClassNames.WRITE_TO_SOCKET_DELEGATE),
                int.class, int.class, int.class, boolean.class, int.class
        });
        return parameters != null ? parameters : new Class[]{null};
    }

    private static final int REQUEST_NONE = 0;
    private static final int REQUEST_PEERS = 1;
    // refilled by SearchPager: messages.searchGlobal and the calls log
    private static final int REQUEST_MESSAGES = 2;
    // channels.searchPosts may draw on a paid daily quota: filtered in place, never re-requested
    private static final int REQUEST_POSTS = 3;

    private static Class<?> contactsSearchClass;
    private static Class<?> contactsFoundClass;
    private static Class<?> messagesSearchGlobalClass;
    private static Class<?> channelsSearchPostsClass;
    private static Class<?> messagesSearchClass;
    private static Class<?> inputPeerEmptyClass;
    private static Class<?> messagesMessagesClass;

    private static int getSearchRequestType(Object request) {
        if (request == null) return REQUEST_NONE;
        if (isInstance(contactsSearchClass, request)) return REQUEST_PEERS;
        if (isInstance(messagesSearchGlobalClass, request)) return REQUEST_MESSAGES;
        if (isInstance(channelsSearchPostsClass, request)) return REQUEST_POSTS;
        // messages.search without a peer = calls log
        if (isInstance(messagesSearchClass, request)) {
            Object peer = TgAccess.get(request, "TLRPC$TL_messages_search", "peer");
            if (peer == null || isInstance(inputPeerEmptyClass, peer)) return REQUEST_MESSAGES;
        }
        return REQUEST_NONE;
    }

    private static boolean isInstance(Class<?> cls, Object object) {
        return cls != null && cls.isInstance(object);
    }

    static boolean isMessages(Object response) {
        return isInstance(messagesMessagesClass, response);
    }

    /**
     * The calls log (messages.search) pages by message id, the global searches by rate.
     */
    private static int getPagerKind(Object request) {
        return isInstance(messagesSearchClass, request) ? SearchPager.KIND_ID : SearchPager.KIND_RATE;
    }

    private static Object wrapRequestDelegate(Class<?> delegateClass, int account, int type, Object request, Object original) {
        return Proxy.newProxyInstance(delegateClass.getClassLoader(), new Class[]{delegateClass}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class || args == null || args.length != 2 || args[0] == null || args[1] != null) {
                return invokeDelegate(method, original, args);
            }
            try {
                if (type == REQUEST_PEERS) {
                    filterPeers(account, args[0]);
                } else if (type == REQUEST_POSTS) {
                    filterPosts(account, request, args[0]);
                } else if (isMessages(args[0]) && SearchPager.onResponse(account, getPagerKind(request), request, args[0],
                        response -> invokeDelegate(method, original, new Object[]{response, null}))) {
                    return null;
                }
            } catch (Throwable t) {
                Logger.e(t);
            }
            return invokeDelegate(method, original, args);
        });
    }

    /**
     * Public posts search: pages come out shorter, the count drops by what was removed. A first page
     * that loses everything ends the search (count 0): screens with nothing to show would otherwise
     * ask for that same first page again and again.
     */
    private static void filterPosts(int account, Object request, Object response) {
        HiddenFilter filter = HideChatsConfig.filterFor(account);
        if (filter == null || !isMessages(response)) return;
        ArrayList<Object> messages = TgAccess.getList(response, "TLRPC$messages_Messages", "messages");
        if (messages == null) return;
        int size = messages.size();
        messages.removeIf(message -> message != null && filter.isHidden(TgAccess.getMessageDialogId(message)));
        int removed = size - messages.size();
        if (removed == 0) return;
        boolean firstPage = TgAccess.intValue(TgAccess.get(request, "TLRPC$TL_channels_searchPosts", "offset_rate"), 0) == 0;
        int count = TgAccess.intValue(TgAccess.get(response, "TLRPC$messages_Messages", "count"), 0);
        TgAccess.set(response, "TLRPC$messages_Messages", "count", messages.isEmpty() && firstPage ? 0 : Math.max(messages.size(), count - removed));
    }

    private static void filterPeers(int account, Object response) {
        HiddenFilter filter = HideChatsConfig.filterFor(account);
        if (filter == null || !isInstance(contactsFoundClass, response)) return;
        // my_results are our own contacts and chats, including ones never loaded on this device
        ArrayList<Object> myResults = TgAccess.getList(response, "TLRPC$TL_contacts_found", "my_results");
        if (myResults != null) {
            HashMap<Long, Object> chats = chatsById(TgAccess.getList(response, "TLRPC$TL_contacts_found", "chats"));
            boolean snapshotChanged = false;
            for (Object peer : myResults) {
                long dialogId = TgAccess.getPeerDialogId(peer);
                if (filter.observeDialog(dialogId, 0, dialogId < 0 ? chats.get(-dialogId) : null)) snapshotChanged = true;
            }
            if (snapshotChanged) HideChatsConfig.markDirty(filter.data);
        }
        for (String field : new String[]{"my_results", "results"}) {
            ArrayList<Object> peers = TgAccess.getList(response, "TLRPC$TL_contacts_found", field);
            if (peers != null) {
                peers.removeIf(peer -> filter.isHidden(TgAccess.getPeerDialogId(peer)));
            }
        }
    }

    static Object invokeDelegate(Method method, Object target, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            switch (method.getName()) {
                case "equals":
                    return args != null && args.length == 1 && args[0] == target;
                case "hashCode":
                    return System.identityHashCode(target);
                case "toString":
                    return String.valueOf(target);
            }
        }
        try {
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }
}
