package com.my.televip.settings.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import com.my.televip.Configs.ConfigItem;
import com.my.televip.Configs.ConfigManager;
import com.my.televip.Audio;
import com.my.televip.features.hideChats.HideChats;
import com.my.televip.language.Keys;
import com.my.televip.language.Translator;
import com.my.televip.logging.Logger;
import com.my.televip.settings.controller.SettingsController;
import com.my.televip.ui.ThemeColors;
import com.my.televip.utils.DialogUtils;
import com.my.televip.utils.Utils;
import com.my.televip.virtuals.ActionBar.AlertDialog;
import com.my.televip.virtuals.androidx.ViewHolder;
import com.my.televip.virtuals.messenger.browser.Browser;
import com.my.televip.ui.Cells.ExpandableTextCheckCell;
import com.my.televip.virtuals.ui.Cells.HeaderCell;
import com.my.televip.virtuals.ui.Cells.TextCheckCell;
import com.my.televip.ui.Cells.TextInfoCell;
import com.my.televip.virtuals.ui.Cells.TextSettingsCell;

import de.robv.android.xposed.XposedHelpers;

public class SettingsAdapter {

    public static int getRow(int position) { return ConfigManager.getItems().get(position).getType(); }

    public static int getRowCount() {
        return ConfigManager.getItems().size();
    }

    public static void onBindViewHolder(Object holder, SettingsController settingsController, int position, int viewType) {
        try {
            ConfigItem item = ConfigManager.getItems().get(position);

            switch (viewType) {
                case ConfigItem.HEADER:
                    HeaderCellHolder headerCell = new HeaderCellHolder(holder);
                    headerCell.cell.setText(Translator.get(item.getKey()));
                    break;

                case ConfigItem.SWITCH:
                    TextCheckCellHolder textCheck = new TextCheckCellHolder(holder);
                    if (item.getValue() != null) {
                        textCheck.cell.setTextAndValueAndCheck(
                                Translator.get(item.getKey()),
                                item.getValue(),
                                item.isEnable(),
                                true,
                                false
                        );
                    } else if (item.isRestartRequired()) {
                        textCheck.cell.setTextAndValueAndCheck(
                                Translator.get(item.getKey()),
                                Translator.get(Keys.RestartRequired),
                                item.isEnable(),
                                true,
                                false
                        );
                    } else {
                        textCheck.cell.setTextAndCheck(
                                Translator.get(item.getKey()),
                                item.isEnable(),
                                false
                        );
                    }
                    textCheck.cell.getTextView().setLines(0);
                    textCheck.cell.getTextView().setMaxLines(0);
                    textCheck.cell.getTextView().setSingleLine(false);
                    textCheck.cell.getTextView().setEllipsize(null);
                    break;
                case ConfigItem.EXPANDABLE_SWITCH:
                    ExpandableTextCheckCellHolder expandableTextCheck = new ExpandableTextCheckCellHolder(holder);
                    expandableTextCheck.cell.addChildren(item);

                    break;
                case ConfigItem.TEXT:
                    TextSettingsCellHolder settingsCell = new TextSettingsCellHolder(holder);
                    if (item.getKey().equals(Keys.Calendar)) {
                        String value = null;
                        switch (item.getCustomCalendar()) {
                            case 0:
                                value = Translator.get(Keys.Gregorian);
                                break;
                            case 1:
                                value = Translator.get(Keys.Hijri);
                                break;
                            case 2:
                                value = Translator.get(Keys.Persian);
                                break;
                        }
                        settingsCell.cell.setTextAndValue(Translator.get(item.getKey()), value, false, false);
                    } else if (item.getKey().equals(Keys.HideChatsList)) {
                        int managed = HideChats.getManagedCount();
                        settingsCell.cell.setTextAndValue(Translator.get(item.getKey()),
                                managed == 0 ? Translator.get(Keys.HideChatsNoneSelected) : Translator.get(Keys.HideChatsSelectedCount, managed), false, false);
                        settingsCell.cell.getTextView().setTextColor(ThemeColors.getTextColor());
                    } else if (item.getKey().equals(Keys.HideChatsVisible)) {
                        int managed = HideChats.getManagedCount();
                        int visible = HideChats.getVisibleCount();
                        String value = managed == 0 ? Translator.get(Keys.HideChatsListEmpty)
                                : visible == 0 ? Translator.get(Keys.HideChatsAllHidden)
                                : Translator.get(Keys.HideChatsVisibleCount, visible, managed);
                        settingsCell.cell.setTextAndValue(Translator.get(item.getKey()), value, false, false);
                        settingsCell.cell.getTextView().setTextColor(ThemeColors.getTextColor());
                    } else {
                        settingsCell.cell.setText(Translator.get(item.getKey()), false);
                        settingsCell.cell.getTextView().setTextColor(ThemeColors.getTextBlueColor());
                    }
                    break;
                case ConfigItem.DIVIDER:
                    ShadowSectionCellHolder shadowSectionCell = new ShadowSectionCellHolder(holder);
                    shadowSectionCell.cell.setBackgroundColor((ThemeColors.getBackgroundGrayColor()));
                    break;
                case ConfigItem.INFO:
                    TextInfoCellHolder textInfoCell = new TextInfoCellHolder(holder);
                    TextView textView = textInfoCell.text.getTextView();
                    if (item.getKey().equals(Keys.OfflineVisibilityInfo)) {
                        bindExpandableInfo(textView, Translator.get(Keys.OfflineVisibilityInfo), 2);
                    } else if (item.getKey().equals(Keys.HideChatsInfo)) {
                        bindExpandableInfo(textView, Translator.get(Keys.HideChatsInfo), 3);
                    }
                    break;
            }
            ViewHolder viewHolder = new ViewHolder(holder);

            viewHolder.getItemView().setOnLongClickListener(v -> {
                playAudio(settingsController.getContext());
                return true;
            });

            viewHolder.getItemView().setOnClickListener(v -> {

                if (viewType == ConfigItem.SWITCH) {
                    TextCheckCellHolder textCheck = new TextCheckCellHolder(holder);
                    // the switch is bound from item.isEnable(), TextCheckCell.isChecked() is inlined away in R8 builds
                    boolean checked = !item.isEnable();
                    textCheck.cell.setChecked(checked);
                    item.setEnable(checked);
                    item.run();
                } else if (viewType == ConfigItem.TEXT) {
                    switch (item.getKey()) {
                        case Keys.DeveloperChannel:
                            settingsController.hide();
                            Browser.openUrl(settingsController.getContext(), "https://t.me/t_l0_e");
                            break;
                        case Keys.RestartApp:
                            Intent intent = settingsController.getContext()
                                    .getPackageManager()
                                    .getLaunchIntentForPackage(Utils.pkgName);

                            if (intent != null) {
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                settingsController.getContext().startActivity(intent);
                            }

                            ((Activity) settingsController.getContext()).finishAffinity();
                            android.os.Process.killProcess(android.os.Process.myPid());
                            break;
                        case Keys.Calendar:
                            Dialog dlg = DialogUtils.createSingleChoiceDialog((Activity) settingsController.getContext(), new String[]{
                                            Translator.get(Keys.Gregorian), Translator.get(Keys.Hijri), Translator.get(Keys.Persian)},
                                    Translator.get(Keys.Calendar), item.getCustomCalendar(), (dialog, which) -> {
                                        item.setCustomCalendar(which);
                                        item.run();
                                        if (settingsController.settingsActivity.listView.getAdapter() != null) {
                                            settingsController.settingsActivity.listView.getAdapter().notifyItemChanged(position);
                                        }
                                    });
                            dlg.show();
                            break;
                        case Keys.HideChatsList:
                            HideChats.openManagedPicker((Activity) settingsController.getContext(), () -> {
                                notifyItemChanged(settingsController, position);
                                notifyItemChanged(settingsController, ConfigManager.getItems().indexOf(ConfigManager.hideChatsVisible));
                            });
                            break;
                        case Keys.HideChatsVisible:
                            if (HideChats.getManagedCount() == 0) {
                                HideChats.openManagedPicker((Activity) settingsController.getContext(), () -> {
                                    notifyItemChanged(settingsController, position);
                                    notifyItemChanged(settingsController, ConfigManager.getItems().indexOf(ConfigManager.hideChatsList));
                                });
                            } else {
                                HideChats.openVisiblePicker((Activity) settingsController.getContext(), () -> notifyItemChanged(settingsController, position));
                            }
                            break;
                    }
                }
            });

        } catch (Throwable t){
            Logger.e(t);
        }

    }

    public static class HeaderCellHolder {
        HeaderCell cell;

        public HeaderCellHolder(Object obj) {
            cell = new HeaderCell(XposedHelpers.getObjectField(obj, "headerCell"));
        }
    }

    public static class TextCheckCellHolder {
        TextCheckCell cell;

        public TextCheckCellHolder(Object obj) {
            cell = new TextCheckCell(XposedHelpers.getObjectField(obj, "textCheckCell"));
        }
    }
    public static class ExpandableTextCheckCellHolder {
        ExpandableTextCheckCell cell;

        public ExpandableTextCheckCellHolder(Object obj) {
            cell = (ExpandableTextCheckCell) XposedHelpers.getObjectField(obj, "expandableTextCheckCell");
        }
    }

    public static class TextSettingsCellHolder {
        TextSettingsCell cell;

        public TextSettingsCellHolder(Object obj) {
            cell = new TextSettingsCell(XposedHelpers.getObjectField(obj, "textSettingsCell"));
        }
    }

    public static class ShadowSectionCellHolder {
        View cell;

        public ShadowSectionCellHolder(Object obj) {
            cell = (View) XposedHelpers.getObjectField(obj, "view");
        }
    }

    public static class TextInfoCellHolder {
        TextInfoCell text;

        public TextInfoCellHolder(Object obj) {
            text = (TextInfoCell) XposedHelpers.getObjectField(obj, "view");
        }
    }

    private static void notifyItemChanged(SettingsController settingsController, int position) {
        try {
            if (position < 0 || settingsController.settingsActivity == null || settingsController.settingsActivity.listView == null) return;
            settingsController.settingsActivity.listView.getAdapter().notifyItemChanged(position);
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    /**
     * Info text collapsed to a few lines; a tap expands / collapses it. Rebinding collapses it again.
     */
    private static void bindExpandableInfo(TextView textView, String text, int collapsedLines) {
        textView.setText(text);
        textView.setTag(Boolean.FALSE);
        textView.setMaxLines(collapsedLines);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setOnClickListener(v -> {
            boolean expanded = !Boolean.TRUE.equals(textView.getTag());
            textView.setTag(expanded);
            textView.setMaxLines(expanded ? Integer.MAX_VALUE : collapsedLines);
            textView.setEllipsize(expanded ? null : TextUtils.TruncateAt.END);
        });
    }

    public static void playAudio(Context context) {
        if (Audio.playing) {
            Audio.stop();
        } else {
            Audio.start();
            DialogUtils.showQuranAlert(context);
        }
    }

}
