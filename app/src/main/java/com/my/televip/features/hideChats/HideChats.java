package com.my.televip.features.hideChats;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.my.televip.Class.ClassNames;
import com.my.televip.Clients.ClientManager;
import com.my.televip.base.BaseMethodHook;
import com.my.televip.features.hideChats.ui.ChatPicker;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.Obfuscate;
import com.my.televip.obfuscate.ObfuscationManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XposedHelpers;

/**
 * Hides chats everywhere in the client: every chat list / folder / archive, contacts, search
 * (recent searches, top peers, local + global results, message results), share sheets, stories,
 * unread badges and optionally notifications.
 *
 * Hooks never mutate Telegram's master data (allDialogs, dialogs_dict, contacts, database); only the
 * derived lists the UI reads from are filtered, so disabling the feature restores everything.
 */
public class HideChats {

    private static final long REFRESH_DELAY_MS = 150;
    private static final long COUNTERS_REFRESH_DELAY_MS = 1000;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable refreshAllRunnable = HideChats::refreshAll;
    // UI thread only
    private static final Set<Integer> accountsToRefresh = new HashSet<>();
    private static final Set<Integer> countersToRefresh = new HashSet<>();
    private static final Runnable refreshAccountsRunnable = () -> {
        ArrayList<Integer> accounts = new ArrayList<>(accountsToRefresh);
        accountsToRefresh.clear();
        for (int account : accounts) refreshAccount(account);
    };
    private static final Runnable refreshCountersRunnable = () -> {
        ArrayList<Integer> accounts = new ArrayList<>(countersToRefresh);
        countersToRefresh.clear();
        for (int account : accounts) refreshStep(() -> UnreadCountersHooks.refresh(account));
    };
    private static boolean hooksInstalled;
    private static boolean notificationHooksInstalled;
    private static Boolean supported;

    /**
     * Official Telegram (incl. Web / Beta) is the verified target. Other clients get the feature only
     * when every hook point it cannot work without exists with the expected signature, so a partially
     * working (leaking) variant is never offered. Obfuscated clients would need name mappings.
     */
    public static boolean isSupported() {
        if (supported == null) {
            boolean result = false;
            try {
                result = isPotentiallySupported() && (isOfficialClient() || hasCriticalHookPoints());
            } catch (Throwable t) {
                Logger.e(t);
            }
            if (!result && isPotentiallySupported()) {
                Logger.w("HideChats: disabled, required hook points are missing in this client");
            }
            supported = result;
        }
        return supported;
    }

    public static boolean isPotentiallySupported() {
        return ObfuscationManager.current() == null && !ClientManager.isTgnetObfuscated();
    }

    private static boolean isOfficialClient() {
        return ClientManager.is(ClientManager.Client.Telegram)
                || ClientManager.is(ClientManager.Client.TelegramWeb)
                || ClientManager.is(ClientManager.Client.TelegramBeta);
    }

    private static boolean hasCriticalHookPoints() {
        TgAccess.init();
        Class<?> longSparseArray = TgAccess.findClass(ClassNames.LONG_SPARES_ARRAY);
        Class<?> dialogsSearchAdapter = TgAccess.findClass(ClassNames.DIALOGS_SEARCH_ADAPTER);
        Class<?> recentLoaded = TgAccess.findClass(ClassNames.DIALOGS_SEARCH_ADAPTER_RECENT_LOADED);
        return hasMethod(TgAccess.messagesControllerClass, "MessagesController", "sortDialogs", longSparseArray)
                && hasMethod(TgAccess.messagesControllerClass, "MessagesController", "hasHiddenArchive")
                && hasMethod(TgAccess.messagesControllerClass, "MessagesController", "reorderPinnedDialogs", int.class, ArrayList.class, long.class)
                && hasMethod(TgAccess.findClass(ClassNames.DIALOGS_ADAPTER), "DialogsAdapter", "updateItemList")
                && hasMethod(TgAccess.contactsControllerClass, "ContactsController", "buildContactsSectionsArrays", boolean.class)
                && hasMethod(TgAccess.messagesStorageClass, "MessagesStorage", "localSearch", int.class, String.class, ArrayList.class, ArrayList.class, ArrayList.class, ArrayList.class, int.class)
                && hasMethod(dialogsSearchAdapter, "DialogsSearchAdapter", "filter", Object.class)
                && hasMethod(dialogsSearchAdapter, "DialogsSearchAdapter", "updateSearchResults", ArrayList.class, ArrayList.class, ArrayList.class, ArrayList.class, int.class)
                && hasMethod(dialogsSearchAdapter, "DialogsSearchAdapter", "loadRecentSearch", int.class, int.class, recentLoaded)
                && hasMethod(TgAccess.connectionsManagerClass, "ConnectionsManager", "sendRequestInternal", (Object[]) SearchHooks.sendRequestInternalParameters());
    }

    private static boolean hasMethod(Class<?> cls, String classKey, String method, Object... parameterTypes) {
        if (cls == null) return false;
        for (Object type : parameterTypes) {
            if (type == null) return false;
        }
        return XposedHelpers.findMethodExactIfExists(cls, Obfuscate.getMethodName(classKey, method), parameterTypes) != null;
    }

    /**
     * Called from Application.onCreate: push notifications may start the process without any activity,
     * long before the regular feature initialization runs.
     */
    public static void earlyInit() {
        if (!isPotentiallySupported()) return;
        try {
            if (HideChatsConfig.load() && HideChatsConfig.isEnabled()) {
                installNotificationHooks();
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    /**
     * Runnable of the main switch. Runs at startup when the switch is on and on every toggle.
     */
    public static void onToggle() {
        if (!isSupported()) return;
        runOnUiThread(() -> {
            try {
                if (!HideChatsConfig.load()) return;
                if (HideChatsConfig.isEnabled()) {
                    installHooks();
                    if (HideChatsConfig.needsSnapshot()) {
                        HideChatsConfig.activate();
                    }
                    forEachAccount(DialogsHooks::filterNow);
                } else if (!HideChatsConfig.needsSnapshot()) {
                    HideChatsConfig.deactivate();
                }
                scheduleRefreshAll();
            } catch (Throwable t) {
                Logger.e(t);
            }
        });
    }

    /**
     * Mode, "hide new chats", notification or selection changes.
     */
    public static void onSettingsChanged() {
        if (!isSupported()) return;
        runOnUiThread(() -> {
            try {
                if (HideChatsConfig.load()) scheduleRefreshAll();
            } catch (Throwable t) {
                Logger.e(t);
            }
        });
    }

    /**
     * Several switches run their runnable together at startup; refresh once.
     */
    private static void scheduleRefreshAll() {
        if (!hooksInstalled) return;
        handler.removeCallbacks(refreshAllRunnable);
        handler.postDelayed(refreshAllRunnable, REFRESH_DELAY_MS);
    }

    /**
     * A chat got classified in the background: refresh everything that shows it (coalesced).
     */
    static void scheduleRefreshAccount(int account) {
        runOnUiThread(() -> {
            if (!hooksInstalled) return;
            accountsToRefresh.add(account);
            handler.removeCallbacks(refreshAccountsRunnable);
            handler.postDelayed(refreshAccountsRunnable, REFRESH_DELAY_MS);
        });
    }

    /**
     * Chats got classified after the storage thread may have counted them as unread (coalesced).
     */
    static void scheduleCountersRefresh(int account) {
        runOnUiThread(() -> {
            if (!hooksInstalled) return;
            countersToRefresh.add(account);
            handler.removeCallbacks(refreshCountersRunnable);
            handler.postDelayed(refreshCountersRunnable, COUNTERS_REFRESH_DELAY_MS);
        });
    }

    public static boolean needsEnableConfirmation() {
        if (!HideChatsConfig.load()) return false;
        return HideChatsConfig.getMode() == HideChatsConfig.MODE_SHOW_ONLY_SELECTED
                && HideChatsConfig.getSelected(TgAccess.getSelectedAccount()).isEmpty();
    }

    public static int getSelectedCount() {
        if (!HideChatsConfig.load()) return 0;
        return HideChatsConfig.getSelected(TgAccess.getSelectedAccount()).size();
    }

    public static void openPicker(Activity activity, Runnable onChanged) {
        if (!HideChatsConfig.load()) return;
        int account = TgAccess.getSelectedAccount();
        if (TgAccess.getClientUserId(account) == 0) return;
        Set<Long> selected = HideChatsConfig.getSelected(account);
        ChatPicker.show(activity, account, selected, HideChatsConfig.getMode() == HideChatsConfig.MODE_SHOW_ONLY_SELECTED, result -> {
            HideChatsConfig.setSelected(account, result);
            onSettingsChanged();
            if (onChanged != null) onChanged.run();
        });
    }

    private static void installHooks() {
        if (hooksInstalled) return;
        hooksInstalled = true;
        TgAccess.init();
        installNotificationHooks();
        installNotificationCenterHook();
        DialogsHooks.install();
        ContactsHooks.install();
        SearchHooks.install();
        StoriesHooks.install();
        UnreadCountersHooks.install();
    }

    private static void installNotificationHooks() {
        if (notificationHooksInstalled) return;
        notificationHooksInstalled = true;
        TgAccess.init();
        NotificationsHooks.install();
    }

    /**
     * Telegram replaces the contact sections / top peers wholesale and then posts these events;
     * filtering right before observers run keeps every screen consistent.
     */
    private static void installNotificationCenterHook() {
        int contactsDidLoad = TgAccess.getNotificationId("contactsDidLoad");
        int reloadHints = TgAccess.getNotificationId("reloadHints");
        hook(TgAccess.notificationCenterClass, "NotificationCenter", "postNotificationName", int.class, Object[].class, new BaseMethodHook() {
            @Override
            protected void beforeMethod(MethodHookParam param) {
                int id = (int) param.args[0];
                if ((id != contactsDidLoad && id != reloadHints) || !HideChatsConfig.isEnabled()) return;
                int account = TgAccess.getNotificationCenterAccount(param.thisObject);
                if (account < 0) return;
                if (id == contactsDidLoad) {
                    ContactsHooks.onContactsLoaded(account);
                } else {
                    SearchHooks.onHintsReloaded(account);
                }
            }
        });
    }

    static void refreshAll() {
        forEachAccount(HideChats::refreshAccount);
    }

    private interface AccountAction {
        void run(int account);
    }

    private static void forEachAccount(AccountAction action) {
        int count = TgAccess.getMaxAccountCount();
        for (int account = 0; account < count; account++) {
            if (TgAccess.getClientUserId(account) == 0) continue;
            try {
                action.run(account);
            } catch (Throwable t) {
                Logger.e(t);
            }
        }
    }

    static void refreshAccount(int account) {
        if (!hooksInstalled) return;
        runOnUiThread(() -> {
            refreshStep(() -> DialogsHooks.refresh(account));
            refreshStep(() -> ContactsHooks.refresh(account));
            refreshStep(() -> SearchHooks.refresh(account));
            refreshStep(() -> StoriesHooks.refresh(account));
            refreshStep(() -> UnreadCountersHooks.refresh(account));
            refreshStep(() -> NotificationsHooks.refresh(account));
        });
    }

    private static void refreshStep(Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    static boolean hook(Class<?> cls, String classKey, String method, Object... parameterTypesAndCallback) {
        if (cls == null) {
            Logger.w("HideChats: class for " + classKey + "#" + method + " not found");
            return false;
        }
        try {
            XposedHelpers.findAndHookMethod(cls, Obfuscate.getMethodName(classKey, method), parameterTypesAndCallback);
            return true;
        } catch (Throwable t) {
            Logger.w("HideChats: unable to hook " + cls.getName() + "#" + method + ": " + t);
            return false;
        }
    }

    /**
     * Always later on the UI thread: for work started from inside hooks, which must not re-enter
     * Telegram (sort, reload, notify) in the middle of the hooked call.
     */
    static void post(Runnable runnable) {
        handler.post(runnable);
    }

    static void runOnUiThread(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            handler.post(runnable);
        }
    }
}
