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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;
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

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable refreshAllRunnable = HideChats::refreshAll;
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
                // names worked out on the device are checked against the build before the feature is
                // offered at all, so a class the resolver missed turns it off instead of half hiding
                boolean trustedNames = isOfficialClient() && !ObfuscationManager.isRuntimeResolved();
                result = isPotentiallySupported() && (trustedNames || hasCriticalHookPoints());
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
        return (ObfuscationManager.current() == null || ObfuscationManager.isVersionMapped()
                || ObfuscationManager.isRuntimeResolved()) && !ClientManager.isTgnetObfuscated();
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
        return hasMethod(TgAccess.messagesControllerClass, "MessagesController", "sortDialogs", longSparseArray)
                && hasMethod(TgAccess.messagesControllerClass, "MessagesController", "hasHiddenArchive")
                && hasMethod(TgAccess.messagesControllerClass, "MessagesController", "reorderPinnedDialogs", int.class, ArrayList.class, long.class)
                && hasMethod(TgAccess.findClass(ClassNames.DIALOGS_ADAPTER), "DialogsAdapter", "updateItemList")
                && hasMethod(TgAccess.contactsControllerClass, "ContactsController", "buildContactsSectionsArrays", boolean.class)
                && hasMethod(TgAccess.messagesStorageClass, "MessagesStorage", "localSearch", int.class, String.class, ArrayList.class, ArrayList.class, ArrayList.class, ArrayList.class, int.class)
                && hasMethod(dialogsSearchAdapter, "DialogsSearchAdapter", "filter", Object.class)
                && hasMethod(dialogsSearchAdapter, "DialogsSearchAdapter", "getItemCount")
                && hasMethod(TgAccess.findClass(ClassNames.DIALOG_CELL), "DialogCell", "buildLayout")
                && hasMethod(TgAccess.findClass(ClassNames.CHAT_ACTIVITY), "ChatActivity", "onFragmentCreate")
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
                    // "hide new chats" takes effect now: what exists at this point is not new. A
                    // snapshot kept from before (startup with hiding on) is left as it is.
                    if (HideChatsConfig.isHideNewChats()) forEachAccount(HideChatsConfig::captureKnown);
                    installHooks();
                    forEachAccount(DialogsHooks::filterNow);
                }
                scheduleRefreshAll();
            } catch (Throwable t) {
                Logger.e(t);
            }
        });
    }

    /**
     * Runnable of the "hide new chats" switch. Takes the snapshot the option needs when it takes
     * effect; load() forgets it whenever the option is not in effect.
     */
    public static void onHideNewChatsToggled() {
        if (!isSupported()) return;
        runOnUiThread(() -> {
            try {
                if (!HideChatsConfig.load()) return;
                if (HideChatsConfig.isHideNewChats()) forEachAccount(HideChatsConfig::captureKnown);
                scheduleRefreshAll();
            } catch (Throwable t) {
                Logger.e(t);
            }
        });
    }

    /**
     * The managed list, the visible selection or the notification switch changed.
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
     * Number of chats on the managed list of the current account.
     */
    public static int getManagedCount() {
        if (!HideChatsConfig.load()) return 0;
        return HideChatsConfig.getManaged(TgAccess.getSelectedAccount()).size();
    }

    /**
     * Managed chats that stay visible while hiding is on.
     */
    public static int getVisibleCount() {
        if (!HideChatsConfig.load()) return 0;
        return HideChatsConfig.getVisible(TgAccess.getSelectedAccount()).size();
    }

    /**
     * Picks the chats the feature is allowed to hide, out of every chat of the account.
     */
    public static void openManagedPicker(Activity activity, Runnable onChanged) {
        openPicker(activity, onChanged, true);
    }

    /**
     * Picks which of the managed chats stay visible; the rest of them are hidden.
     */
    public static void openVisiblePicker(Activity activity, Runnable onChanged) {
        openPicker(activity, onChanged, false);
    }

    private static void openPicker(Activity activity, Runnable onChanged, boolean managedList) {
        if (!HideChatsConfig.load()) return;
        int account = TgAccess.getSelectedAccount();
        if (TgAccess.getClientUserId(account) == 0) return;
        Set<Long> managed = HideChatsConfig.getManaged(account);
        ChatPicker.show(activity, account,
                managedList ? managed : HideChatsConfig.getVisible(account),
                managedList ? null : managed,
                managedList ? ChatPicker.MODE_MANAGED : ChatPicker.MODE_VISIBLE,
                result -> {
                    if (managedList) {
                        HideChatsConfig.setManaged(account, result);
                    } else {
                        HideChatsConfig.setVisible(account, result);
                    }
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
        ChatGuardHooks.install();
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

    /**
     * Accounts that log in after "hide new chats" was switched on need their own snapshot, taken the
     * first time they are seen, or everything they have would count as new.
     */
    static void ensureKnownSnapshot(int account) {
        if (!HideChatsConfig.isHideNewChats()) return;
        runOnUiThread(() -> HideChatsConfig.captureKnown(account));
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

    /**
     * Hooks every overload of the method, for call sites that only read state back and so do not
     * care about the parameters R8 may have rewritten.
     */
    static boolean hookAll(Class<?> cls, String classKey, String method, BaseMethodHook callback) {
        if (cls == null) {
            Logger.w("HideChats: class for " + classKey + "#" + method + " not found");
            return false;
        }
        try {
            Set<de.robv.android.xposed.XC_MethodHook.Unhook> hooks =
                    XposedBridge.hookAllMethods(cls, Obfuscate.getMethodName(classKey, method), callback);
            if (hooks.isEmpty()) {
                Logger.w("HideChats: no method " + cls.getName() + "#" + method + " to hook");
                return false;
            }
            return true;
        } catch (Throwable t) {
            Logger.w("HideChats: unable to hook " + cls.getName() + "#" + method + ": " + t);
            return false;
        }
    }

    /**
     * Hooks the overloads of one signature of a method. R8 may rename each overload differently and
     * may turn a method into a static helper that takes its receiver as the first parameter, so the
     * name is resolved for that signature and every declared method carrying it is hooked.
     *
     * @param tail parameter types the real method must end with, empty to accept any
     */
    static boolean hookBySignature(Class<?> cls, String classKey, String method, String signature,
                                   BaseMethodHook callback, Class<?>... tail) {
        if (cls == null) {
            Logger.w("HideChats: class for " + classKey + "#" + method + " not found");
            return false;
        }
        String resolved = Obfuscate.getMethodName(classKey, method, signature);
        boolean hooked = false;
        try {
            for (Method candidate : cls.getDeclaredMethods()) {
                if (!candidate.getName().equals(resolved) || !endsWith(candidate.getParameterTypes(), tail)) continue;
                XposedBridge.hookMethod(candidate, callback);
                hooked = true;
            }
        } catch (Throwable t) {
            Logger.w("HideChats: unable to hook " + cls.getName() + "#" + method + ": " + t);
            return false;
        }
        if (!hooked) {
            Logger.w("HideChats: no method " + cls.getName() + "#" + resolved + " (" + method + ") to hook");
        }
        return hooked;
    }

    private static boolean endsWith(Class<?>[] parameterTypes, Class<?>[] tail) {
        if (parameterTypes.length < tail.length) return false;
        int offset = parameterTypes.length - tail.length;
        for (int i = 0; i < tail.length; i++) {
            if (tail[i] == null || parameterTypes[offset + i] != tail[i]) return false;
        }
        return true;
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
