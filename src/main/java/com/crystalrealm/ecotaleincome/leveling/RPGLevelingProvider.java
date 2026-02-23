package com.crystalrealm.ecotaleincome.leveling;

import com.crystalrealm.ecotaleincome.util.PluginLogger;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Reflection-based adapter for Zuxaw RPG Leveling
 * ({@code org.zuxaw.plugin.api.RPGLevelingAPI}).
 *
 * <p>Resolves the API singleton via {@code get()}, {@code getInstance()},
 * or {@code getAPI()}, and caches {@code getPlayerLevel(UUID)}.
 * Also exposes the raw API object so that callers (e.g. MobKillListener)
 * can subscribe to XP events via reflection + Proxy.</p>
 */
public class RPGLevelingProvider implements LevelProvider {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();
    private static final String CLASS_NAME = "org.zuxaw.plugin.api.RPGLevelingAPI";

    private Object api;              // singleton instance
    private Method getLevelMethod;    // getPlayerLevel(UUID)
    private boolean available;

    public RPGLevelingProvider() {
        try {
            Class<?> clazz = Class.forName(CLASS_NAME);

            // Check isAvailable() first if it exists
            try {
                Method isAvail = clazz.getMethod("isAvailable");
                Object result = isAvail.invoke(null);
                if (result instanceof Boolean b && !b) {
                    LOGGER.info("RPGLevelingAPI.isAvailable() returned false.");
                    return;
                }
            } catch (NoSuchMethodException ignored) {}

            // Resolve singleton
            for (String name : new String[]{"get", "getInstance", "getAPI"}) {
                try {
                    Method m = clazz.getMethod(name);
                    api = m.invoke(null);
                    if (api != null) break;
                } catch (NoSuchMethodException ignored) {}
            }

            if (api != null) {
                getLevelMethod = api.getClass().getMethod("getPlayerLevel", UUID.class);
                available = true;
                LOGGER.info("RPG Leveling API resolved successfully.");
            }
        } catch (ClassNotFoundException e) {
            LOGGER.info("RPG Leveling not found (optional dependency).");
        } catch (Exception e) {
            LOGGER.warn("Failed to resolve RPG Leveling API: {}", e.getMessage());
        }
    }

    @Override
    public String getName() { return "RPG Leveling"; }

    @Override
    public boolean isAvailable() { return available && api != null; }

    @Override
    public int getPlayerLevel(UUID playerUuid) {
        if (!isAvailable() || getLevelMethod == null) return 1;
        try {
            Object result = getLevelMethod.invoke(api, playerUuid);
            if (result instanceof Number n) return n.intValue();
        } catch (Exception e) {
            LOGGER.warn("getPlayerLevel failed for {}: {}", playerUuid, e.getMessage());
        }
        return 1;
    }

    /** @return raw RPGLevelingAPI instance for event subscription, or null */
    @Nullable
    public Object getRawApi() { return api; }
}
