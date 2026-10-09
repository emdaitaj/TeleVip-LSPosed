package com.my.televip.obfuscate;

import com.my.televip.Clients.ClientManager;
import com.my.televip.logging.Logger;
import com.my.televip.obfuscate.struct.ClientObfuscationData;
import com.my.televip.utils.JsonAssetReader;
import com.my.televip.utils.DevOptions;
import com.my.televip.utils.Utils;

import java.util.EnumMap;
import java.util.Map;

import com.my.televip.obfuscate.runtime.RuntimeMapping;

import de.robv.android.xposed.XposedHelpers;

public class ObfuscationManager {

    private static final Map<ClientManager.Client, ClientObfuscationData> cache =
            new EnumMap<>(ClientManager.Client.class);

    private static volatile ClientObfuscationData currentClientData;
    private static volatile boolean attemptedLoad = false;
    private static volatile boolean versionMapped = false;

    public static ClientObfuscationData current() {
        if (currentClientData != null) return currentClientData;
        if (attemptedLoad) return null;

        synchronized (ObfuscationManager.class) {
            if (currentClientData != null) return currentClientData;
            attemptedLoad = true;

            ClientManager.Client type = ClientManager.getCurrent();
            if (type == null) return null;

            currentClientData = load(type);
            return currentClientData;
        }
    }

    /**
     * True when the mapping was generated for this exact build (assets/clients/&lt;Client&gt;/&lt;versionCode&gt;.json),
     * it then covers every name TeleVip uses, unlike the hand-made per-client mappings.
     */
    public static boolean isVersionMapped() {
        current();
        return versionMapped;
    }

    private static ClientObfuscationData load(ClientManager.Client type) {
        if (cache.containsKey(type)) return cache.get(type);

        ClientObfuscationData data = loadVersioned(type);

        if (data == null) {
            String clientPath = "assets/clients/" + type.name() + ".json";
            String clientJson = JsonAssetReader.readRaw(clientPath, false);

            String clientAliasPath = "assets/clients/Alias/" + type.name() + ".json";
            String clientAliasJson = JsonAssetReader.readRaw(clientAliasPath, false);

            if (clientJson != null && clientAliasJson != null)
                data = ClientObfuscationData.parse(clientJson, clientAliasJson);
        }

        if (data != null)
            cache.put(type, data);
        return data;
    }

    // Official Telegram is R8-obfuscated since 12.10.2 and the names change with every release.
    private static ClientObfuscationData loadVersioned(ClientManager.Client type) {
        if (Utils.versionCode > 0 && !DevOptions.isResolverForced()) {
            String path = "assets/clients/" + type.name() + "/" + Utils.versionCode + ".json";
            String json = JsonAssetReader.readRaw(path, false);
            if (json != null) {
                versionMapped = true;
                Logger.l("Using obfuscation mapping " + path);
                return ClientObfuscationData.parse(json, json);
            }
        }

        if (XposedHelpers.findClassIfExists("org.telegram.ui.ActionBar.Theme", Utils.classLoader) != null) {
            return null;  // names are not obfuscated in this build, nothing to resolve
        }
        Logger.w("This " + type.name() + " build (versionCode " + Utils.versionCode + ") is obfuscated and has no"
                + " mapping of its own; working the names out from the build itself");
        return RuntimeMapping.resolve();
    }

    /**
     * True when the names came from the device rather than a shipped mapping, and everything the
     * hooks need was found. Features that must not half-work check this.
     */
    public static boolean isResolvedCompletely() {
        current();
        return resolvedCompletely;
    }

    /**
     * True when the names were worked out from the installed build instead of a shipped mapping.
     * They are checked rather than trusted: see HideChats.hasCriticalHookPoints.
     */
    public static boolean isRuntimeResolved() {
        current();
        return runtimeResolved;
    }

    public static void setRuntimeResolved(boolean resolved) {
        runtimeResolved = resolved;
    }

    private static volatile boolean runtimeResolved;

    public static void setResolvedCompletely(boolean complete) {
        resolvedCompletely = complete;
    }

    private static volatile boolean resolvedCompletely;

}
