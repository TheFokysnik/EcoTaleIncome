package com.crystalrealm.ecotaleincome.leveling;

import com.crystalrealm.ecotaleincome.util.PluginLogger;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Config-driven reflection adapter for any leveling / XP plugin.
 *
 * <p>Works with any API that exposes:
 * <ul>
 *   <li>{@code getPlayerLevel(UUID)} — returns int or Number</li>
 * </ul>
 * Supports both static and instance-based (singleton) APIs.</p>
 *
 * @see LevelBridge
 */
public class GenericLevelProvider implements LevelProvider {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    private final String displayName;
    private Object apiInstance;
    private Method getLevelMethod;
    private boolean available;

    /**
     * @param className      fully-qualified API class name
     * @param instanceMethod singleton accessor (empty → static)
     * @param getLevelName   method name for getPlayerLevel
     */
    public GenericLevelProvider(String className, String instanceMethod, String getLevelName) {
        this.displayName = className;
        try {
            Class<?> clazz = Class.forName(className);

            // Resolve instance (singleton) or use static
            if (instanceMethod != null && !instanceMethod.isBlank()) {
                Method accessor = clazz.getMethod(instanceMethod);
                apiInstance = accessor.invoke(null);
            }

            // Resolve getPlayerLevel
            Class<?> target = apiInstance != null ? apiInstance.getClass() : clazz;
            getLevelMethod = target.getMethod(getLevelName, UUID.class);

            available = true;
            LOGGER.info("GenericLevelProvider resolved: {} (instance={})", className,
                    apiInstance != null ? "yes" : "static");
        } catch (ClassNotFoundException e) {
            LOGGER.info("GenericLevelProvider class not found: {} (disabled)", className);
        } catch (Exception e) {
            LOGGER.warn("GenericLevelProvider init failed for {}: {}", className, e.getMessage());
        }
    }

    @Override
    public String getName() { return "Generic (" + displayName + ")"; }

    @Override
    public boolean isAvailable() { return available; }

    @Override
    public int getPlayerLevel(UUID playerUuid) {
        if (!available || getLevelMethod == null) return 1;
        try {
            Object result = getLevelMethod.invoke(apiInstance, playerUuid);
            if (result instanceof Number n) return n.intValue();
        } catch (Exception e) {
            LOGGER.debug("GenericLevelProvider.getPlayerLevel failed: {}", e.getMessage());
        }
        return 1;
    }
}
