package com.crystalrealm.ecotaleincome.leveling;

import com.crystalrealm.ecotaleincome.util.PluginLogger;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Level provider for <b>MMOSkillTree</b> by Ziggfreed.
 *
 * <p>MMOSkillTree uses ECS and requires {@code Store/Ref} context.
 * Player context is cached via {@link #onPlayerJoin} when players connect.</p>
 */
public class MMOSkillTreeProvider implements LevelProvider {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();
    private static final String API_CLASS = "com.ziggfreed.mmoskilltree.api.MMOSkillTreeAPI";

    private final ConcurrentHashMap<UUID, Object[]> playerContext = new ConcurrentHashMap<>();

    private boolean available;
    private Method getTotalLevelMethod;
    private Class<?> storeClass;
    private Class<?> refClass;

    public MMOSkillTreeProvider() {
        resolve();
    }

    private void resolve() {
        try {
            Class<?> apiClass = Class.forName(API_CLASS);
            storeClass = Class.forName("com.hypixel.hytale.component.Store");
            refClass = Class.forName("com.hypixel.hytale.component.Ref");
            getTotalLevelMethod = apiClass.getMethod("getTotalLevel", storeClass, refClass);
            available = true;
            LOGGER.info("MMOSkillTreeAPI resolved successfully.");
        } catch (ClassNotFoundException e) {
            LOGGER.info("MMOSkillTree not found — provider disabled.");
            available = false;
        } catch (Exception e) {
            LOGGER.warn("Failed to resolve MMOSkillTreeAPI: {}", e.getMessage());
            available = false;
        }
    }

    @Override
    public void onPlayerJoin(UUID uuid, Object store, Object ref) {
        playerContext.put(uuid, new Object[]{store, ref});
    }

    @Override
    public void onPlayerLeave(UUID uuid) {
        playerContext.remove(uuid);
    }

    @Override
    public String getName() {
        return "MMOSkillTree";
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public int getPlayerLevel(UUID playerUuid) {
        if (!isAvailable() || getTotalLevelMethod == null) return 1;
        Object[] ctx = playerContext.get(playerUuid);
        if (ctx == null) return 1;
        try {
            Object result = getTotalLevelMethod.invoke(null, ctx[0], ctx[1]);
            if (result instanceof Number n) return n.intValue();
        } catch (Exception e) {
            LOGGER.warn("MMOSkillTree getTotalLevel failed for {}: {}", playerUuid, e.getMessage());
        }
        return 1;
    }
}
