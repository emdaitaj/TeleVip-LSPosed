package com.my.televip.features.hideChats;

import com.my.televip.Class.ClassNames;
import com.my.televip.base.BaseMethodHook;

import java.util.ArrayList;

/**
 * Story bubbles above the chat list and in the archive. StoriesController keeps its own (unfiltered)
 * fields; only what the UI reads through the getters is filtered.
 */
final class StoriesHooks {

    private static final String SWAP_CONTROLLER = "televipStoriesSwapController";
    private static final String SWAP_ORIGINAL = "televipStoriesSwapOriginal";

    private StoriesHooks() {}

    static void install() {
        Class<?> storiesController = TgAccess.findClass(ClassNames.STORIES_CONTROLLER);

        BaseMethodHook filterResult = new BaseMethodHook() {
            @Override
            protected void afterMethod(MethodHookParam param) {
                Object result = param.getResult();
                if (result instanceof ArrayList) {
                    //noinspection unchecked
                    param.setResult(filter(param.thisObject, (ArrayList<Object>) result));
                }
            }
        };
        HideChats.hook(storiesController, "StoriesController", "getDialogListStories", filterResult);
        HideChats.hook(storiesController, "StoriesController", "getHiddenList", filterResult);

        // In R8 builds both getters are inlined, so their callers read the backing lists straight from
        // the controller. The list is then swapped for a filtered copy for the duration of the call
        // that renders it, and put back afterwards, so Telegram keeps its own data.
        installDialogStoriesSwap();

        HideChats.hook(storiesController, "StoriesController", "hasStories", new BaseMethodHook() {
            @Override
            protected void afterMethod(MethodHookParam param) {
                if (!Boolean.TRUE.equals(param.getResult())) return;
                if (HideChatsConfig.filterFor(getAccount(param.thisObject)) == null) return;
                Object visible = TgAccess.call(param.thisObject, "StoriesController", "getDialogListStories");
                boolean hasVisible = visible instanceof ArrayList && !((ArrayList<?>) visible).isEmpty();
                if (!hasVisible && !TgAccess.boolValue(TgAccess.call(param.thisObject, "StoriesController", "hasSelfStories"), false)) {
                    param.setResult(false);
                }
            }
        });

        HideChats.hook(storiesController, "StoriesController", "getTotalStoriesCount", boolean.class, new BaseMethodHook() {
            @Override
            protected void afterMethod(MethodHookParam param) {
                if (!(param.getResult() instanceof Integer)) return;
                ArrayList<Object> all = (boolean) param.args[0]
                        ? TgAccess.getList(param.thisObject, "StoriesController", "hiddenListStories")
                        : TgAccess.getList(param.thisObject, "StoriesController", "dialogListStories");
                if (all == null) return;
                int removed = all.size() - filter(param.thisObject, all).size();
                if (removed > 0) {
                    param.setResult(Math.max(0, (int) param.getResult() - removed));
                }
            }
        });
    }

    static void refresh(int account) {
        TgAccess.postNotification(account, "storiesUpdated");
    }

    static boolean hasVisibleArchivedStories(Object messagesController) {
        Object storiesController = TgAccess.call(messagesController, "MessagesController", "getStoriesController");
        if (storiesController == null) return false;
        Object hidden = TgAccess.call(storiesController, "StoriesController", "getHiddenList");
        if (!(hidden instanceof ArrayList)) {
            // the getter is inlined away in R8 builds
            hidden = TgAccess.getList(storiesController, "StoriesController", "hiddenListStories");
            if (!(hidden instanceof ArrayList)) return false;
            //noinspection unchecked
            hidden = filter(storiesController, (ArrayList<Object>) hidden);
        }
        return !((ArrayList<?>) hidden).isEmpty();
    }

    /**
     * Filters dialogListStories while the chat list story row rebuilds itself, and restores it right
     * after, including when that method throws. Every class and member key is spelled out: the
     * mapping generator only picks up literal pairs, so a name passed in a variable would silently
     * resolve to the unobfuscated one and the swap would never happen.
     */
    private static void installDialogStoriesSwap() {
        HideChats.hookAll(TgAccess.findClass(ClassNames.DIALOG_STORIES_CELL), "DialogStoriesCell", "updateItems", new BaseMethodHook() {
            @Override
            protected void beforeMethod(MethodHookParam param) {
                int account = TgAccess.intValue(TgAccess.get(param.thisObject, "DialogStoriesCell", "currentAccount"), -1);
                Object controller = storiesControllerFor(account);
                if (controller == null) return;
                ArrayList<Object> all = TgAccess.getList(controller, "StoriesController", "dialogListStories");
                if (all == null) return;
                ArrayList<Object> visible = filter(controller, all);
                if (visible == all) return;
                param.setObjectExtra(SWAP_CONTROLLER, controller);
                param.setObjectExtra(SWAP_ORIGINAL, all);
                TgAccess.set(controller, "StoriesController", "dialogListStories", visible);
            }

            @Override
            protected void afterMethod(MethodHookParam param) {
                Object controller = param.getObjectExtra(SWAP_CONTROLLER);
                Object original = param.getObjectExtra(SWAP_ORIGINAL);
                if (controller != null && original != null) {
                    TgAccess.set(controller, "StoriesController", "dialogListStories", original);
                }
            }
        });
    }

    private static Object storiesControllerFor(int account) {
        Object messagesController = TgAccess.getMessagesController(account);
        return messagesController == null ? null : TgAccess.call(messagesController, "MessagesController", "getStoriesController");
    }

    private static int getAccount(Object storiesController) {
        return TgAccess.intValue(TgAccess.get(storiesController, "StoriesController", "currentAccount"), -1);
    }

    /**
     * Returns the same list when nothing is hidden, otherwise a filtered copy.
     */
    private static ArrayList<Object> filter(Object storiesController, ArrayList<Object> stories) {
        HiddenFilter filter = HideChatsConfig.filterFor(getAccount(storiesController));
        if (filter == null || stories.isEmpty()) return stories;
        ArrayList<Object> result = null;
        for (int i = 0, size = stories.size(); i < size; i++) {
            Object peerStories = stories.get(i);
            long dialogId = TgAccess.getPeerDialogId(TgAccess.get(peerStories, "TL_stories$PeerStories", "peer"));
            boolean hidden = dialogId != 0 && dialogId != filter.data.userId && filter.isHidden(dialogId);
            if (hidden) {
                if (result == null) result = new ArrayList<>(stories.subList(0, i));
            } else if (result != null) {
                result.add(peerStories);
            }
        }
        return result == null ? stories : result;
    }
}
