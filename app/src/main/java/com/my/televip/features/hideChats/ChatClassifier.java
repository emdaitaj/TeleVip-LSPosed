package com.my.televip.features.hideChats;

import android.os.Handler;
import android.os.Looper;

import com.my.televip.logging.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * Decides whether a chat that shows up after activation existed before it ("known") or is "new".
 *
 * Channels and supergroups carry the date we joined them and basic groups the date they were created.
 * Otherwise activity older than the activation moment proves the chat existed. For the rest (private
 * chats, bots, secret chats, older basic groups we may have been added to, chats seen without a date)
 * the server is asked for the newest message older than the activation moment: chats that merely were
 * not cached locally have one, chats that started afterwards do not. Until the answer arrives the chat
 * is "pending": hidden whenever either answer would hide it (HiddenFilter#isHiddenKey), with its
 * notifications held back (NotificationsHooks). Without an answer it stays pending and is asked about
 * again later; nothing is guessed.
 */
final class ChatClassifier {

    static final class Probe {
        final long key;
        final int activatedAt;
        // activity seen when the chat went pending (0 = unknown): proof enough once a basic group loads
        final int date;
        // attempts in the current round, finished rounds
        int attempts;
        int rounds;
        boolean roundActive;
        Runnable roundTimeout;

        Probe(long key, int activatedAt, int date) {
            this.key = key;
            this.activatedAt = activatedAt;
            this.date = date;
        }
    }

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 4000;
    private static final long ROUND_TIMEOUT_MS = 30000;
    private static final long NEXT_ROUND_DELAY_MS = 60000;
    private static final long MAX_ROUND_DELAY_MS = 30 * 60000;
    private static final long REQUEST_TIMEOUT_MS = 20000;
    private static final int MAX_REQUESTS_IN_FLIGHT = 3;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    // UI thread only
    private static final LinkedHashMap<Probe, Runnable> waiting = new LinkedHashMap<>();
    private static int requestsInFlight;

    private ChatClassifier() {}

    /**
     * @param key  peer key (HiddenFilter#toPeerKey) of a chat without classification
     * @param date a date the chat was active at (its last message, a search hit...), 0 when unknown
     * @param chat its TLRPC.Chat when the caller has one that is not stored yet, otherwise null
     * @return true when the chat was classified right away (the caller persists the snapshot)
     */
    static boolean classify(HiddenFilter filter, long key, int date, Object chat) {
        HideChatsConfig.AccountData data = filter.data;
        int activatedAt = filter.activatedAt;
        if (key == data.userId || activatedAt <= 0) {
            // no snapshot yet: whatever the account already has existed
            return store(data, key, false);
        }
        if (DialogIds.isEncryptedDialog(key)) {
            // a secret chat whose user is unknown: decided once the user is (HiddenFilter#isHidden)
            return false;
        }
        boolean isChat = DialogIds.isChatDialog(key);
        if (isChat && chat == null) chat = TgAccess.getChat(filter.getMessagesController(), -key);
        if (isNotInChat(chat)) {
            // still listed after we were removed or left: hidden like an old chat, but not classified,
            // since its history can no longer be asked about and joining again must count as new
            data.dead.add(key);
            return false;
        }
        data.dead.remove(key);
        Boolean isNew = decide(chat, isChat, date, activatedAt);
        if (isNew != null) {
            return store(data, key, isNew);
        }
        if (data.pending.add(key)) {
            int account = filter.account;
            HideChats.post(() -> startProbe(account, data, key, activatedAt, date));
        }
        return false;
    }

    /**
     * What can be told without asking the server: join / creation dates, or a message older than the
     * activation, which proves a private chat or basic group existed. Channels and supergroups may show
     * history from before we joined: only their join date counts. Null when undecided.
     */
    private static Boolean decide(Object chat, boolean isChat, int date, int activatedAt) {
        Boolean isNew = decideByChatDate(chat, activatedAt);
        if (isNew == null && date > 0 && date <= activatedAt && (!isChat || chat != null && !TgAccess.isChannel(chat))) {
            isNew = false;
        }
        return isNew;
    }

    private static boolean isNotInChat(Object chat) {
        return chat != null && TgAccess.boolValue(TgAccess.callStatic(TgAccess.chatObjectClass, "ChatObject", "isNotInChat", chat), false);
    }

    /**
     * Records a classification unless the chat already has one (the first one wins) and ends its
     * pending state. Returns true when the snapshot changed.
     */
    static boolean store(HideChatsConfig.AccountData data, long key, boolean isNew) {
        synchronized (data) {
            data.pending.remove(key);
            if (data.known.contains(key) || data.newChats.contains(key)) return false;
            return isNew ? data.newChats.add(key) : data.known.add(key);
        }
    }

    /**
     * Channels / supergroups: joined after activation or not. Basic groups: created after activation
     * means new; an older one may still have been joined afterwards, so that stays undecided.
     */
    private static Boolean decideByChatDate(Object chat, int activatedAt) {
        int chatDate = TgAccess.getChatDate(chat);
        if (chatDate <= 0) return null;
        if (chatDate > activatedAt) return Boolean.TRUE;
        return TgAccess.isChannel(chat) ? Boolean.FALSE : null;
    }

    private static void startProbe(int account, HideChatsConfig.AccountData data, long key, int activatedAt, int date) {
        if (!data.pending.contains(key) || data.probes.containsKey(key)) return;
        Probe probe = new Probe(key, activatedAt, date);
        probe.roundTimeout = () -> endRound(account, data, probe);
        data.probes.put(key, probe);
        startRound(account, data, probe);
    }

    private static void startRound(int account, HideChatsConfig.AccountData data, Probe probe) {
        if (data.probes.get(probe.key) != probe) return;
        probe.attempts = 0;
        probe.roundActive = true;
        handler.removeCallbacks(probe.roundTimeout);
        handler.postDelayed(probe.roundTimeout, ROUND_TIMEOUT_MS);
        sendProbe(account, data, probe);
    }

    private static void sendProbe(int account, HideChatsConfig.AccountData data, Probe probe) {
        if (data.probes.get(probe.key) != probe || !probe.roundActive) return;
        // the chat may have been loaded in the meantime
        boolean isChat = DialogIds.isChatDialog(probe.key);
        Object chat = isChat ? TgAccess.getChat(TgAccess.getMessagesController(account), -probe.key) : null;
        if (isNotInChat(chat)) {
            data.dead.add(probe.key);
            finish(account, data, probe, null);
            return;
        }
        Boolean isNew = decide(chat, isChat, probe.date, probe.activatedAt);
        if (isNew != null) {
            finish(account, data, probe, isNew);
            return;
        }
        if (requestsInFlight >= MAX_REQUESTS_IN_FLIGHT) {
            waiting.put(probe, () -> sendProbe(account, data, probe));
            return;
        }
        probe.attempts++;
        Object request = null;
        try {
            request = buildRequest(account, probe);
        } catch (Throwable t) {
            Logger.e(t);
        }
        if (request == null) {
            onFailure(account, data, probe);
            return;
        }
        // the slot is given back by the answer or, if none ever comes (offline, logged out), the timeout
        boolean[] completed = {false};
        Runnable timeout = () -> {
            if (completed[0]) return;
            completed[0] = true;
            requestsInFlight--;
            onFailure(account, data, probe);
            sendWaiting();
        };
        requestsInFlight++;
        boolean sent = TgAccess.sendRequest(account, request, (response, error) -> handler.post(() -> {
            if (completed[0]) return;
            completed[0] = true;
            requestsInFlight--;
            handler.removeCallbacks(timeout);
            try {
                onAnswer(account, data, probe, response, error);
            } catch (Throwable t) {
                Logger.e(t);
            } finally {
                sendWaiting();
            }
        }));
        if (sent) {
            handler.postDelayed(timeout, REQUEST_TIMEOUT_MS);
        } else {
            completed[0] = true;
            requestsInFlight--;
            onFailure(account, data, probe);
        }
    }

    private static void sendWaiting() {
        Iterator<Runnable> iterator = waiting.values().iterator();
        while (requestsInFlight < MAX_REQUESTS_IN_FLIGHT && iterator.hasNext()) {
            Runnable next = iterator.next();
            iterator.remove();
            next.run();
            // run() may have queued again: restart over the current contents
            iterator = waiting.values().iterator();
        }
    }

    /**
     * messages.getHistory(peer, offset_date = activation, limit = 1): the newest message sent before
     * the activation moment, if there is one.
     */
    private static Object buildRequest(int account, Probe probe) {
        Object messagesController = TgAccess.getMessagesController(account);
        long key = probe.key;
        // the input peer needs the access hash of a loaded user / chat
        Object peer = DialogIds.isUserDialog(key) ? TgAccess.getUser(messagesController, key) : TgAccess.getChat(messagesController, -key);
        if (peer == null) return null;
        Object inputPeer = TgAccess.getInputPeer(messagesController, key);
        Object request = TgAccess.newInstance(TgAccess.getHistoryClass);
        if (inputPeer == null || request == null) return null;
        boolean ok = TgAccess.set(request, "TLRPC$TL_messages_getHistory", "peer", inputPeer)
                && TgAccess.set(request, "TLRPC$TL_messages_getHistory", "offset_date", probe.activatedAt)
                && TgAccess.set(request, "TLRPC$TL_messages_getHistory", "limit", 1);
        return ok ? request : null;
    }

    private static void onAnswer(int account, HideChatsConfig.AccountData data, Probe probe, Object response, Object error) {
        if (data.probes.get(probe.key) != probe) return;
        ArrayList<Object> messages = error == null ? TgAccess.getList(response, "TLRPC$messages_Messages", "messages") : null;
        if (messages == null) {
            onFailure(account, data, probe);
            return;
        }
        boolean existed = false;
        for (Object message : messages) {
            int date = TgAccess.getMessageDate(message);
            if (date > 0 && date <= probe.activatedAt) {
                existed = true;
                break;
            }
        }
        finish(account, data, probe, !existed);
    }

    private static void onFailure(int account, HideChatsConfig.AccountData data, Probe probe) {
        // a request of a round that already ended: the next round is scheduled
        if (data.probes.get(probe.key) != probe || !probe.roundActive) return;
        if (probe.attempts >= MAX_ATTEMPTS) {
            endRound(account, data, probe);
        } else {
            handler.postDelayed(() -> sendProbe(account, data, probe), RETRY_DELAY_MS * Math.max(1, probe.attempts));
        }
    }

    /**
     * No answer this round: the chat stays pending (hidden whenever it might have to be) and is asked
     * about again later, backing off up to MAX_ROUND_DELAY_MS.
     */
    private static void endRound(int account, HideChatsConfig.AccountData data, Probe probe) {
        if (data.probes.get(probe.key) != probe || !probe.roundActive) return;
        probe.roundActive = false;
        handler.removeCallbacks(probe.roundTimeout);
        waiting.remove(probe);
        probe.rounds++;
        long delay = Math.min(MAX_ROUND_DELAY_MS, NEXT_ROUND_DELAY_MS << Math.min(probe.rounds - 1, 5));
        if (probe.rounds == 1) Logger.w("HideChats: no answer classifying chat " + probe.key + ", retrying later");
        handler.postDelayed(() -> startRound(account, data, probe), delay);
    }

    /**
     * @param isNew the classification, or null when the chat turned out to be one we are not in
     */
    private static void finish(int account, HideChatsConfig.AccountData data, Probe probe, Boolean isNew) {
        data.probes.remove(probe.key);
        handler.removeCallbacks(probe.roundTimeout);
        waiting.remove(probe);
        if (isNew == null) {
            data.pending.remove(probe.key);
        } else if (probe.activatedAt == data.activatedAt && store(data, probe.key, isNew)) {
            HideChatsConfig.markDirty(data);
        }
        NotificationsHooks.settleHeld(account, data);
        HideChats.scheduleRefreshAccount(account);
    }
}
