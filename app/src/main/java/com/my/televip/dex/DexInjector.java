package com.my.televip.dex;

import com.my.televip.Clients.ClientManager;
import com.my.televip.logging.Logger;

import java.nio.ByteBuffer;

import dalvik.system.InMemoryDexClassLoader;
import de.robv.android.xposed.XposedHelpers;

public class DexInjector {

    public static ClassLoader classLoader;

    public static void injectDex(ClassLoader classLoader) {
        if (ClientManager.is(ClientManager.Client.Nekogram) || ClientManager.is(ClientManager.Client.Cherrygram)) return;
        // the injected adapter extends the client's own androidx RecyclerView classes, they are renamed in obfuscated builds
        if (XposedHelpers.findClassIfExists("androidx.recyclerview.widget.RecyclerView$Adapter", classLoader) == null) return;
        try {
            byte[] dexBytes = DexHolder.DEX_BYTES;

            ByteBuffer buffer = ByteBuffer.wrap(dexBytes);

            DexInjector.classLoader = new InMemoryDexClassLoader(
                    buffer,
                    classLoader
            );
        } catch (Throwable e){
            Logger.e(e);
        }

    }

}
