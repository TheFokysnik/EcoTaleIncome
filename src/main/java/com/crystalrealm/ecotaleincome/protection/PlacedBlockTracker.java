package com.crystalrealm.ecotaleincome.protection;

import com.crystalrealm.ecotaleincome.util.PluginLogger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks blocks placed by players to prevent place-and-break income exploits.
 *
 * <p>When a player places a block, the position is recorded with a timestamp.
 * If any player later breaks a block at a tracked position, the reward is denied.</p>
 *
 * <p>Positions are stored as compact long keys for memory efficiency.
 * Entries expire after a configurable duration and are periodically cleaned up.</p>
 *
 * <h3>Example exploit prevented:</h3>
 * <pre>
 *   1. Player places Oak Log block
 *   2. Player breaks it → would earn woodcutting reward
 *   3. Repeat infinitely → infinite money
 * </pre>
 * <p>With this tracker, step 2 is denied because the position is marked as player-placed.</p>
 */
public class PlacedBlockTracker {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    /**
     * Placed block positions: positionKey → placement timestamp.
     * Timestamps allow age-based cleanup.
     */
    private final Map<Long, Long> placedPositions = new ConcurrentHashMap<>();

    /** Maximum tracked positions before emergency cleanup */
    private static final int MAX_TRACKED_POSITIONS = 500_000;

    // ── Public API ──────────────────────────────────────────────

    /**
     * Records a block placement at the given position.
     *
     * @param x block X coordinate
     * @param y block Y coordinate
     * @param z block Z coordinate
     */
    public void recordPlacement(int x, int y, int z) {
        if (placedPositions.size() >= MAX_TRACKED_POSITIONS) {
            LOGGER.warn("PlacedBlockTracker reached {} entries — performing emergency cleanup.",
                    MAX_TRACKED_POSITIONS);
            cleanupOldest();
        }
        placedPositions.put(positionKey(x, y, z), System.currentTimeMillis());
    }

    /**
     * Checks if a block at the given position was placed by a player.
     *
     * @return true if this position was recorded as player-placed
     */
    public boolean isPlayerPlaced(int x, int y, int z) {
        return placedPositions.containsKey(positionKey(x, y, z));
    }

    /**
     * Removes tracking for a specific position.
     */
    public void removePosition(int x, int y, int z) {
        placedPositions.remove(positionKey(x, y, z));
    }

    // ── Cleanup ─────────────────────────────────────────────────

    /**
     * Removes positions older than {@code maxAgeMs} milliseconds.
     * Should be called periodically (e.g., every 5 minutes).
     *
     * @param maxAgeMs maximum age in milliseconds
     */
    public void cleanup(long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        int before = placedPositions.size();
        placedPositions.entrySet().removeIf(e -> e.getValue() < cutoff);
        int removed = before - placedPositions.size();
        if (removed > 0) {
            LOGGER.debug("PlacedBlockTracker cleanup: removed {} expired entries, {} remaining.",
                    removed, placedPositions.size());
        }
    }

    /**
     * Emergency cleanup — removes the oldest half of entries.
     */
    private void cleanupOldest() {
        long median = placedPositions.values().stream()
                .sorted()
                .skip(placedPositions.size() / 2)
                .findFirst()
                .orElse(System.currentTimeMillis());
        placedPositions.entrySet().removeIf(e -> e.getValue() < median);
    }

    /**
     * Clears all tracked positions. Called on plugin shutdown.
     */
    public void clearAll() {
        placedPositions.clear();
    }

    /**
     * Returns the number of currently tracked positions.
     */
    public int size() {
        return placedPositions.size();
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Encodes block coordinates into a compact long key.
     * Supports coordinates in range [-1,048,576, +1,048,575] per axis.
     */
    private static long positionKey(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF))
                | ((long) (y & 0x1FFFFF) << 21)
                | ((long) (z & 0x1FFFFF) << 42);
    }
}
