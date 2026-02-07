package com.crystalrealm.ecotaleincome.protection;

import com.crystalrealm.ecotaleincome.config.IncomeConfig;

import com.crystalrealm.ecotaleincome.util.PluginLogger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-player reward frequency and applies rate limits.
 *
 * <p>Prevents excessive reward payouts by enforcing:
 * <ul>
 *   <li><b>Global rate limit</b> — maximum rewards per minute across all categories.</li>
 *   <li><b>Per-category rate</b> — separate tracking for mob kills, mining,
 *       woodcutting, and farming.</li>
 *   <li><b>Same-block cooldown</b> — prevents rapid break-place-break abuse
 *       by requiring a minimum interval between rewards for the same position.</li>
 * </ul>
 *
 * <p>All data is stored in memory and periodically cleaned up to prevent leaks.</p>
 */
public class CooldownTracker {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    /** Rolling window entries: playerUUID → category → list of timestamps */
    private final Map<UUID, PlayerCooldownData> playerData = new ConcurrentHashMap<>();

    /** Same-block position tracking: playerUUID → position_key → last_reward_time */
    private final Map<UUID, Map<String, Long>> blockCooldowns = new ConcurrentHashMap<>();

    private final IncomeConfig.ProtectionSection protectionConfig;

    public CooldownTracker(IncomeConfig.ProtectionSection protectionConfig) {
        this.protectionConfig = protectionConfig;
    }

    // ── Rate Limiting ───────────────────────────────────────────

    public boolean canReceiveMobReward(UUID playerUuid) {
        return canReceiveReward(playerUuid, "mob");
    }

    public boolean canReceiveMiningReward(UUID playerUuid) {
        return canReceiveReward(playerUuid, "mining");
    }

    public boolean canReceiveWoodcuttingReward(UUID playerUuid) {
        return canReceiveReward(playerUuid, "woodcutting");
    }

    public boolean canReceiveFarmingReward(UUID playerUuid) {
        return canReceiveReward(playerUuid, "farming");
    }

    /**
     * Checks whether a player can receive a reward in the given category,
     * based on the configured {@code MaxRewardsPerMinute} limit.
     */
    private boolean canReceiveReward(UUID playerUuid, String category) {
        int maxPerMinute = protectionConfig.getMaxRewardsPerMinute();
        if (maxPerMinute <= 0) return true; // Disabled

        PlayerCooldownData data = playerData.computeIfAbsent(playerUuid, k -> new PlayerCooldownData());
        long now = System.currentTimeMillis();
        long windowStart = now - 60_000; // 1-minute rolling window

        // Prune old entries & count recent ones
        int recentCount = data.countAndPruneEntries(category, windowStart);
        return recentCount < maxPerMinute;
    }

    // ── Recording ───────────────────────────────────────────────

    public void recordMobKill(UUID playerUuid) {
        recordReward(playerUuid, "mob");
    }

    public void recordMining(UUID playerUuid) {
        recordReward(playerUuid, "mining");
    }

    public void recordWoodcutting(UUID playerUuid) {
        recordReward(playerUuid, "woodcutting");
    }

    public void recordFarming(UUID playerUuid) {
        recordReward(playerUuid, "farming");
    }

    private void recordReward(UUID playerUuid, String category) {
        PlayerCooldownData data = playerData.computeIfAbsent(playerUuid, k -> new PlayerCooldownData());
        data.addEntry(category, System.currentTimeMillis());
    }

    // ── Same-Block Cooldown ─────────────────────────────────────

    /**
     * Checks whether enough time has passed since the last reward
     * at the exact same block position for this player.
     *
     * <p>This prevents the exploit where a player places and breaks
     * the same block repeatedly at one location.</p>
     */
    public boolean canReceiveBlockReward(UUID playerUuid, int x, int y, int z) {
        long cooldownMs = protectionConfig.getSameBlockCooldownMs();
        if (cooldownMs <= 0) return true; // Disabled

        String posKey = x + "," + y + "," + z;
        Map<String, Long> playerBlocks = blockCooldowns.get(playerUuid);
        if (playerBlocks == null) return true;

        Long lastTime = playerBlocks.get(posKey);
        if (lastTime == null) return true;

        return (System.currentTimeMillis() - lastTime) >= cooldownMs;
    }

    /**
     * Records a block break at a specific position.
     */
    public void recordBlockBreak(UUID playerUuid, int x, int y, int z) {
        String posKey = x + "," + y + "," + z;
        blockCooldowns
                .computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>())
                .put(posKey, System.currentTimeMillis());
    }

    // ── Statistics ───────────────────────────────────────────────

    /**
     * Returns the total number of rewards a player has received
     * in the current rolling window for all categories.
     */
    public int getTotalRewardsInWindow(UUID playerUuid) {
        PlayerCooldownData data = playerData.get(playerUuid);
        if (data == null) return 0;
        long windowStart = System.currentTimeMillis() - 60_000;
        return data.countAllEntries(windowStart);
    }

    /**
     * Returns the remaining rewards a player can receive this minute.
     */
    public int getRemainingRewards(UUID playerUuid) {
        int maxPerMinute = protectionConfig.getMaxRewardsPerMinute();
        if (maxPerMinute <= 0) return Integer.MAX_VALUE;
        return Math.max(0, maxPerMinute - getTotalRewardsInWindow(playerUuid));
    }

    // ── Cleanup ─────────────────────────────────────────────────

    /**
     * Removes expired entries and data for offline players.
     * Should be called periodically (e.g., every 5 minutes).
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        long windowStart = now - 60_000;
        long blockCooldownMax = now - Math.max(protectionConfig.getSameBlockCooldownMs() * 2, 30_000);

        // Clean rate-limit data
        playerData.entrySet().removeIf(entry -> {
            entry.getValue().pruneAll(windowStart);
            return entry.getValue().isEmpty();
        });

        // Clean block cooldowns
        blockCooldowns.entrySet().removeIf(entry -> {
            entry.getValue().entrySet().removeIf(posEntry -> posEntry.getValue() < blockCooldownMax);
            return entry.getValue().isEmpty();
        });
    }

    /**
     * Clears all tracking data. Called on plugin shutdown.
     */
    public void clearAll() {
        playerData.clear();
        blockCooldowns.clear();
    }

    // ── Inner Class ─────────────────────────────────────────────

    /**
     * Per-player cooldown data with thread-safe category-based timestamp tracking.
     */
    private static class PlayerCooldownData {

        private final Map<String, java.util.List<Long>> categoryTimestamps = new ConcurrentHashMap<>();

        void addEntry(String category, long timestamp) {
            categoryTimestamps
                    .computeIfAbsent(category, k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>()))
                    .add(timestamp);
        }

        int countAndPruneEntries(String category, long windowStart) {
            java.util.List<Long> timestamps = categoryTimestamps.get(category);
            if (timestamps == null) return 0;

            synchronized (timestamps) {
                timestamps.removeIf(t -> t < windowStart);
                return timestamps.size();
            }
        }

        int countAllEntries(long windowStart) {
            int total = 0;
            for (java.util.List<Long> timestamps : categoryTimestamps.values()) {
                synchronized (timestamps) {
                    for (Long t : timestamps) {
                        if (t >= windowStart) total++;
                    }
                }
            }
            return total;
        }

        void pruneAll(long windowStart) {
            categoryTimestamps.values().forEach(timestamps -> {
                synchronized (timestamps) {
                    timestamps.removeIf(t -> t < windowStart);
                }
            });
            categoryTimestamps.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty();
                }
            });
        }

        boolean isEmpty() {
            return categoryTimestamps.isEmpty();
        }
    }
}
