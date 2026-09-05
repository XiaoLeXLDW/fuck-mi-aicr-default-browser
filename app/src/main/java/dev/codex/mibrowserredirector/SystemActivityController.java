package dev.codex.mibrowserredirector;

import android.app.IActivityController;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class SystemActivityController {
    private static final String[][] CANDIDATES = {
            {"android.app.ActivityManager", "android.app.IActivityManager"},
            {"android.app.ActivityTaskManager", "android.app.IActivityTaskManager"}
    };

    private final Object service;
    private final Method setter;

    private SystemActivityController(Object service, Method setter) {
        this.service = service;
        this.setter = setter;
    }

    static SystemActivityController connect() throws ReflectiveOperationException {
        ReflectiveOperationException last = null;
        for (String[] candidate : CANDIDATES) {
            try {
                Class<?> managerClass = Class.forName(candidate[0]);
                Method getService = managerClass.getDeclaredMethod("getService");
                getService.setAccessible(true);
                Object service = getService.invoke(null);
                if (service == null) throw new IllegalStateException(candidate[0] + " returned null");

                Class<?> interfaceClass = Class.forName(candidate[1]);
                for (Method method : interfaceClass.getMethods()) {
                    if (!"setActivityController".equals(method.getName())) continue;
                    Class<?>[] parameters = method.getParameterTypes();
                    if (parameters.length < 1 || parameters.length > 2) continue;
                    if (!"android.app.IActivityController".equals(parameters[0].getName())) continue;
                    if (parameters.length == 2 && parameters[1] != boolean.class) continue;
                    method.setAccessible(true);
                    return new SystemActivityController(service, method);
                }
                throw new NoSuchMethodException(candidate[1] + ".setActivityController");
            } catch (ReflectiveOperationException e) {
                last = e;
            }
        }
        throw last == null ? new NoSuchMethodException("No Activity Controller service") : last;
    }

    void set(IActivityController controller) throws ReflectiveOperationException {
        try {
            if (setter.getParameterTypes().length == 2) {
                setter.invoke(service, controller, false);
            } else {
                setter.invoke(service, controller);
            }
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SecurityException) throw (SecurityException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw e;
        }
    }

    String implementationName() {
        return setter.getDeclaringClass().getSimpleName() + "#" + setter.getName()
                + "/" + setter.getParameterTypes().length;
    }
}
