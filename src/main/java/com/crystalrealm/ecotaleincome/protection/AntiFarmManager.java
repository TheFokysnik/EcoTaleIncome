package com.crystalrealm.ecotaleincome.protection;

import com.crystalrealm.ecotaleincome.config.IncomeConfig;

import com.crystalrealm.ecotaleincome.util.PluginLogger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-farm system that applies diminishing returns when a player
 * repeatedly performs the same action on the same entity/block type.
 *
 * <p>The system tracks how many times a player has farmed a specific
 * resource type within a sliding window. After exceeding the threshold,
 * a decay multiplier is applied that reduces rewards progressively
 * down to a configurable minimum.</p>
 *
 * <h3>Formula</h3>
 * <pre>
 *   multiplier = max(minMultiplier, 1.0 - (excessCount × decayRate))
 * </pre>
 * Where:
 * <ul>
 *   <li>{@code excessCount} = current count − threshold</li>
 *   <li>{@code decayRate} = per-action reduction (e.g., 0.05 = 5% per action)</li>
 *   <li>{@code minMultiplier} = floor multiplier (e.g., 0.1 = never below 10%)</li>
 * </ul>
 *
 * <h3>Example</h3>
 * <p>With threshold=20, decayRate=0.05, minMultiplier=0.1:</p>
 * <ul>
 *   <li>Kill #1–20: full reward (multiplier=1.0)</li>
 *   <li>Kill #21: multiplier=0.95</li>
 *   <li>Kill #30: multiplier=0.50</li>
 *   <li>Kill #38+: multiplier=0.10 (floor)</li>
 * </ul>
 */
public class AntiFarmManager {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    /** Tracking data: playerUUID → ActionTracker */
    private final Map<UUID, PlayerFarmTracker> playerTrackers = new ConcurrentHashMap<>();

    private final IncomeConfig.AntiFarmSection config;

    /** Time window in milliseconds for tracking (5 minutes) */
    private static final long TRACKING_WINDOW_MS = 300_000;

    public AntiFarmManager(IncomeConfig.AntiFarmSection config) {
        this.config = config;
    }

    // ── Public API ──────────────────────────────────────────────

    /**
     * Returns the anti-farm multiplier for a mob kill and increments
     * the counter. Returns 0.0 if anti-farm is disabled (always full).
     */
    public double getAndUpdateMobMultiplier(UUID playerUuid, String entityType) {
        if (!config.isEnabled()) return 1.0;
        return getAndUpdate(playerUuid, "mob:" + normalizeKey(entityType));
    }

    /**
     * Returns the anti-farm multiplier for mining a specific ore.
     */
    public double getAndUpdateMiningMultiplier(UUID playerUuid, String oreType) {
        if (!config.isEnabled()) return 1.0;
        return getAndUpdate(playerUuid, "ore:" + normalizeKey(oreType));
    }

    /**
     * Returns the anti-farm multiplier for chopping a specific tree.
     */
    public double getAndUpdateWoodMultiplier(UUID playerUuid, String treeType) {
        if (!config.isEnabled()) return 1.0;
        return getAndUpdate(playerUuid, "wood:" + normalizeKey(treeType));
    }

    /**
     * Returns the anti-farm multiplier for harvesting a specific crop.
     */
    public double getAndUpdateFarmingMultiplier(UUID playerUuid, String cropType) {
        if (!config.isEnabled()) return 1.0;
        return getAndUpdate(playerUuid, "crop:" + normalizeKey(cropType));
    }

    // ── Core Logic ──────────────────────────────────────────────

    /**
     * Gets the current diminishing-returns multiplier for this player+action,
     * then increments the counter.
     *
     * @return multiplier between {@code minMultiplier} and {@code 1.0},
     *         or {@code 0.0} if completely blocked.
     */
    private double getAndUpdate(UUID playerUuid, String actionKey) {
        PlayerFarmTracker tracker = playerTrackers.computeIfAbsent(
                playerUuid, k -> new PlayerFarmTracker()
        );

        long now = System.currentTimeMillis();
        int count = tracker.getCountInWindow(actionKey, now - TRACKING_WINDOW_MS);

        // Calculate multiplier based on excess over threshold
        double multiplier = calculateMultiplier(count);

        // Record this action
        tracker.recordAction(actionKey, now);

        return multiplier;
    }

    /**
     * Calculates the diminishing-returns multiplier based on repetition count.
     */
    private double calculateMultiplier(int count) {
        int threshold = config.getSameEntityThreshold();
        double decayRate = config.getDecayRate();
        double minMultiplier = config.getMinMultiplier();

        if (count < threshold) {
            return 1.0; // Below threshold = full reward
        }

        int excess = count - threshold;
        double multiplier = 1.0 - (excess * decayRate);
        return Math.max(minMultiplier, multiplier);
    }

    // ── Statistics ───────────────────────────────────────────────

    /**
     * Returns the current farm count for a player's specific action type.
     */
    public int getCurrentCount(UUID playerUuid, String actionKey) {
        PlayerFarmTracker tracker = playerTrackers.get(playerUuid);
        if (tracker == null) return 0;
        return tracker.getCountInWindow(actionKey, System.currentTimeMillis() - TRACKING_WINDOW_MS);
    }

    /**
     * Returns the effective multiplier without incrementing the counter.
     */
    public double peekMultiplier(UUID playerUuid, String category, String type) {
        if (!config.isEnabled()) return 1.0;

        String actionKey = category + ":" + normalizeKey(type);
        PlayerFarmTracker tracker = playerTrackers.get(playerUuid);
        if (tracker == null) return 1.0;

        int count = tracker.getCountInWindow(actionKey, System.currentTimeMillis() - TRACKING_WINDOW_MS);
        return calculateMultiplier(count);
    }

    // ── Cleanup ─────────────────────────────────────────────────

    /**
     * Removes expired entries from all trackers to prevent memory leaks.
     */
    public void cleanup() {
        long cutoff = System.currentTimeMillis() - TRACKING_WINDOW_MS;

        playerTrackers.entrySet().removeIf(entry -> {
            entry.getValue().pruneOldEntries(cutoff);
            return entry.getValue().isEmpty();
        });
    }

    /**
     * Clears all anti-farm data. Called on plugin shutdown.
     */
    public void clearAll() {
        playerTrackers.clear();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private static String normalizeKey(String key) {
        return key.toLowerCase().trim();
    }

    // ── Inner Tracker Class ─────────────────────────────────────

    /**
     * Per-player tracking of farming actions with timestamps.
     */
    private static class PlayerFarmTracker {

        /** actionKey → sorted list of timestamps */
        private final Map<String, java.util.List<Long>> actions = new ConcurrentHashMap<>();

        void recordAction(String actionKey, long timestamp) {
            actions.computeIfAbsent(actionKey,
                            k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>()))
                    .add(timestamp);
        }

        int getCountInWindow(String actionKey, long windowStart) {
            java.util.List<Long> timestamps = actions.get(actionKey);
            if (timestamps == null) return 0;

            synchronized (timestamps) {
                // Remove old entries while counting
                timestamps.removeIf(t -> t < windowStart);
                return timestamps.size();
            }
        }

        void pruneOldEntries(long cutoff) {
            actions.values().forEach(timestamps -> {
                synchronized (timestamps) {
                    timestamps.removeIf(t -> t < cutoff);
                }
            });
            actions.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty();
                }
            });
        }

        boolean isEmpty() {
            return actions.isEmpty();
        }
    }
}
