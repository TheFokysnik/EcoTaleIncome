package com.crystalrealm.ecotaleincome.util;

import com.crystalrealm.ecotaleincome.EcoTaleIncomePlugin;
import com.crystalrealm.ecotaleincome.lang.LangManager;
import com.hypixel.hytale.server.core.HytaleServer;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.*;

/**
 * Aggregates reward notifications over a short time window and sends
 * a single combined HUD notification instead of spamming per-event.
 *
 * <p>Uses the native Hytale {@code NotificationUtil.sendNotification()} API
 * to display a beautiful popup on the right side of the screen — like the
 * "New Item Found!" notification from NewItemIndicator.</p>
 *
 * <p>Example (after mining 3 iron ores and 2 copper ores within 1.5 sec):</p>
 * <pre>
 *   [HUD Popup]
 *   Title:       +12.50
 *   Description: Iron Ore ×3, Copper Ore ×2
 * </pre>
 *
 * <p>The aggregation window is configurable via {@code General.AggregateWindowMs}
 * in the plugin config (default 1500 ms).</p>
 */
public final class RewardAggregator {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    /** Default aggregation window in milliseconds. */
    private static final long DEFAULT_WINDOW_MS = 1500;

    private final EcoTaleIncomePlugin plugin;

    /** Per-player pending reward entries (thread-safe). */
    private final ConcurrentHashMap<UUID, PendingRewards> pending = new ConcurrentHashMap<>();

    /** Scheduler for flush timers. */
    private final ScheduledExecutorService scheduler;

    public RewardAggregator(@Nonnull EcoTaleIncomePlugin plugin) {
        this.plugin = plugin;
        this.scheduler = HytaleServer.SCHEDULED_EXECUTOR;
    }

    // ─── Public API ─────────────────────────────────────────────

    /**
     * Submit a reward entry for aggregation.
     * If this is the first entry in the current window, a flush timer is started.
     */
    public void submit(@Nonnull UUID playerUuid, double amount,
                       @Nonnull String source, @Nonnull String category) {
        long windowMs = getWindowMs();

        LOGGER.info("Reward submitted: player={}, amount={}, source={}, category={}, window={}ms",
                playerUuid, MessageUtil.formatCoins(amount), source, category, windowMs);

        PendingRewards rewards = pending.computeIfAbsent(playerUuid, k -> new PendingRewards());

        synchronized (rewards) {
            rewards.add(amount, source, category);

            // Schedule flush if not already scheduled
            if (rewards.flushTask == null) {
                rewards.flushTask = scheduler.schedule(
                        () -> flush(playerUuid),
                        windowMs, TimeUnit.MILLISECONDS
                );
            }
        }
    }

    /**
     * Flush pending rewards for a player — format and send the aggregated HUD notification.
     */
    private void flush(@Nonnull UUID playerUuid) {
        PendingRewards rewards = pending.remove(playerUuid);
        if (rewards == null) return;

        List<RewardEntry> entries;
        synchronized (rewards) {
            entries = new ArrayList<>(rewards.entries);
            rewards.entries.clear();
            rewards.flushTask = null;
        }

        if (entries.isEmpty()) return;

        // Calculate total
        double total = 0;
        for (RewardEntry e : entries) total += e.amount;

        // Determine dominant category
        String dominantCategory = entries.get(0).category;

        // Build source list for description
        String sourceList = buildSourceList(entries);

        LOGGER.info("Flushing {} reward(s) for {} — total: {}, sources: {}",
                entries.size(), playerUuid, MessageUtil.formatCoins(total), sourceList);

        // Send via native HUD notification
        boolean sent = MessageUtil.sendAggregatedHudNotification(
                playerUuid, total, sourceList, dominantCategory);

        if (!sent) {
            LOGGER.warn("Failed to deliver aggregated notification to {}", playerUuid);
        }
    }

    /** Shutdown: flush all remaining rewards immediately. */
    public void shutdown() {
        for (UUID uuid : new ArrayList<>(pending.keySet())) {
            flush(uuid);
        }
        pending.clear();
    }

    // ─── Source List Building ────────────────────────────────────

    /**
     * Build the aggregated source description.
     *
     * <p>Format: {@code Iron Ore ×3, Copper Ore ×2}</p>
     */
    private String buildSourceList(@Nonnull List<RewardEntry> entries) {
        // Group by source name
        Map<String, SourceAgg> sourceMap = new LinkedHashMap<>();
        for (RewardEntry e : entries) {
            String displayName = MessageUtil.formatSourceName(e.source);
            SourceAgg agg = sourceMap.computeIfAbsent(displayName, k -> new SourceAgg());
            agg.count++;
            agg.amount += e.amount;
        }

        // Build: "Iron Ore ×3, Copper Ore ×2"
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, SourceAgg> entry : sourceMap.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(entry.getKey());
            if (entry.getValue().count > 1) {
                sb.append(" \u00d7").append(entry.getValue().count);
            }
        }

        return sb.toString();
    }

    // ─── Config ─────────────────────────────────────────────────

    private long getWindowMs() {
        try {
            long ms = plugin.getConfigManager().getConfig().getGeneral().getAggregateWindowMs();
            return ms > 0 ? ms : DEFAULT_WINDOW_MS;
        } catch (Exception e) {
            return DEFAULT_WINDOW_MS;
        }
    }

    // ─── Internal Data Structures ───────────────────────────────

    /** A single reward event before aggregation. */
    private static class RewardEntry {
        final double amount;
        final String source;
        final String category;

        RewardEntry(double amount, String source, String category) {
            this.amount = amount;
            this.source = source;
            this.category = category;
        }
    }

    /** Aggregated info for a single source name. */
    private static class SourceAgg {
        int count;
        double amount;
    }

    /** Pending rewards for a single player during the aggregation window. */
    private static class PendingRewards {
        final List<RewardEntry> entries = new ArrayList<>();
        ScheduledFuture<?> flushTask;

        void add(double amount, String source, String category) {
            entries.add(new RewardEntry(amount, source, category));
        }
    }
}
