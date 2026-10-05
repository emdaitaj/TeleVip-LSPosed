package com.my.televip.features.hideChats;

import android.util.SparseArray;

import com.my.televip.Class.ClassNames;
import com.my.televip.base.BaseMethodHook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.function.Predicate;

import de.robv.android.xposed.XposedHelpers;

/**
 * Chat lists: all folders / filter tabs, the archive entry, forward & "choose chat" pickers,
 * the main list adapter and the share sheet.
 */
final class DialogsHooks {

    private static final String KEY_ARCHIVE_SUPPRESSED = "televipHideChatsArchiveSuppressed";
    private static final String EXTRA_FOLDER_SWAP = "televipHideChatsFolderSwap";

    private static final String[] DIALOG_LISTS = {
            "dialogsForward", "dialogsServerOnly", "dialogsCanAddUsers", "dialogsMyChannels", "dialogsMyGroups",
            "dialogsChannelsOnly", "dialogsUsersOnly", "dialogsForBlock", "dialogsGroupsOnly"
    };

    // DialogsAdapter.VIEW_TYPE_* and DialogsEmptyCell.TYPE_* (stable across 12.x)
    private static final int VIEW_TYPE_EMPTY = 5;
    private static final int VIEW_TYPE_HEADER = 7;
    private static final int VIEW_TYPE_SHADOW = 8;
    private static final int VIEW_TYPE_GRAY_SECTION = 20;
    private static final int EMPTY_TYPE_WELCOME_NO_CONTACTS = 0;
    private static final int EMPTY_TYPE_WELCOME_WITH_CONTACTS = 1;

    private DialogsHooks() {}

    /**
     * Stand-in for a folder list while pinning: sortDialogs() rebuilds every folder list in place
     * (clear + refill), which is how a rebuild is told apart from an early return.
     */
    private static final class PinnedOnlyList extends ArrayList<Object> {
        boolean rebuilt;

        PinnedOnlyList(ArrayList<Object> pinned) {
            super(pinned);
        }

        @Override
        public void clear() {
            rebuilt = true;
            super.clear();
        }
    }

    static void install() {
        Class<?> messagesController = TgAccess.findClass(ClassNames.MESSAGES_CONTROLLER);
        Class<?> longSparseArray = TgAccess.findClass(ClassNames.LONG_SPARES_ARRAY);
        Class<?> inputPeer = TgAccess.findClass(ClassNames.TLRPC_INPUT_PEER);

        HideChats.hook(messagesController, "MessagesController", "sortDialogs", longSparseArray, new BaseMethodHook() {
            @Override
            protected void afterMethod(MethodHookParam param) {
                filterDialogLists(param.thisObject);
            }
        });

        HideChats.hook(messagesController, "MessagesController", "hasHiddenArchive", new BaseMethodHook() {
            @Override
            protected void afterMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(param.getResult()) && isArchiveSuppressed(param.thisObject)) {
                    param.setResult(false);
                }
            }
        });

        // Pinning computes the new pinnedNum from getDialogs(folderId); hidden pinned chats must count too.
        if (inputPeer != null) {
            HideChats.hook(messagesController, "MessagesController", "pinDialog", long.class, boolean.class, inputPeer, long.class, new BaseMethodHook() {
                @Override
                protected void beforeMethod(MethodHookParam param) {
                    if (!(boolean) param.args[1]) return;
                    Object dialog = getDialog(param.thisObject, (long) param.args[0]);
                    if (dialog == null || TgAccess.isDialogPinned(dialog)) return;
                    swapInPinnedList(param, TgAccess.getDialogFolderId(dialog));
                }

                @Override
                protected void afterMethod(MethodHookParam param) {
                    restoreFolderList(param);
                }
            });
        }

        // reorderPinnedDialogs sends "force" orders built from getDialogs(folderId): without the hidden
        // pinned chats the server would unpin them.
        HideChats.hook(messagesController, "MessagesController", "reorderPinnedDialogs", int.class, ArrayList.class, long.class, new BaseMethodHook() {
            @Override
            protected void beforeMethod(MethodHookParam param) {
                if ((long) param.args[2] != 0) return;
                swapInPinnedList(param, (int) param.args[0]);
            }

            @Override
            protected void afterMethod(MethodHookParam param) {
                restoreFolderList(param);
            }
        });

        HideChats.hook(TgAccess.findClass(ClassNames.DIALOGS_ADAPTER), "DialogsAdapter", "updateItemList", new BaseMethodHook() {
            @Override
            protected void afterMethod(MethodHookParam param) {
                filterAdapterItems(param.thisObject);
            }
        });

        HideChats.hook(TgAccess.findClass(ClassNames.SHARE_DIALOGS_ADAPTER), "ShareAlert$ShareDialogsAdapter", "fetchDialogs", new BaseMethodHook() {
            @Override
            protected void afterMethod(MethodHookParam param) {
                filterShareDialogs(param.thisObject);
            }
        });

        // Long press on "share": quick share row built from top peers + all chats (ids)
        HideChats.hook(TgAccess.findClass(ClassNames.QUICK_SHARE_SELECTOR), "QuickShareSelectorOverlayLayout", "fetchDialogs", new BaseMethodHook() {
            @Override
            protected void afterMethod(MethodHookParam param) {
                HiddenFilter filter = HideChatsConfig.filterFor(TgAccess.intValue(TgAccess.get(param.thisObject, "QuickShareSelectorOverlayLayout", "currentAccount"), TgAccess.getSelectedAccount()));
                if (filter == null) return;
                ArrayList<Object> dialogIds = TgAccess.getList(param.thisObject, "QuickShareSelectorOverlayLayout", "dialogs");
                if (dialogIds != null) {
                    dialogIds.removeIf(id -> id instanceof Long && filter.isHidden((Long) id));
                }
            }
        });

        // Search > Channels tab: "my channels" are taken from all chats
        HideChats.hook(TgAccess.findClass(ClassNames.DIALOGS_CHANNELS_ADAPTER), "DialogsChannelsAdapter", "updateMyChannels", new BaseMethodHook() {
            @Override
            protected void afterMethod(MethodHookParam param) {
                HiddenFilter filter = HideChatsConfig.filterFor(TgAccess.intValue(TgAccess.get(param.thisObject, "DialogsChannelsAdapter", "currentAccount"), TgAccess.getSelectedAccount()));
                if (filter == null) return;
                ArrayList<Object> channels = TgAccess.getList(param.thisObject, "DialogsChannelsAdapter", "myChannels");
                if (channels != null) {
                    channels.removeIf(chat -> filter.isHidden(TgAccess.getObjectDialogId(chat)));
                }
            }
        });
    }

    static void refresh(int account) {
        Object messagesController = TgAccess.getMessagesController(account);
        if (messagesController == null) return;
        Class<?> longSparseArray = TgAccess.findClass(ClassNames.LONG_SPARES_ARRAY);
        // the after hook filters even when sortDialogs bails out early (interface paused)
        TgAccess.callWithTypes(messagesController, "MessagesController", "sortDialogs", new Class[]{longSparseArray}, (Object) null);
        TgAccess.postNotification(account, "dialogsNeedReload");
    }

    /**
     * Filters the lists built before the hooks existed (process started by a push), without re-sorting.
     */
    static void filterNow(int account) {
        Object messagesController = TgAccess.getMessagesController(account);
        if (messagesController != null) filterDialogLists(messagesController);
    }

    private static void filterDialogLists(Object messagesController) {
        int account = TgAccess.getAccount(messagesController);
        HiddenFilter filter = HideChatsConfig.filterFor(account);
        if (filter == null) {
            setArchiveSuppressed(messagesController, false);
            return;
        }
        ArrayList<Object> allDialogs = TgAccess.getAllDialogs(messagesController);
        if (allDialogs == null) return;

        IdentityHashMap<Object, Boolean> hiddenCache = new IdentityHashMap<>();
        boolean snapshotChanged = false;
        int archived = 0;
        int visibleArchived = 0;
        for (int i = 0, size = allDialogs.size(); i < size; i++) {
            Object dialog = allDialogs.get(i);
            if (dialog == null || TgAccess.isDialogFolder(dialog)) continue;
            long dialogId = TgAccess.getDialogId(dialog);
            if (filter.observeDialog(dialogId, TgAccess.getDialogLastMessageDate(dialog))) {
                snapshotChanged = true;
            }
            boolean isHidden = filter.isHidden(dialogId);
            hiddenCache.put(dialog, isHidden);
            if (TgAccess.getDialogFolderId(dialog) == 1) {
                archived++;
                if (!isHidden) visibleArchived++;
            }
        }
        if (snapshotChanged) {
            HideChatsConfig.markDirty(filter.data);
            // the storage thread may have counted these chats as unread before they were classified
            HideChats.scheduleCountersRefresh(account);
        }

        Predicate<Object> hidden = dialog -> {
            if (dialog == null || TgAccess.isDialogFolder(dialog)) return false;
            Boolean cached = hiddenCache.get(dialog);
            if (cached == null) {
                cached = filter.isHidden(TgAccess.getDialogId(dialog));
                hiddenCache.put(dialog, cached);
            }
            return cached;
        };

        SparseArray<ArrayList<Object>> dialogsByFolder = TgAccess.getDialogsByFolder(messagesController);
        if (dialogsByFolder != null) {
            for (int i = 0; i < dialogsByFolder.size(); i++) {
                ArrayList<Object> list = dialogsByFolder.valueAt(i);
                if (list != null) list.removeIf(hidden);
            }
        }

        for (String name : DIALOG_LISTS) {
            ArrayList<Object> list = TgAccess.getList(messagesController, "MessagesController", name);
            if (list != null) list.removeIf(hidden);
        }

        Object selectedFilters = TgAccess.get(messagesController, "MessagesController", "selectedDialogFilter");
        if (selectedFilters instanceof Object[]) {
            for (Object dialogFilter : (Object[]) selectedFilters) {
                if (dialogFilter == null) continue;
                ArrayList<Object> dialogs = TgAccess.getList(dialogFilter, "MessagesController$DialogFilter", "dialogs");
                if (dialogs != null) dialogs.removeIf(hidden);
                ArrayList<Object> dialogsForward = TgAccess.getList(dialogFilter, "MessagesController$DialogFilter", "dialogsForward");
                if (dialogsForward != null) dialogsForward.removeIf(hidden);
            }
        }

        // Communities (newer clients): LongSparseArray<ArrayList<Dialog>>
        Object byCommunity = TgAccess.get(messagesController, "MessagesController", "dialogsByCommunity");
        if (byCommunity != null) {
            int size = TgAccess.intValue(TgAccess.call(byCommunity, "LongSparseArray", "size"), 0);
            for (int i = 0; i < size; i++) {
                Object list = TgAccess.call(byCommunity, "LongSparseArray", "valueAt", i);
                if (list instanceof ArrayList) {
                    //noinspection unchecked
                    ((ArrayList<Object>) list).removeIf(hidden);
                }
            }
        }

        // Archive whose chats are all hidden: drop its entry from the main list (it would reveal names,
        // counters and the last message) unless it still shows visible archived stories.
        boolean suppressArchive = archived > 0 && visibleArchived == 0 && !StoriesHooks.hasVisibleArchivedStories(messagesController);
        if (suppressArchive && dialogsByFolder != null) {
            ArrayList<Object> main = dialogsByFolder.get(0);
            if (main != null) {
                main.removeIf(dialog -> TgAccess.isDialogFolder(dialog) && TgAccess.getFolderEntryId(dialog) == 1);
            }
        }
        setArchiveSuppressed(messagesController, suppressArchive);
    }

    private static boolean isArchiveSuppressed(Object messagesController) {
        return Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(messagesController, KEY_ARCHIVE_SUPPRESSED));
    }

    private static void setArchiveSuppressed(Object messagesController, boolean value) {
        if (value || isArchiveSuppressed(messagesController)) {
            XposedHelpers.setAdditionalInstanceField(messagesController, KEY_ARCHIVE_SUPPRESSED, value);
        }
    }

    private static Object getDialog(Object messagesController, long dialogId) {
        return TgAccess.call(TgAccess.getDialogsDict(messagesController), "LongSparseArray", "get", dialogId);
    }

    /**
     * Temporarily replaces getDialogs(folderId) with every pinned chat of that folder (hidden ones
     * included) in pinned order.
     */
    private static void swapInPinnedList(de.robv.android.xposed.XC_MethodHook.MethodHookParam param, int folderId) {
        Object messagesController = param.thisObject;
        HiddenFilter filter = HideChatsConfig.filterFor(TgAccess.getAccount(messagesController));
        if (filter == null) return;
        SparseArray<ArrayList<Object>> dialogsByFolder = TgAccess.getDialogsByFolder(messagesController);
        ArrayList<Object> allDialogs = TgAccess.getAllDialogs(messagesController);
        if (dialogsByFolder == null || allDialogs == null) return;

        ArrayList<Object> pinned = new ArrayList<>();
        boolean anyHidden = false;
        for (int i = 0, size = allDialogs.size(); i < size; i++) {
            Object dialog = allDialogs.get(i);
            if (dialog == null || TgAccess.isDialogFolder(dialog) || !TgAccess.isDialogPinned(dialog)) continue;
            if (TgAccess.getDialogFolderId(dialog) != folderId) continue;
            pinned.add(dialog);
            if (!anyHidden && filter.isHidden(TgAccess.getDialogId(dialog))) anyHidden = true;
        }
        if (!anyHidden) return;
        Collections.sort(pinned, (a, b) -> Integer.compare(TgAccess.getDialogPinnedNum(b), TgAccess.getDialogPinnedNum(a)));

        ArrayList<Object> previous = dialogsByFolder.get(folderId);
        PinnedOnlyList swapped = new PinnedOnlyList(pinned);
        dialogsByFolder.put(folderId, swapped);
        param.setObjectExtra(EXTRA_FOLDER_SWAP, new Object[]{folderId, previous, swapped});
    }

    /**
     * Puts the original folder list back (screens may hold on to it). When the call re-sorted the
     * dialogs (pinDialog does unless the interface is paused), the stand-in holds the fresh, filtered
     * folder content: it is copied over first.
     */
    @SuppressWarnings("unchecked")
    private static void restoreFolderList(de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
        Object extra = param.getObjectExtra(EXTRA_FOLDER_SWAP);
        if (!(extra instanceof Object[])) return;
        Object[] swap = (Object[]) extra;
        int folderId = (int) swap[0];
        ArrayList<Object> previous = (ArrayList<Object>) swap[1];
        PinnedOnlyList swapped = (PinnedOnlyList) swap[2];
        SparseArray<ArrayList<Object>> dialogsByFolder = TgAccess.getDialogsByFolder(param.thisObject);
        if (dialogsByFolder == null || dialogsByFolder.get(folderId) != swapped) return;
        if (previous == null) {
            if (swapped.rebuilt) {
                dialogsByFolder.put(folderId, new ArrayList<>(swapped));
            } else {
                dialogsByFolder.remove(folderId);
            }
            return;
        }
        if (swapped.rebuilt) {
            previous.clear();
            previous.addAll(swapped);
        }
        dialogsByFolder.put(folderId, previous);
    }

    private static void filterAdapterItems(Object adapter) {
        int account = TgAccess.intValue(TgAccess.get(adapter, "DialogsAdapter", "currentAccount"), -1);
        HiddenFilter filter = HideChatsConfig.filterFor(account);
        if (filter == null) return;

        ArrayList<Object> onlineContacts = TgAccess.getList(adapter, "DialogsAdapter", "onlineContacts");
        if (onlineContacts != null) {
            onlineContacts.removeIf(contact -> filter.isHidden(TgAccess.getContactUserId(contact)));
            if (onlineContacts.isEmpty()) {
                TgAccess.set(adapter, "DialogsAdapter", "onlineContacts", null);
            }
        }

        ArrayList<Object> items = TgAccess.getList(adapter, "DialogsAdapter", "itemInternals");
        if (items == null || items.isEmpty()) return;

        ArrayList<Object> kept = new ArrayList<>(items.size());
        boolean removedDialog = false;
        boolean hadContacts = false;
        boolean keptContacts = false;
        int contactsHeaderIndex = -1;
        for (int i = 0, size = items.size(); i < size; i++) {
            Object item = items.get(i);
            Object dialog = TgAccess.get(item, "DialogsAdapter$ItemInternal", "dialog");
            if (dialog != null && !TgAccess.isDialogFolder(dialog) && filter.isHidden(TgAccess.getDialogId(dialog))) {
                removedDialog = true;
                continue;
            }
            Object contact = TgAccess.get(item, "DialogsAdapter$ItemInternal", "contact");
            if (contact != null) {
                hadContacts = true;
                if (filter.isHidden(TgAccess.getContactUserId(contact))) continue;
                keptContacts = true;
            } else if (!hadContacts && viewType(item) == VIEW_TYPE_HEADER) {
                contactsHeaderIndex = kept.size();
            }
            kept.add(item);
        }
        if (hadContacts && !keptContacts) {
            if (contactsHeaderIndex >= 0) {
                kept.remove(contactsHeaderIndex);
                if (contactsHeaderIndex > 0 && viewType(kept.get(contactsHeaderIndex - 1)) == VIEW_TYPE_SHADOW) {
                    kept.remove(contactsHeaderIndex - 1);
                }
            }
            // the empty placeholder was configured to point at the (now hidden) contacts
            for (Object item : kept) {
                if (viewType(item) == VIEW_TYPE_EMPTY
                        && TgAccess.intValue(TgAccess.get(item, "DialogsAdapter$ItemInternal", "emptyType"), -1) == EMPTY_TYPE_WELCOME_WITH_CONTACTS) {
                    TgAccess.set(item, "DialogsAdapter$ItemInternal", "emptyType", EMPTY_TYPE_WELCOME_NO_CONTACTS);
                }
            }
        }
        // A hidden "reply to"/"forward from" chat leaves its two gray separators behind.
        for (int i = kept.size() - 1; removedDialog && i > 0; i--) {
            if (viewType(kept.get(i)) == VIEW_TYPE_GRAY_SECTION && viewType(kept.get(i - 1)) == VIEW_TYPE_GRAY_SECTION) {
                kept.remove(i);
                kept.remove(i - 1);
                i--;
            }
        }
        if (kept.size() != items.size()) {
            items.clear();
            items.addAll(kept);
        }
    }

    private static int viewType(Object item) {
        return TgAccess.intValue(TgAccess.get(item, "AdapterWithDiffUtils$Item", "viewType"), -1);
    }

    private static void filterShareDialogs(Object adapter) {
        Object shareAlert = TgAccess.get(adapter, "ShareAlert$ShareDialogsAdapter", "this$0");
        int account = TgAccess.intValue(TgAccess.get(shareAlert, "ShareAlert", "currentAccount"), TgAccess.getSelectedAccount());
        HiddenFilter filter = HideChatsConfig.filterFor(account);
        if (filter == null) return;
        ArrayList<Object> dialogs = TgAccess.getList(adapter, "ShareAlert$ShareDialogsAdapter", "dialogs");
        if (dialogs == null) return;
        Object dialogsMap = TgAccess.get(adapter, "ShareAlert$ShareDialogsAdapter", "dialogsMap");
        boolean removed = dialogs.removeIf(dialog -> {
            if (dialog == null || TgAccess.isDialogFolder(dialog)) return false;
            long dialogId = TgAccess.getDialogId(dialog);
            if (!filter.isHidden(dialogId)) return false;
            if (dialogsMap != null) TgAccess.call(dialogsMap, "LongSparseArray", "remove", dialogId);
            return true;
        });
        if (removed) {
            TgAccess.call(adapter, "RecyclerListView", "notifyDataSetChanged");
        }
    }
}
