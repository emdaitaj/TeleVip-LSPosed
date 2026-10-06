package com.my.televip.obfuscate;


import com.my.televip.obfuscate.struct.ResolverRegistry;

import de.robv.android.xposed.XC_MethodHook;

public class ArgsResolver {

    public static ResolverRegistry resolverRegistry = new ResolverRegistry(ResolverRegistry.getResolverClass());

    public static Class<?>[] resolveObject(String name, Class<?>[] classes) {
        if (resolverRegistry != null) {
            if (resolverRegistry.hasParameter(name)) {
                return resolverRegistry.resolveParameter(name);
            }
        }

        return classes;
    }

    // prefers the real parameter list from a generated per-version mapping, hooks then have to find arguments by type
    public static Object[] merge(String className, String name, Class<?>[] classes, XC_MethodHook hook)
    {
        Class<?>[] real = Obfuscate.getParameterTypes(className, name, null);
        if (real == null) return merge(name, classes, hook);
        Object[] result = new Object[real.length + 1];
        System.arraycopy(real, 0, result, 0, real.length);
        result[real.length] = hook;
        return result;
    }

    public static Object[] merge(String name, Class<?>[] classes, XC_MethodHook hook)
    {
        Class<?>[] c = resolveObject(name, classes);
        if (c != null) {
            Object[] result = new Object[c.length + 1];
            System.arraycopy(c, 0, result, 0, c.length);
            result[c.length] = hook;
            return result;
        }
        return null;
    }
}
