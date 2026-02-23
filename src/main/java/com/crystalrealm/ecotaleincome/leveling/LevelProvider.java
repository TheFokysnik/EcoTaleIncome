package com.crystalrealm.ecotaleincome.leveling;

import java.util.UUID;

/**
 * Universal interface for any leveling / XP system.
 *
 * <p>Built-in implementations:
 * <ul>
 *   <li>{@link RPGLevelingProvider} — Zuxaw RPG Leveling</li>
 *   <li>{@link EndlessLevelingProvider} — EndlessLeveling by Airijko</li>
 *   <li>{@link MMOSkillTreeProvider} — MMOSkillTree by Ziggfreed</li>
 *   <li>{@link GenericLevelProvider} — reflection adapter for any plugin</li>
 * </ul>
 */
public interface LevelProvider {

    /** Human-readable provider name (e.g. "RPG Leveling"). */
    String getName();

    /** @return {@code true} when the backing API is loaded and callable. */
    boolean isAvailable();

    /**
     * Returns the current level for the specified player.
     *
     * @param playerUuid player UUID
     * @return player level, or {@code 1} when unknown
     */
    int getPlayerLevel(UUID playerUuid);

    /**
     * Called when a player joins. Providers that need ECS context (Store/Ref)
     * can cache it here.
     */
    default void onPlayerJoin(UUID uuid, Object store, Object ref) {}

    /** Called when a player leaves. */
    default void onPlayerLeave(UUID uuid) {}
}
