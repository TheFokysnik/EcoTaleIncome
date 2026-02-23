package com.crystalrealm.ecotaleincome.leveling;

import com.crystalrealm.ecotaleincome.util.PluginLogger;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Level provider for <b>EndlessLeveling</b> by Airijko.
 *
 * <p>Uses reflection to call {@code EndlessLevelingAPI.get().getPlayerLevel(UUID)}.</p>
 */
public class EndlessLevelingProvider implements LevelProvider {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();
    private static final String API_CLASS = "com.airijko.endlessleveling.api.EndlessLevelingAPI";

    private boolean available;
    private Object apiInstance;
    private Method getPlayerLevelMethod;

    public EndlessLevelingProvider() {
        resolve();
    }

    private void resolve() {
        try {
            Class<?> apiClass = Class.forName(API_CLASS);
            Method getMethod = apiClass.getMethod("get");
            apiInstance = getMethod.invoke(null);
            if (apiInstance == null) {
                LOGGER.info("EndlessLevelingAPI.get() returned null — provider disabled.");
                available = false;
                return;
            }
            getPlayerLevelMethod = apiInstance.getClass().getMethod("getPlayerLevel", UUID.class);
            available = true;
            LOGGER.info("EndlessLevelingAPI resolved successfully.");
        } catch (ClassNotFoundException e) {
            LOGGER.info("EndlessLeveling not found — provider disabled.");
            available = false;
        } catch (Exception e) {
            LOGGER.warn("Failed to resolve EndlessLevelingAPI: {}", e.getMessage());
            available = false;
        }
    }

    @Override
    public String getName() {
        return "Endless Leveling";
    }

    @Override
    public boolean isAvailable() {
        return available && apiInstance != null;
    }

    @Override
    public int getPlayerLevel(UUID playerUuid) {
        if (!isAvailable() || getPlayerLevelMethod == null) return 1;
        try {
            Object result = getPlayerLevelMethod.invoke(apiInstance, playerUuid);
            if (result instanceof Number n) return n.intValue();
        } catch (Exception e) {
            LOGGER.warn("EndlessLeveling getPlayerLevel failed for {}: {}", playerUuid, e.getMessage());
        }
        return 1;
    }
}
