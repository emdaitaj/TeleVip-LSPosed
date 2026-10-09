package com.my.televip.obfuscate.runtime;

import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.ObfuscationManager;
import com.my.televip.obfuscate.struct.ClientObfuscationData;
import com.my.televip.utils.Utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Names worked out on the device for a Telegram build that has no mapping of its own, kept in the
 * app's own cache directory so the work happens once per build rather than once per start.
 *
 * A plain file rather than SharedPreferences: this runs while the app is still starting, long
 * before it has a Context.
 */
public final class RuntimeMapping {

    private static final String CACHE_NAME = "televip-names-";

    private RuntimeMapping() {}

    public static ClientObfuscationData resolve() {
        try {
            if (Utils.apkPath == null) {
                Logger.w("Cannot work the names out: the path of the installed app is unknown");
                return null;
            }
            File cache = cacheFile();
            ClientObfuscationData cached = fromCache(cache);
            if (cached != null) return cached;

            StructuralResolver resolver = StructuralResolver.resolve(Utils.apkPath);
            if (resolver == null || resolver.classMap().isEmpty()) return null;
            Map<String, String> classes = resolver.classMap();
            Map<String, String> members = resolver.memberMap();
            Map<String, String[]> params = toArrays(resolver.paramMap());
            ObfuscationManager.setResolvedCompletely(resolver.isComplete());
            ObfuscationManager.setRuntimeResolved(true);
            store(cache, classes, members, params, resolver.argOrderMap(), resolver.isComplete());
            return ClientObfuscationData.fromResolved(classes, members, params, resolver.argOrderMap());
        } catch (Throwable t) {
            Logger.e(t);
            return null;
        }
    }

    private static File cacheFile() {
        File dir = new File(Utils.dataDir != null ? Utils.dataDir : "/data/local/tmp", "cache");
        return new File(dir, CACHE_NAME + Utils.versionCode + ".json");
    }

    private static ClientObfuscationData fromCache(File file) {
        if (!file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            JSONObject root = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            Map<String, String> classes = read(root.optJSONObject("classes"));
            if (classes.isEmpty()) return null;
            Map<String, String> members = read(root.optJSONObject("members"));
            ObfuscationManager.setResolvedCompletely(root.optBoolean("complete"));
            ObfuscationManager.setRuntimeResolved(true);
            Logger.l("Using names worked out earlier for versionCode " + Utils.versionCode
                    + " (" + classes.size() + " classes, " + members.size() + " members)");
            return ClientObfuscationData.fromResolved(classes, members,
                    readArrays(root.optJSONObject("params")), readOrders(root.optJSONObject("argOrder")));
        } catch (Throwable t) {
            Logger.e(t);
            return null;
        }
    }

    private static Map<String, String[]> toArrays(Map<String, List<String>> values) {
        HashMap<String, String[]> out = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            out.put(entry.getKey(), entry.getValue().toArray(new String[0]));
        }
        return out;
    }

    private static Map<String, String[]> readArrays(JSONObject json) {
        HashMap<String, String[]> out = new HashMap<>();
        if (json == null) return out;
        for (Iterator<String> it = json.keys(); it.hasNext(); ) {
            String key = it.next();
            JSONArray array = json.optJSONArray(key);
            if (array == null) continue;
            String[] values = new String[array.length()];
            for (int i = 0; i < values.length; i++) values[i] = array.optString(i);
            out.put(key, values);
        }
        return out;
    }

    private static Map<String, int[]> readOrders(JSONObject json) {
        HashMap<String, int[]> out = new HashMap<>();
        if (json == null) return out;
        for (Iterator<String> it = json.keys(); it.hasNext(); ) {
            String key = it.next();
            JSONArray array = json.optJSONArray(key);
            if (array == null) continue;
            int[] values = new int[array.length()];
            for (int i = 0; i < values.length; i++) values[i] = array.optInt(i);
            out.put(key, values);
        }
        return out;
    }

    private static void store(File file, Map<String, String> classes, Map<String, String> members,
                              Map<String, String[]> params, Map<String, int[]> argOrder, boolean complete) {
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            JSONObject root = new JSONObject();
            root.put("classes", new JSONObject(classes));
            root.put("members", new JSONObject(members));
            root.put("params", new JSONObject(params));
            root.put("argOrder", new JSONObject(argOrder));
            root.put("complete", complete);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    private static Map<String, String> read(JSONObject json) {
        HashMap<String, String> values = new HashMap<>();
        if (json == null) return values;
        for (Iterator<String> it = json.keys(); it.hasNext(); ) {
            String key = it.next();
            values.put(key, json.optString(key));
        }
        return values;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }
}
