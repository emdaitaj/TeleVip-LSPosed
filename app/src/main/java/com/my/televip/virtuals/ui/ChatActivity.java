package com.my.televip.virtuals.ui;

import android.view.View;

import com.my.televip.obfuscate.Obfuscate;
import com.my.televip.virtuals.ActionBar.ActionBarMenuItem;
import com.my.televip.virtuals.messenger.MessageObject;

import de.robv.android.xposed.XposedHelpers;

public class ChatActivity {

    final Object chatActivity;

    public ChatActivity(Object obj){
        chatActivity = obj;
    }

    public MessageObject getSelectedObject(){
        return new MessageObject(XposedHelpers.getObjectField(chatActivity, Obfuscate.getFieldName("ChatActivity","selectedObject")));
    }

    public ActionBarMenuItem getHeaderItem(){
        return new ActionBarMenuItem(XposedHelpers.getObjectField(chatActivity, Obfuscate.getFieldName("ChatActivity", "headerItem")));
    }
    public View getPinnedMessageView(){
        return (View) XposedHelpers.getObjectField(chatActivity, Obfuscate.getFieldName("ChatActivity", "pinnedMessageView"));
    }

    public void scrollToMessageId(int id, int fromMessageId, boolean select, int loadIndex, boolean forceScroll, int forcePinnedMessageId){
        String signature = "int,int,boolean,int,boolean,int";
        XposedHelpers.callMethod(chatActivity, Obfuscate.getMethodName("ChatActivity", "scrollToMessageId", signature),
                Obfuscate.arrangeArgs("ChatActivity", "scrollToMessageId", signature, id, fromMessageId, select, loadIndex, forceScroll, forcePinnedMessageId));
    }

}
