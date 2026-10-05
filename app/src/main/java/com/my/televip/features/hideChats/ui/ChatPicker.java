package com.my.televip.features.hideChats.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.my.televip.Class.ClassLoad;
import com.my.televip.Class.ClassNames;
import com.my.televip.Drawable.ArrowDrawable;
import com.my.televip.application.AndroidUtilities;
import com.my.televip.features.hideChats.ChatEntry;
import com.my.televip.features.hideChats.ChatPickerSource;
import com.my.televip.language.Keys;
import com.my.televip.language.Translator;
import com.my.televip.logging.Logger;
import com.my.televip.ui.ThemeColors;
import com.my.televip.ui.toolBar.MainToolBar;
import com.my.televip.utils.Utils;
import com.my.televip.virtuals.ActionBar.Theme;
import com.my.televip.virtuals.messenger.LocaleController;
import com.my.televip.virtuals.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XposedHelpers;

/**
 * Full screen chat selector shown on top of the TeleVip settings page.
 * Changes are applied when it is closed (toolbar arrow or system back).
 */
public class ChatPicker {

    public interface Callback {
        void onDone(Set<Long> selected);
    }

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_CHAT = 1;

    private static ChatPicker current;

    private static boolean avatarClassesResolved;
    private static Class<?> backupImageViewClass;
    private static Class<?> avatarDrawableClass;
    private static Class<?> tlObjectClass;

    private final Activity activity;
    private final int account;
    private final boolean showOnlyMode;
    private final Callback callback;
    private final Set<Long> initialSelection;
    private final Set<Long> selection;
    private final ArrayList<ChatEntry> entries = new ArrayList<>();
    private final ArrayList<Object> rows = new ArrayList<>();
    private final RowsAdapter adapter = new RowsAdapter();
    private String query = "";

    private LinearLayout root;
    private TextView clearButton;
    private TextView hintView;
    private EditText searchField;

    private ChatPicker(Activity activity, int account, Set<Long> selection, boolean showOnlyMode, Callback callback) {
        this.activity = activity;
        this.account = account;
        this.showOnlyMode = showOnlyMode;
        this.callback = callback;
        this.initialSelection = new HashSet<>(selection);
        this.selection = new HashSet<>(selection);
    }

    public static void show(Activity activity, int account, Set<Long> selection, boolean showOnlyMode, Callback callback) {
        if (activity == null) return;
        if (current != null) current.close(false);
        ChatPicker picker = new ChatPicker(activity, account, selection, showOnlyMode, callback);
        try {
            picker.open();
            current = picker;
        } catch (Throwable t) {
            Logger.e(t);
            picker.detach();
        }
    }

    /**
     * Back press handling: closes (and applies) the picker if it is showing.
     */
    public static boolean dismissCurrent() {
        ChatPicker picker = current;
        if (picker == null) return false;
        if (!picker.isShowing()) {
            // its activity went away without closing it
            current = null;
            return false;
        }
        picker.close(true);
        return true;
    }

    private boolean isShowing() {
        return root != null && root.isAttachedToWindow() && !activity.isFinishing();
    }

    private void open() {
        Context context = activity;
        boolean rtl = LocaleController.isRTL();

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ThemeColors.getBackgroundGrayColor());
        root.setClickable(true);
        root.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);

        MainToolBar toolbar = new MainToolBar(context);
        toolbar.setColorTitle(ThemeColors.getTextToolBarColor());
        toolbar.setRippleColor(ThemeColors.getToolBarRippleColor());
        toolbar.setTextTitle(Translator.get(showOnlyMode ? Keys.HideChatsPickerTitleShow : Keys.HideChatsPickerTitleHide));
        toolbar.setImageDrawable(new ArrowDrawable());
        toolbar.getImage().setOnClickListener(v -> close(true));

        clearButton = new TextView(context);
        clearButton.setText(Translator.get(Keys.HideChatsClear));
        clearButton.setTextColor(ThemeColors.getTextBlueColor());
        clearButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        clearButton.setTypeface(Typeface.DEFAULT_BOLD);
        clearButton.setGravity(Gravity.CENTER);
        clearButton.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        clearButton.setOnClickListener(v -> {
            selection.clear();
            adapter.notifyDataSetChanged();
            updateHeader();
        });
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clearParams.gravity = Gravity.CENTER_VERTICAL;
        toolbar.addView(clearButton, clearParams);
        root.addView(toolbar);

        LinearLayout searchContainer = new LinearLayout(context);
        searchContainer.setBackgroundColor(ThemeColors.getToolBarColor());
        searchContainer.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(4), AndroidUtilities.dp(14), AndroidUtilities.dp(10));
        searchField = new EditText(context);
        searchField.setSingleLine(true);
        searchField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        searchField.setHint(Translator.get(Keys.HideChatsSearch));
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        searchField.setTextColor(ThemeColors.getTextColor());
        searchField.setHintTextColor(ThemeColors.getTextGrayColor());
        searchField.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        searchField.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        GradientDrawable searchBackground = new GradientDrawable();
        searchBackground.setCornerRadius(AndroidUtilities.dp(20));
        searchBackground.setColor(Theme.isLight() ? 0xFFF0F2F5 : 0xFF17212B);
        searchField.setBackground(searchBackground);
        searchField.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(9), AndroidUtilities.dp(16), AndroidUtilities.dp(9));
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase();
                rebuildRows();
            }
        });
        searchContainer.addView(searchField, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(searchContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        hintView = new TextView(context);
        hintView.setTextColor(ThemeColors.getTextGrayColor());
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hintView.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        hintView.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(10), AndroidUtilities.dp(18), AndroidUtilities.dp(10));
        root.addView(hintView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout listContainer = new FrameLayout(context);
        listContainer.setBackgroundColor(ThemeColors.getBackgroundWhiteOrBlueColor());
        ListView listView = new ListView(context);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setVerticalScrollBarEnabled(false);
        listView.setCacheColorHint(Color.TRANSPARENT);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Object row = rows.get(position);
            if (!(row instanceof ChatEntry)) return;
            ChatEntry entry = (ChatEntry) row;
            if (!selection.remove(entry.id)) selection.add(entry.id);
            adapter.notifyDataSetChanged();
            updateHeader();
        });
        listView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == SCROLL_STATE_TOUCH_SCROLL) hideKeyboard();
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {}
        });
        TextView emptyView = new TextView(context);
        emptyView.setText(Translator.get(Keys.HideChatsEmpty));
        emptyView.setTextColor(ThemeColors.getTextGrayColor());
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setGravity(Gravity.CENTER);
        listContainer.addView(listView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listContainer.addView(emptyView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listView.setEmptyView(emptyView);
        root.addView(listContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout container = new LaunchActivity(activity).frameLayout;
        container.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.bringToFront();
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {}

            @Override
            public void onViewDetachedFromWindow(View view) {
                // closed, or the activity was destroyed with the picker open: changes are not applied
                // then, and neither the activity nor the views may outlive it here
                if (current == ChatPicker.this) current = null;
            }
        });

        entries.addAll(ChatPickerSource.loadInMemory(account, selection));
        rebuildRows();
        updateHeader();

        HashSet<Long> loadedChats = new HashSet<>();
        for (ChatEntry entry : entries) {
            if (!entry.contactOnly) loadedChats.add(entry.id);
        }
        ChatPickerSource.loadCached(account, loadedChats, this::mergeCached);
    }

    private void mergeCached(ArrayList<ChatEntry> cached) {
        if (current != this || cached.isEmpty()) return;
        HashSet<Long> ids = new HashSet<>();
        for (ChatEntry entry : cached) ids.add(entry.id);
        entries.removeIf(entry -> entry.contactOnly && ids.contains(entry.id));
        int insertAt = entries.size();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).contactOnly) {
                insertAt = i;
                break;
            }
        }
        entries.addAll(insertAt, cached);
        rebuildRows();
    }

    private void rebuildRows() {
        rows.clear();
        boolean chatsHeader = false;
        boolean contactsHeader = false;
        for (ChatEntry entry : entries) {
            if (!query.isEmpty() && !entry.searchText.contains(query)) continue;
            if (entry.contactOnly) {
                if (!contactsHeader) {
                    rows.add(Translator.get(Keys.HideChatsSectionContacts));
                    contactsHeader = true;
                }
            } else if (!chatsHeader) {
                rows.add(Translator.get(Keys.HideChatsSectionChats));
                chatsHeader = true;
            }
            rows.add(entry);
        }
        adapter.notifyDataSetChanged();
    }

    private void updateHeader() {
        clearButton.setVisibility(selection.isEmpty() ? View.GONE : View.VISIBLE);
        String hint = Translator.get(showOnlyMode ? Keys.HideChatsPickerHintShow : Keys.HideChatsPickerHintHide);
        hintView.setText(hint + "\n" + Translator.get(Keys.HideChatsSelectedCount, selection.size()));
    }

    private void close(boolean apply) {
        if (current == this) current = null;
        hideKeyboard();
        detach();
        if (apply && !selection.equals(initialSelection) && callback != null) {
            try {
                callback.onDone(new HashSet<>(selection));
            } catch (Throwable t) {
                Logger.e(t);
            }
        }
    }

    private void detach() {
        if (root != null && root.getParent() instanceof ViewGroup) {
            ((ViewGroup) root.getParent()).removeView(root);
        }
    }

    private void hideKeyboard() {
        if (searchField == null) return;
        try {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
            searchField.clearFocus();
        } catch (Throwable ignored) {}
    }

    private static void resolveAvatarClasses() {
        if (avatarClassesResolved) return;
        avatarClassesResolved = true;
        backupImageViewClass = ClassLoad.getClass("org.telegram.ui.Components.BackupImageView", Utils.classLoader, false);
        avatarDrawableClass = ClassLoad.getClass("org.telegram.ui.Components.AvatarDrawable", Utils.classLoader, false);
        tlObjectClass = ClassLoad.getClass(ClassNames.TL_OBJECT, Utils.classLoader, false);
    }

    private View createAvatarView(Context context) {
        resolveAvatarClasses();
        if (backupImageViewClass != null && avatarDrawableClass != null && tlObjectClass != null) {
            try {
                Object view = XposedHelpers.newInstance(backupImageViewClass, context);
                XposedHelpers.callMethod(view, "setRoundRadius", AndroidUtilities.dp(23));
                return (View) view;
            } catch (Throwable t) {
                backupImageViewClass = null;
                Logger.w("HideChats: avatar view unavailable: " + t);
            }
        }
        ImageView imageView = new ImageView(context);
        imageView.setImageDrawable(new LetterAvatarDrawable());
        return imageView;
    }

    private void bindAvatar(View view, ChatEntry entry) {
        if (view instanceof ImageView) {
            Drawable drawable = ((ImageView) view).getDrawable();
            if (drawable instanceof LetterAvatarDrawable) {
                ((LetterAvatarDrawable) drawable).setInfo(entry.id, entry.title);
            }
            return;
        }
        try {
            Object avatarDrawable = XposedHelpers.newInstance(avatarDrawableClass);
            if (entry.peer != null) {
                XposedHelpers.callMethod(avatarDrawable, "setInfo", new Class[]{int.class, tlObjectClass}, account, entry.peer);
            } else {
                XposedHelpers.callMethod(avatarDrawable, "setInfo", new Class[]{long.class, String.class, String.class}, entry.id, entry.title, null);
            }
            if (entry.self) {
                XposedHelpers.callMethod(avatarDrawable, "setAvatarType", 1);
            }
            if (entry.peer != null && !entry.self) {
                XposedHelpers.callMethod(view, "setForUserOrChat", new Class[]{tlObjectClass, avatarDrawableClass}, entry.peer, avatarDrawable);
            } else {
                XposedHelpers.callMethod(view, "setImageDrawable", new Class[]{Drawable.class}, avatarDrawable);
            }
        } catch (Throwable t) {
            LetterAvatarDrawable fallback = new LetterAvatarDrawable();
            fallback.setInfo(entry.id, entry.title);
            try {
                XposedHelpers.callMethod(view, "setImageDrawable", new Class[]{Drawable.class}, fallback);
            } catch (Throwable ignored) {}
        }
    }

    private static final class RowHolder {
        View avatar;
        TextView title;
        TextView subtitle;
        CheckBox checkBox;
    }

    private final class RowsAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            Object row = rows.get(position);
            return row instanceof ChatEntry ? ((ChatEntry) row).id : position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof ChatEntry ? VIEW_TYPE_CHAT : VIEW_TYPE_HEADER;
        }

        @Override
        public boolean isEnabled(int position) {
            return getItemViewType(position) == VIEW_TYPE_CHAT;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Object row = rows.get(position);
            if (!(row instanceof ChatEntry)) {
                TextView header = convertView instanceof TextView ? (TextView) convertView : createHeader(parent.getContext());
                header.setText((String) row);
                return header;
            }
            ChatEntry entry = (ChatEntry) row;
            View view = convertView;
            RowHolder holder;
            if (view == null || !(view.getTag() instanceof RowHolder)) {
                view = createRow(parent.getContext());
            }
            holder = (RowHolder) view.getTag();
            holder.title.setText(entry.title);
            holder.subtitle.setText(entry.subtitle);
            holder.subtitle.setVisibility(TextUtils.isEmpty(entry.subtitle) ? View.GONE : View.VISIBLE);
            holder.checkBox.setChecked(selection.contains(entry.id));
            bindAvatar(holder.avatar, entry);
            return view;
        }

        private TextView createHeader(Context context) {
            TextView header = new TextView(context);
            header.setTextColor(ThemeColors.getTextBlueColor());
            header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            header.setTypeface(Typeface.DEFAULT_BOLD);
            header.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            header.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(14), AndroidUtilities.dp(18), AndroidUtilities.dp(6));
            return header;
        }

        private View createRow(Context context) {
            LinearLayout layout = new LinearLayout(context);
            layout.setOrientation(LinearLayout.HORIZONTAL);
            layout.setGravity(Gravity.CENTER_VERTICAL);
            layout.setMinimumHeight(AndroidUtilities.dp(62));
            layout.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(7), AndroidUtilities.dp(10), AndroidUtilities.dp(7));

            RowHolder holder = new RowHolder();
            holder.avatar = createAvatarView(context);
            LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(AndroidUtilities.dp(46), AndroidUtilities.dp(46));
            avatarParams.setMarginEnd(AndroidUtilities.dp(14));
            layout.addView(holder.avatar, avatarParams);

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            holder.title = new TextView(context);
            holder.title.setTextColor(ThemeColors.getTextColor());
            holder.title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            holder.title.setSingleLine(true);
            holder.title.setEllipsize(TextUtils.TruncateAt.END);
            holder.title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            texts.addView(holder.title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            holder.subtitle = new TextView(context);
            holder.subtitle.setTextColor(ThemeColors.getTextGrayColor());
            holder.subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            holder.subtitle.setSingleLine(true);
            holder.subtitle.setEllipsize(TextUtils.TruncateAt.END);
            holder.subtitle.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subtitleParams.topMargin = AndroidUtilities.dp(2);
            texts.addView(holder.subtitle, subtitleParams);
            layout.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            holder.checkBox = new CheckBox(context);
            holder.checkBox.setClickable(false);
            holder.checkBox.setFocusable(false);
            holder.checkBox.setButtonTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{ThemeColors.getTextBlueColor(), ThemeColors.getTextGrayColor()}));
            layout.addView(holder.checkBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            layout.setTag(holder);
            return layout;
        }
    }
}
