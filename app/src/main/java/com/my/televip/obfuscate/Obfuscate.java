package com.my.televip.obfuscate;

import com.my.televip.obfuscate.struct.ClientObfuscationData;
import com.my.televip.utils.Utils;

import de.robv.android.xposed.XposedHelpers;

public class Obfuscate {

    public static String getClassName(String className) {
        ClientObfuscationData data = ObfuscationManager.current();
        if (data == null) return className;
        return data.resolveClass(className);
    }

    public static String getMethodName(String className, String methodName) {
        ClientObfuscationData data = ObfuscationManager.current();
        if (data == null) return methodName.replace("storyEntitiesAllowed2", "storyEntitiesAllowed");
        return data.resolveMethod(className, methodName);
    }

    // for overloads that R8 renamed differently, signature lists the original parameter types: "int,TLObject"
    public static String getMethodName(String className, String methodName, String signature) {
        ClientObfuscationData data = ObfuscationManager.current();
        if (data == null) return methodName;
        return data.resolveMethod(className, methodName, signature);
    }

    public static String getFieldName(String className, String fieldName) {
        ClientObfuscationData data = ObfuscationManager.current();
        if (data == null) return fieldName;
        return data.resolveField(className, fieldName);
    }

    /**
     * Parameter types of the real method when R8 removed, narrowed or reordered parameters
     * (only known from generated per-version mappings), otherwise null.
     */
    public static Class<?>[] getParameterTypes(String className, String methodName, String signature) {
        ClientObfuscationData data = ObfuscationManager.current();
        if (data == null) return null;
        String[] names = data.resolveParams(className, methodName, signature);
        if (names == null) return null;
        Class<?>[] types = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            types[i] = XposedHelpers.findClassIfExists(names[i], Utils.classLoader);
            if (types[i] == null) return null;
        }
        return types;
    }

    // puts call arguments in the order of the real method, R8 sorts parameters by type
    public static Object[] arrangeArgs(String className, String methodName, String signature, Object... args) {
        ClientObfuscationData data = ObfuscationManager.current();
        int[] order = data != null ? data.resolveArgOrder(className, methodName, signature) : null;
        if (order == null || order.length != args.length) return args;
        Object[] arranged = new Object[order.length];
        for (int i = 0; i < order.length; i++) arranged[i] = args[order[i]];
        return arranged;
    }
}
