package com.crystalrealm.ecotaleincome.leveling;

import com.crystalrealm.ecotaleincome.util.PluginLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Facade that routes level queries to the active {@link LevelProvider}.
 *
 * <p>Usage:
 * <pre>
 *   LevelBridge bridge = new LevelBridge();
 *   // built-in providers are registered automatically
 *   bridge.registerProvider("generic", new GenericLevelProvider(...)); // optional
 *   bridge.activate("rpgleveling"); // select preferred
 *   int lvl = bridge.getPlayerLevel(uuid);
 * </pre>
 */
public class LevelBridge {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    private final LinkedHashMap<String, LevelProvider> providers = new LinkedHashMap<>();
    private LevelProvider active;

    public LevelBridge() {
        // Built-in: RPG Leveling (always registered; isAvailable() returns false if not present)
        registerProvider("rpgleveling", new RPGLevelingProvider());
        registerProvider("endlessleveling", new EndlessLevelingProvider());
    }

    /** Register a provider under a unique key. */
    public void registerProvider(@Nonnull String key, @Nonnull LevelProvider provider) {
        providers.put(key.toLowerCase(), provider);
        LOGGER.info("Level provider registered: {} ({})", key, provider.getName());
    }

    /**
     * Activates a provider by key. Falls back to first available if the
     * preferred one is not present or not available.
     *
     * @return {@code true} if any available provider was activated
     */
    public boolean activate(@Nullable String preferredKey) {
        // Try preferred
        if (preferredKey != null) {
            LevelProvider p = providers.get(preferredKey.toLowerCase());
            if (p != null && p.isAvailable()) {
                active = p;
                LOGGER.info("Level provider activated: {} ({})", preferredKey, p.getName());
                return true;
            }
        }

        // Fallback: first available
        for (Map.Entry<String, LevelProvider> e : providers.entrySet()) {
            if (e.getValue().isAvailable()) {
                active = e.getValue();
                LOGGER.info("Level provider fallback: {} ({})", e.getKey(), active.getName());
                return true;
            }
        }

        LOGGER.warn("No level provider available — all level queries will return 1.");
        return false;
    }

    public boolean isAvailable() {
        return active != null && active.isAvailable();
    }

    /** @return human-readable provider name, or "none" */
    @Nonnull
    public String getProviderName() {
        return active != null ? active.getName() : "none";
    }

    /** @return player level via active provider, or 1 */
    public int getPlayerLevel(@Nonnull UUID playerUuid) {
        if (active == null || !active.isAvailable()) return 1;
        return active.getPlayerLevel(playerUuid);
    }

    /**
     * Returns the raw RPG Leveling API object for event subscription,
     * or {@code null} if the "rpgleveling" provider is not active.
     */
    @Nullable
    public Object getRawRpgApi() {
        LevelProvider rp = providers.get("rpgleveling");
        if (rp instanceof RPGLevelingProvider rpgProv) {
            return rpgProv.getRawApi();
        }
        return null;
    }

    /** Notify all providers about a new player (caches ECS Store/Ref for providers that need it). */
    public void onPlayerJoin(UUID uuid, Object store, Object ref) {
        for (LevelProvider p : providers.values()) {
            try { p.onPlayerJoin(uuid, store, ref); } catch (Exception ignored) {}
        }
    }

    /** Notify all providers that a player left. */
    public void onPlayerLeave(UUID uuid) {
        for (LevelProvider p : providers.values()) {
            try { p.onPlayerLeave(uuid); } catch (Exception ignored) {}
        }
    }
}
