package com.my.televip.features.hideChats;

import com.my.televip.Class.ClassNames;
import com.my.televip.base.BaseMethodHook;

import java.util.ArrayList;

/**
 * Story bubbles above the chat list and in the archive. StoriesController keeps its own (unfiltered)
 * fields; only what the UI reads through the getters is filtered.
 */
final class StoriesHooks {

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
                String field = (boolean) param.args[0] ? "hiddenListStories" : "dialogListStories";
                ArrayList<Object> all = TgAccess.getList(param.thisObject, "StoriesController", field);
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
        Object hidden = TgAccess.call(storiesController, "StoriesController", "getHiddenList");
        return hidden instanceof ArrayList && !((ArrayList<?>) hidden).isEmpty();
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
