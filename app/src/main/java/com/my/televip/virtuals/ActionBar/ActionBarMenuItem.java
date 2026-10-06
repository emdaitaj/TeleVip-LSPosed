package com.my.televip.virtuals.ActionBar;

import com.my.televip.obfuscate.ArgsResolver;
import com.my.televip.obfuscate.Obfuscate;

import de.robv.android.xposed.XposedHelpers;

public class ActionBarMenuItem {

    Object actionBarMenuItem;

    public ActionBarMenuItem(Object obj){
        actionBarMenuItem = obj;
    }

    public void lazilyAddSubItem(int id, int resId, String name){
        XposedHelpers.callMethod(actionBarMenuItem, Obfuscate.getMethodName("ActionBarMenuItem", "lazilyAddSubItem", "int,int,CharSequence"), id, resId, name);
    }
    public void addSubItem(int id, int resId, String name){
        XposedHelpers.callMethod(actionBarMenuItem, Obfuscate.getMethodName("ActionBarMenuItem", "addSubItem", "int,int,CharSequence"), id, resId, name);
    }

    public Object getActionBarMenuItem(){
        return  actionBarMenuItem;
    }
}
