package com.my.televip.utils;

import com.my.televip.logging.Logger;

import java.io.File;

import de.robv.android.xposed.XposedHelpers;

/**
 * Switches meant for testing TeleVip itself, turned on by marker files under /data/local/tmp, which
 * only adb can write. Nothing here changes anything for a normal install.
 */
public final class DevOptions {

    /**
     * Turns on Telegram's own debug flag, which is what makes the login screen offer its test
     * servers. Those accept the documented test numbers (99966XYYYY, code = the DC digit repeated),
     * so the client can be signed in where the production servers will not sign one in, such as an
     * emulator. Test accounts live on separate servers and share nothing with real ones.
     *
     *   adb shell "touch /data/local/tmp/televip_test_backend && chmod 666 /data/local/tmp/televip_test_backend"
     */
    private static final String TEST_BACKEND_MARKER = "/data/local/tmp/televip_test_backend";

    /**
     * Ignores the shipped name mapping so the names are worked out from the build instead, which is
     * how the resolver is exercised on a build that does have a mapping to check it against.
     *
     *   adb shell "touch /data/local/tmp/televip_force_resolver && chmod 666 /data/local/tmp/televip_force_resolver"
     */
    private static final String FORCE_RESOLVER_MARKER = "/data/local/tmp/televip_force_resolver";

    public static boolean isResolverForced() {
        try {
            return new File(FORCE_RESOLVER_MARKER).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    private DevOptions() {}

    public static boolean isTestBackendRequested() {
        try {
            return new File(TEST_BACKEND_MARKER).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Switches the connection to the test servers once the networking layer is up. Telegram only
     * offers the switch in its own debug builds, and turning that whole debug mode on from here
     * hangs the standalone build on startup, so only this one flag is flipped.
     */
    public static void applyTestBackend(int account) {
        if (!isTestBackendRequested() || switched) return;
        try {
            Class<?> connectionsManager = XposedHelpers.findClassIfExists("org.telegram.tgnet.ConnectionsManager", Utils.classLoader);
            if (connectionsManager == null) return;
            Object instance = XposedHelpers.callStaticMethod(connectionsManager, "getInstance", account);
            if (instance == null) return;
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(instance, "isTestBackend"))) {
                switched = true;
                return;
            }
            XposedHelpers.callMethod(instance, "switchBackend", false);
            switched = true;
            Logger.l("Dev marker found: connected to Telegram's test servers (test numbers 99966XYYYY)");
        } catch (Throwable t) {
            Logger.e(t);
        }
    }

    private static volatile boolean switched;
}
