package com.my.televip.features.hideChats;

import com.my.televip.logging.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import de.robv.android.xposed.XposedHelpers;

/**
 * Global message searches (messages.searchGlobal and the peer-less messages.search of the calls log)
 * are filtered where their responses arrive. Dropping results would shrink pages, and screens take a
 * short page for the end of the results (the chats search even wants exactly 20), so a page that lost
 * results is refilled from the following server pages first (a few at most, to stay clear of flood
 * limits):
 * - rate based searches deliver exactly the requested number of results while more exist. Results
 *   fetched beyond that are kept and handed out first with the next page; the next_rate the screen
 *   gets is a token (negative, real rates never are) that maps its next request back to the real
 *   server position.
 * - the calls log pages by message id and accepts any non-empty page: whole pages are delivered.
 * The refill is bounded: when nearly every result belongs to hidden chats, a page can still come out
 * short and the screen stops paging there (searching inside the chat still finds everything).
 */
final class SearchPager {

    interface Delivery {
        void deliver(Object response) throws Throwable;
    }

    static final int KIND_RATE = 1;
    static final int KIND_ID = 2;

    // extra server pages per delivered page: global search pages are refilled 50 at a time, the calls
    // log goes by the screen's page size and needs just one visible call per page
    private static final int MAX_EXTRA_PAGES_RATE = 4;
    private static final int MAX_EXTRA_PAGES_ID = 9;
    // refill pages of rate based searches are fetched larger: with most chats hidden, few results
    // of a page survive
    private static final int REFILL_PAGE_SIZE = 50;
    private static final int MAX_SESSIONS = 32;
    private static final String KEY_SESSION = "televipHideChatsSearchSession";
    private static final String KEY_OWN_REQUEST = "televipHideChatsOwnRequest";
    private static final String REQUEST = "TLRPC$TL_messages_searchGlobal";
    private static final String RESPONSE = "TLRPC$messages_Messages";

    /**
     * Where a refilled rate based search goes on: the server position and the results fetched
     * beyond the last delivered page.
     */
    private static final class Session {
        final int token;
        int rate;
        int offsetId;
        long offsetPeerId;
        Object offsetPeer;
        // the server has nothing more: only the kept results are left
        boolean serverEnd;
        final ArrayList<Object> results = new ArrayList<>();
        final ArrayList<Object> users = new ArrayList<>();
        final ArrayList<Object> chats = new ArrayList<>();
        int hidden;
        int delivered;

        Session(int token) {
            this.token = token;
        }
    }

    // guarded by itself
    private static final LinkedHashMap<Integer, Session> sessions = new LinkedHashMap<Integer, Session>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Session> eldest) {
            return size() > MAX_SESSIONS;
        }
    };
    private static int lastToken;

    private SearchPager() {}

    /**
     * Requests this pager sends itself; the request hook leaves them alone.
     */
    static boolean isOwnRequest(Object request) {
        return Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(request, KEY_OWN_REQUEST));
    }

    /**
     * Before a search request is sent: one continuing a refilled search carries a token as
     * offset_rate and is pointed back at the real server position. Returns true when it was.
     * Runs even while hiding is off, so searches started before keep working.
     */
    static boolean onRequest(Object request, int kind) {
        if (kind != KIND_RATE) return false;
        int rate = TgAccess.intValue(TgAccess.get(request, REQUEST, "offset_rate"), 0);
        if (rate >= 0) return false;
        Session session;
        synchronized (sessions) {
            session = sessions.get(rate);
        }
        if (session == null) {
            // forgotten long ago: never send the server a made up rate
            TgAccess.set(request, REQUEST, "offset_rate", 0);
            return false;
        }
        // Screens tell a first page by offset_id == 0 (and clear their results): a continuation keeps
        // its non-zero id even where the real position is unknown or no longer matters.
        TgAccess.set(request, REQUEST, "offset_rate", session.rate);
        if (!session.serverEnd) {
            if (session.offsetId != 0) TgAccess.set(request, REQUEST, "offset_id", session.offsetId);
            if (session.offsetPeer != null) TgAccess.set(request, REQUEST, "offset_peer", session.offsetPeer);
        }
        XposedHelpers.setAdditionalInstanceField(request, KEY_SESSION, session);
        return true;
    }

    /**
     * A successful search response. Returns true when the pager delivers it (right away or once more
     * pages arrived), false when it can be delivered untouched.
     */
    static boolean onResponse(int account, int kind, Object request, Object response, Delivery delivery) {
        Object previous = XposedHelpers.getAdditionalInstanceField(request, KEY_SESSION);
        HiddenFilter filter = HideChatsConfig.filterFor(account);
        if (filter == null && previous == null) return false;
        Page page = new Page(account, kind, request, response, filter, previous instanceof Session ? (Session) previous : null, delivery);
        try {
            return page.start();
        } catch (Throwable t) {
            Logger.e(t);
            page.deliver();
            return true;
        }
    }

    private static final class Page {
        final int account;
        final int kind;
        final int limit;
        final Object request;
        final Object container;
        final HiddenFilter filter;
        final Session previous;
        final Delivery delivery;
        final ArrayList<Object> results = new ArrayList<>();
        final ArrayList<Object> users = new ArrayList<>();
        final ArrayList<Object> chats = new ArrayList<>();
        // users / chats of the last server page: the only ones results kept for later can need
        ArrayList<Object> lastUsers = new ArrayList<>();
        ArrayList<Object> lastChats = new ArrayList<>();
        int hidden;
        int delivered;
        int serverCount;
        int extraPages;
        // server position after the last page
        int rate;
        int offsetId;
        long offsetPeerId;
        boolean serverEnd;
        boolean done;

        Page(int account, int kind, Object request, Object container, HiddenFilter filter, Session previous, Delivery delivery) {
            this.account = account;
            this.kind = kind;
            this.request = request;
            this.container = container;
            this.filter = filter;
            this.previous = previous;
            this.delivery = delivery;
            this.limit = Math.max(1, TgAccess.intValue(TgAccess.get(request, REQUEST, "limit"), 20));
        }

        boolean start() {
            if (previous != null) {
                synchronized (sessions) {
                    sessions.remove(previous.token);
                }
                results.addAll(previous.results);
                users.addAll(previous.users);
                chats.addAll(previous.chats);
                hidden = previous.hidden;
                delivered = previous.delivered;
                // the position stays where it was until a non-empty server page moves it
                rate = previous.rate;
                offsetId = previous.offsetId;
                offsetPeerId = previous.offsetPeerId;
                serverEnd = previous.serverEnd;
                lastUsers = new ArrayList<>(previous.users);
                lastChats = new ArrayList<>(previous.chats);
            }
            if (previous != null && previous.serverEnd) {
                // the request only went out because a request has to: its answer is not part of the results
                serverEnd = true;
                deliver();
                return true;
            }
            int removed = consume(container);
            if (previous == null && removed == 0) return false;
            next();
            return true;
        }

        private int consume(Object response) {
            ArrayList<Object> messages = TgAccess.getList(response, RESPONSE, "messages");
            int size = messages != null ? messages.size() : 0;
            int removed = 0;
            boolean snapshotChanged = false;
            for (int i = 0; i < size; i++) {
                Object message = messages.get(i);
                if (message == null) continue;
                long dialogId = TgAccess.getMessageDialogId(message);
                if (filter != null) {
                    // results come from all of our chats, including ones never loaded on this device
                    if (filter.observeDialog(dialogId, TgAccess.getMessageDate(message))) snapshotChanged = true;
                    if (filter.isHidden(dialogId)) {
                        removed++;
                        continue;
                    }
                }
                results.add(message);
            }
            if (snapshotChanged) HideChatsConfig.markDirty(filter.data);
            ArrayList<Object> pageUsers = copy(TgAccess.getList(response, RESPONSE, "users"));
            ArrayList<Object> pageChats = copy(TgAccess.getList(response, RESPONSE, "chats"));
            users.addAll(pageUsers);
            chats.addAll(pageChats);
            hidden += removed;
            serverCount = Math.max(serverCount, TgAccess.intValue(TgAccess.get(response, RESPONSE, "count"), 0));
            if (size > 0) {
                Object last = messages.get(size - 1);
                offsetId = TgAccess.getMessageId(last);
                offsetPeerId = TgAccess.getMessagePeerDialogId(last);
                lastUsers = pageUsers;
                lastChats = pageChats;
            }
            if (kind == KIND_RATE) {
                if (size > 0) rate = TgAccess.intValue(TgAccess.get(response, RESPONSE, "next_rate"), 0);
                serverEnd = size == 0 || rate == 0;
            } else {
                serverEnd = size < limit;
            }
            return removed;
        }

        private void next() {
            boolean needMore = kind == KIND_RATE ? results.size() < limit : results.isEmpty();
            if (needMore && !serverEnd && extraPages < (kind == KIND_RATE ? MAX_EXTRA_PAGES_RATE : MAX_EXTRA_PAGES_ID)) {
                Object nextRequest = nextRequest();
                if (nextRequest != null) {
                    extraPages++;
                    if (TgAccess.sendRequest(account, nextRequest, this::onNextPage)) return;
                }
            }
            deliver();
        }

        private void onNextPage(Object response, Object error) {
            try {
                if (error == null && SearchHooks.isMessages(response)) {
                    consume(response);
                    next();
                    return;
                }
            } catch (Throwable t) {
                Logger.e(t);
            }
            // keep what arrived; the screen asks again from the same server position
            deliver();
        }

        private Object nextRequest() {
            Object nextRequest = copyRequest(request);
            if (nextRequest == null) return null;
            if (kind == KIND_RATE) {
                Object peer = buildInputPeer(offsetPeerId, users, chats);
                if (peer == null) return null;
                TgAccess.set(nextRequest, REQUEST, "offset_rate", rate);
                TgAccess.set(nextRequest, REQUEST, "offset_peer", peer);
                TgAccess.set(nextRequest, REQUEST, "limit", Math.max(limit, REFILL_PAGE_SIZE));
            }
            TgAccess.set(nextRequest, REQUEST, "offset_id", offsetId);
            XposedHelpers.setAdditionalInstanceField(nextRequest, KEY_OWN_REQUEST, true);
            return nextRequest;
        }

        void deliver() {
            if (done) return;
            done = true;
            Object response = container;
            try {
                ArrayList<Object> page = results;
                ArrayList<Object> rest = new ArrayList<>();
                if (kind == KIND_RATE && results.size() > limit) {
                    page = new ArrayList<>(results.subList(0, limit));
                    rest = new ArrayList<>(results.subList(limit, results.size()));
                }
                boolean more = !rest.isEmpty() || !serverEnd;
                delivered += page.size();
                TgAccess.set(response, RESPONSE, "messages", page);
                TgAccess.set(response, RESPONSE, "users", users);
                TgAccess.set(response, RESPONSE, "chats", chats);
                // screens that page by count stop once they hold "count" results
                TgAccess.set(response, RESPONSE, "count", more ? Math.max(serverCount - hidden, delivered + rest.size() + 1) : delivered);
                if (kind == KIND_RATE) {
                    int flags = TgAccess.intValue(TgAccess.get(response, RESPONSE, "flags"), 0);
                    if (more) {
                        Session session = newSession();
                        session.rate = rate;
                        session.offsetId = offsetId;
                        session.offsetPeerId = offsetPeerId;
                        session.offsetPeer = buildInputPeer(offsetPeerId, lastUsers, lastChats);
                        session.serverEnd = serverEnd;
                        session.results.addAll(rest);
                        session.users.addAll(lastUsers);
                        session.chats.addAll(lastChats);
                        session.hidden = hidden;
                        session.delivered = delivered;
                        TgAccess.set(response, RESPONSE, "next_rate", session.token);
                        flags |= 1;
                    } else {
                        TgAccess.set(response, RESPONSE, "next_rate", 0);
                        flags &= ~1;
                    }
                    TgAccess.set(response, RESPONSE, "flags", flags);
                }
            } catch (Throwable t) {
                Logger.e(t);
            }
            try {
                delivery.deliver(response);
            } catch (Throwable t) {
                Logger.e(t);
            }
        }

        /**
         * The offset peer of the next request: the chat of the last result, as Telegram builds it.
         */
        private Object buildInputPeer(long dialogId, ArrayList<Object> users, ArrayList<Object> chats) {
            if (DialogIds.isUserDialog(dialogId)) {
                Object user = findById(users, "TLRPC$User", dialogId);
                if (user == null) return TgAccess.getInputPeer(TgAccess.getMessagesController(account), dialogId);
                if (TgAccess.boolValue(TgAccess.get(user, "TLRPC$User", "self"), false)) {
                    return TgAccess.newInstance(TgAccess.inputPeerSelfClass);
                }
                Object peer = TgAccess.newInstance(TgAccess.inputPeerUserClass);
                if (peer == null) return null;
                TgAccess.set(peer, "TLRPC$InputPeer", "user_id", dialogId);
                TgAccess.set(peer, "TLRPC$InputPeer", "access_hash", TgAccess.longValue(TgAccess.get(user, "TLRPC$User", "access_hash"), 0));
                return peer;
            }
            if (DialogIds.isChatDialog(dialogId)) {
                Object chat = findById(chats, "TLRPC$Chat", -dialogId);
                if (chat == null) return TgAccess.getInputPeer(TgAccess.getMessagesController(account), dialogId);
                Object peer;
                if (TgAccess.isChannel(chat)) {
                    peer = TgAccess.newInstance(TgAccess.inputPeerChannelClass);
                    if (peer == null) return null;
                    TgAccess.set(peer, "TLRPC$InputPeer", "channel_id", -dialogId);
                    TgAccess.set(peer, "TLRPC$InputPeer", "access_hash", TgAccess.longValue(TgAccess.get(chat, "TLRPC$Chat", "access_hash"), 0));
                } else {
                    peer = TgAccess.newInstance(TgAccess.inputPeerChatClass);
                    if (peer == null) return null;
                    TgAccess.set(peer, "TLRPC$InputPeer", "chat_id", -dialogId);
                }
                return peer;
            }
            return null;
        }
    }

    private static Session newSession() {
        synchronized (sessions) {
            lastToken = lastToken <= Integer.MIN_VALUE + 1 ? -1 : lastToken - 1;
            Session session = new Session(lastToken);
            sessions.put(session.token, session);
            return session;
        }
    }

    private static Object findById(ArrayList<Object> objects, String classKey, long id) {
        for (int i = objects.size() - 1; i >= 0; i--) {
            Object object = objects.get(i);
            if (TgAccess.longValue(TgAccess.get(object, classKey, "id"), 0) == id) return object;
        }
        return null;
    }

    private static ArrayList<Object> copy(ArrayList<Object> list) {
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    /**
     * Shallow copy of a TL request (all instance fields, including flags).
     */
    private static Object copyRequest(Object request) {
        Object copy = TgAccess.newInstance(request.getClass());
        if (copy == null) return null;
        for (Class<?> cls = request.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) continue;
                try {
                    field.setAccessible(true);
                    field.set(copy, field.get(request));
                } catch (Throwable ignored) {}
            }
        }
        return copy;
    }
}
