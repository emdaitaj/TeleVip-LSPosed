package com.my.televip.features.hideChats;

import android.content.Context;

import com.my.televip.Class.ClassNames;
import com.my.televip.base.BaseMethodHook;
import com.my.televip.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;

import de.robv.android.xposed.XposedHelpers;

/**
 * Contacts screen (sections, mutual sections, "sort by last seen"), its search and the
 * contact lists of New Group / New Call / privacy pickers.
 */
final class ContactsHooks {

    private static final String KEY_SECTIONS_FILTERED = "televipHideChatsSectionsFiltered";

    private ContactsHooks() {}

    static void install() {
        HideChats.hook(TgAccess.findClass(ClassNames.CONTACTS_CONTROLLER), "ContactsController", "buildContactsSectionsArrays", boolean.class, new BaseMethodHook() {
            @Override
            protected void afterMethod(MethodHookParam param) {
                onSectionsRebuilt(param.thisObject);
            }
        });

        HideChats.hook(TgAccess.findClass(ClassNames.CONTACTS_ADAPTER), "ContactsAdapter", "sortOnlineContacts", new BaseMethodHook() {
            @Override
            protected void beforeMethod(MethodHookParam param) {
                int account = TgAccess.intValue(TgAccess.get(param.thisObject, "ContactsAdapter", "currentAccount"), TgAccess.getSelectedAccount());
                HiddenFilter filter = HideChatsConfig.filterFor(account);
                if (filter == null) return;
                ArrayList<Object> onlineContacts = TgAccess.getList(param.thisObject, "ContactsAdapter", "onlineContacts");
                if (onlineContacts != null) {
                    onlineContacts.removeIf(contact -> filter.isHidden(TgAccess.getContactUserId(contact)));
                }
            }
        });

        if (TgAccess.findClass(ClassNames.CONTACTS_CONTROLLER_CONTACT) != null) {
            HideChats.hook(TgAccess.findClass(ClassNames.SEARCH_ADAPTER), "SearchAdapter", "updateSearchResults", int.class, ArrayList.class, ArrayList.class, ArrayList.class, new BaseMethodHook() {
                @Override
                @SuppressWarnings("unchecked")
                protected void beforeMethod(MethodHookParam param) {
                    // SearchAdapter has no account of its own: it searches UserConfig.selectedAccount too
                    HiddenFilter filter = HideChatsConfig.filterFor(TgAccess.getSelectedAccount());
                    if (filter == null) return;
                    SearchHooks.removeHiddenResults(filter, (ArrayList<Object>) param.args[1], (ArrayList<Object>) param.args[2]);
                }
            });
        }

        // New group / new call, and folder include-exclude / privacy exception pickers.
        hookPickerAdapter(ClassNames.GROUP_CREATE_ADAPTER, ClassNames.GROUP_CREATE_ACTIVITY);
        hookPickerAdapter(ClassNames.USERS_SELECT_ADAPTER, ClassNames.USERS_SELECT_ACTIVITY);
    }

    /**
     * Both adapters are inner classes (outer fragment = first constructor parameter) that build a
     * "contacts" list of users / chats (plus section letters) in their constructor.
     */
    private static void hookPickerAdapter(String adapterClassName, String fragmentClassName) {
        Class<?> adapterClass = TgAccess.findClass(adapterClassName);
        Class<?> fragmentClass = TgAccess.findClass(fragmentClassName);
        if (adapterClass == null || fragmentClass == null) return;
        try {
            XposedHelpers.findAndHookConstructor(adapterClass, fragmentClass, Context.class, new BaseMethodHook() {
                @Override
                protected void afterMethod(MethodHookParam param) {
                    filterPickerContacts(param.thisObject);
                }
            });
        } catch (Throwable t) {
            Logger.w("HideChats: unable to hook " + adapterClassName + ": " + t);
        }
    }

    static void refresh(int account) {
        Object contactsController = TgAccess.getContactsController(account);
        if (contactsController == null) return;
        TgAccess.callWithTypes(contactsController, "ContactsController", "buildContactsSectionsArrays", new Class[]{boolean.class}, false);
        TgAccess.postNotification(account, "contactsDidLoad");
    }

    /**
     * contactsDidLoad is posted right after Telegram replaces the section maps with fresh ones.
     */
    static void onContactsLoaded(int account) {
        Object contactsController = TgAccess.getContactsController(account);
        if (contactsController != null) {
            onSectionsRebuilt(contactsController);
        }
    }

    private static void onSectionsRebuilt(Object contactsController) {
        int account = TgAccess.getAccount(contactsController);
        HiddenFilter filter = HideChatsConfig.filterFor(account);
        if (filter == null) {
            if (Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(contactsController, KEY_SECTIONS_FILTERED))) {
                // the mutual sections are not rebuilt by buildContactsSectionsArrays: restore them too
                rebuildSections(contactsController, null);
                XposedHelpers.setAdditionalInstanceField(contactsController, KEY_SECTIONS_FILTERED, false);
            }
            return;
        }
        rebuildSections(contactsController, filter);
        XposedHelpers.setAdditionalInstanceField(contactsController, KEY_SECTIONS_FILTERED, true);
    }

    /**
     * Rebuilds the (all / mutual) section maps from usersSectionsDict, which Telegram has just
     * rebuilt from the complete contact list, dropping hidden users.
     */
    @SuppressWarnings("unchecked")
    private static void rebuildSections(Object contactsController, HiddenFilter filter) {
        Object dictValue = TgAccess.get(contactsController, "ContactsController", "usersSectionsDict");
        Object sortedValue = TgAccess.get(contactsController, "ContactsController", "sortedUsersSectionsArray");
        if (!(dictValue instanceof HashMap) || !(sortedValue instanceof ArrayList)) return;
        HashMap<String, ArrayList<Object>> dict = (HashMap<String, ArrayList<Object>>) dictValue;
        ArrayList<String> sorted = (ArrayList<String>) sortedValue;
        Object messagesController = TgAccess.getMessagesController(TgAccess.getAccount(contactsController));

        HashMap<String, ArrayList<Object>> visibleDict = new HashMap<>();
        ArrayList<String> visibleSorted = new ArrayList<>();
        HashMap<String, ArrayList<Object>> mutualDict = new HashMap<>();
        ArrayList<String> mutualSorted = new ArrayList<>();
        boolean snapshotChanged = false;

        for (int i = 0, size = sorted.size(); i < size; i++) {
            String key = sorted.get(i);
            ArrayList<Object> contacts = dict.get(key);
            if (contacts == null) continue;
            ArrayList<Object> visible = new ArrayList<>(contacts.size());
            ArrayList<Object> visibleMutual = new ArrayList<>();
            for (int j = 0, count = contacts.size(); j < count; j++) {
                Object contact = contacts.get(j);
                long userId = TgAccess.getContactUserId(contact);
                if (filter != null) {
                    if (filter.observeContact(userId)) snapshotChanged = true;
                    if (filter.isHidden(userId)) continue;
                }
                visible.add(contact);
                Object user = TgAccess.getUser(messagesController, userId);
                if (TgAccess.boolValue(TgAccess.get(user, "TLRPC$User", "mutual_contact"), false)) {
                    visibleMutual.add(contact);
                }
            }
            if (!visible.isEmpty()) {
                visibleDict.put(key, visible);
                visibleSorted.add(key);
            }
            if (!visibleMutual.isEmpty()) {
                mutualDict.put(key, visibleMutual);
                mutualSorted.add(key);
            }
        }
        if (snapshotChanged) {
            HideChatsConfig.markDirty(filter.data);
        }
        if (filter != null) {
            TgAccess.set(contactsController, "ContactsController", "usersSectionsDict", visibleDict);
            TgAccess.set(contactsController, "ContactsController", "sortedUsersSectionsArray", visibleSorted);
        }
        TgAccess.set(contactsController, "ContactsController", "usersMutualSectionsDict", mutualDict);
        TgAccess.set(contactsController, "ContactsController", "sortedUsersMutualSectionsArray", mutualSorted);
    }

    private static void filterPickerContacts(Object adapter) {
        Object fragment = TgAccess.get(adapter, "GroupCreateAdapter", "this$0");
        int account = TgAccess.intValue(TgAccess.get(fragment, "BaseFragment", "currentAccount"), TgAccess.getSelectedAccount());
        HiddenFilter filter = HideChatsConfig.filterFor(account);
        if (filter == null) return;
        ArrayList<Object> contacts = TgAccess.getList(adapter, "GroupCreateAdapter", "contacts");
        if (contacts == null || contacts.isEmpty()) return;
        Class<?> letterClass = TgAccess.findClass(ClassNames.TL_CONTACT);
        boolean removed = contacts.removeIf(object -> {
            long dialogId = TgAccess.getObjectDialogId(object);
            return dialogId != 0 && filter.isHidden(dialogId);
        });
        if (!removed || letterClass == null) return;
        // Section letters are TL_contact subclasses; drop the ones left without entries.
        for (int i = contacts.size() - 1; i >= 0; i--) {
            if (!letterClass.isInstance(contacts.get(i))) continue;
            boolean last = i == contacts.size() - 1;
            if (last || letterClass.isInstance(contacts.get(i + 1))) {
                contacts.remove(i);
            }
        }
    }
}
