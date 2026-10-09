package com.my.televip.obfuscate.struct;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class ClientObfuscationData {

    private final Map<String, String> classMap = new HashMap<>();
    private final Map<String, String> fieldMap = new HashMap<>();
    private final Map<String, String> methodMap = new HashMap<>();
    private final Map<String, String> methodAliasMap = new HashMap<>();
    // generated per-version mappings only: real parameter types / argument order where R8 changed a method
    private final Map<String, String[]> paramsMap = new HashMap<>();
    private final Map<String, int[]> argOrderMap = new HashMap<>();

    /**
     * Data for names worked out on the device rather than shipped: {@code classes} maps original
     * class names, {@code members} maps "ClassKey#member" for both fields and methods.
     */
    public static ClientObfuscationData fromResolved(Map<String, String> classes, Map<String, String> members,
                                                    Map<String, String[]> params, Map<String, int[]> argOrder) {
        ClientObfuscationData data = new ClientObfuscationData();
        data.classMap.putAll(classes);
        for (Map.Entry<String, String> entry : members.entrySet()) {
            data.fieldMap.put(entry.getKey(), entry.getValue());
            data.methodMap.put(entry.getKey(), entry.getValue());
        }
        if (params != null) data.paramsMap.putAll(params);
        if (argOrder != null) data.argOrderMap.putAll(argOrder);
        return data;
    }

    public static ClientObfuscationData parse(String clientJson, String clientAliasJson) {
        ClientObfuscationData data = new ClientObfuscationData();
        try {
            JSONObject root = new JSONObject(clientJson);
            data.parseClasses(root.optJSONArray("classes"));
            data.parseFields(root.optJSONArray("fields"));
            data.parseMethods(root.optJSONArray("methods"));
            data.parseParams(root.optJSONObject("params"));
            data.parseArgOrder(root.optJSONObject("argOrder"));
            JSONObject aliasRoot = new JSONObject(clientAliasJson);
            data.parseMethodAlias(aliasRoot.optJSONObject("methodAlias"));
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return data;
    }

    private void parseClasses(JSONArray arr) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String original = o.optString("o", null);
            String resolved = o.optString("r", null);
            classMap.put(original, resolved);
        }
    }

    private void parseFields(JSONArray arr) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String className = o.optString("c", null);
            String original = o.optString("o", null);
            String resolved = o.optString("r", null);
            fieldMap.put(className + "#" + original, resolved);
        }
    }

    private void parseMethods(JSONArray arr) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String className = o.optString("c", null);
            String original = o.optString("o", null);
            String resolved = o.optString("r", null);
            methodMap.put(className + "#" + original, resolved);
        }
    }

    private void parseParams(JSONObject obj) {
        if (obj == null) return;
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONArray arr = obj.optJSONArray(key);
            if (arr == null) continue;
            String[] types = new String[arr.length()];
            for (int i = 0; i < types.length; i++) types[i] = arr.optString(i);
            paramsMap.put(key, types);
        }
    }

    private void parseArgOrder(JSONObject obj) {
        if (obj == null) return;
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONArray arr = obj.optJSONArray(key);
            if (arr == null) continue;
            int[] order = new int[arr.length()];
            for (int i = 0; i < order.length; i++) order[i] = arr.optInt(i);
            argOrderMap.put(key, order);
        }
    }

    private void parseMethodAlias(JSONObject obj) {
        if (obj == null) return;
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String value = obj.optString(key, null);
            methodAliasMap.put(key, value);
        }
    }

    public String resolveClass(String original) {
        return classMap.getOrDefault(original, original);
    }

    public String resolveField(String className, String original) {
        return fieldMap.getOrDefault(className + "#" + original, original);
    }

    public String resolveMethod(String className, String original) {
        String aliased = methodAliasMap.getOrDefault(className + "#" + original, original);
        return methodMap.getOrDefault(className + "#" + aliased, aliased);
    }

    public String resolveMethod(String className, String original, String signature) {
        String exact = methodMap.get(className + "#" + original + "(" + signature + ")");
        return exact != null ? exact : resolveMethod(className, original);
    }

    public String[] resolveParams(String className, String method, String signature) {
        String[] exact = signature != null ? paramsMap.get(className + "#" + method + "(" + signature + ")") : null;
        return exact != null ? exact : paramsMap.get(className + "#" + method);
    }

    public int[] resolveArgOrder(String className, String method, String signature) {
        int[] exact = signature != null ? argOrderMap.get(className + "#" + method + "(" + signature + ")") : null;
        return exact != null ? exact : argOrderMap.get(className + "#" + method);
    }
}