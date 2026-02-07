package com.crystalrealm.ecotaleincome.listeners;

import com.crystalrealm.ecotaleincome.EcoTaleIncomePlugin;
import com.crystalrealm.ecotaleincome.config.IncomeConfig;
import com.crystalrealm.ecotaleincome.economy.EconomyBridge;
import com.crystalrealm.ecotaleincome.protection.AntiFarmManager;
import com.crystalrealm.ecotaleincome.protection.CooldownTracker;
import com.crystalrealm.ecotaleincome.reward.MultiplierResolver;
import com.crystalrealm.ecotaleincome.reward.RewardCalculator;
import com.crystalrealm.ecotaleincome.reward.RewardResult;
import com.crystalrealm.ecotaleincome.rpg.RPGLevelingBridge;
import com.crystalrealm.ecotaleincome.util.MessageUtil;

import com.hypixel.hytale.event.EventRegistry;

import com.crystalrealm.ecotaleincome.util.PluginLogger;

import org.zuxaw.plugin.api.RPGLevelingAPI;
import org.zuxaw.plugin.api.EntityKillContext;
import org.zuxaw.plugin.api.XPSource;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Listens for mob kills and distributes currency rewards.
 *
 * <p>Two strategies are used:
 * <ol>
 *   <li><b>RPG Leveling integration</b> — if available, hooks into
 *       {@link RPGLevelingAPI#registerExperienceGainedListener} for
 *       {@link XPSource#ENTITY_KILL} events, providing entity level
 *       data for level-difference scaling.</li>
 *   <li><b>Native fallback</b> — there is no native EntityDeathEvent
 *       on the Hytale server. If RPG Leveling is absent, mob kill
 *       rewards are unavailable and a warning is logged.</li>
 * </ol>
 */
public class MobKillListener {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    private final EcoTaleIncomePlugin plugin;
    private final RewardCalculator rewardCalculator;
    private final EconomyBridge economyBridge;
    private final MultiplierResolver multiplierResolver;
    private final AntiFarmManager antiFarmManager;
    private final CooldownTracker cooldownTracker;

    private boolean registeredViaRPG = false;

    public MobKillListener(EcoTaleIncomePlugin plugin,
                           RewardCalculator rewardCalculator,
                           EconomyBridge economyBridge,
                           MultiplierResolver multiplierResolver,
                           AntiFarmManager antiFarmManager,
                           CooldownTracker cooldownTracker) {
        this.plugin = plugin;
        this.rewardCalculator = rewardCalculator;
        this.economyBridge = economyBridge;
        this.multiplierResolver = multiplierResolver;
        this.antiFarmManager = antiFarmManager;
        this.cooldownTracker = cooldownTracker;
    }

    // ── Registration ────────────────────────────────────────────

    /**
     * Registers the mob-kill listener using the best available strategy.
     *
     * @param eventRegistry    Hytale event registry (kept for API compatibility)
     * @param rpgBridge        RPG Leveling bridge (may be unavailable)
     */
    public void register(EventRegistry eventRegistry,
                         @Nullable RPGLevelingBridge rpgBridge) {

        IncomeConfig config = plugin.getConfigManager().getConfig();
        if (!config.getMobKills().isEnabled()) {
            LOGGER.info("MobKill rewards are disabled in config — skipping listener registration.");
            return;
        }

        // Strategy 1: RPG Leveling integration (preferred)
        if (rpgBridge != null && rpgBridge.isAvailable() && config.getMobKills().isUseRPGLeveling()) {
            registerViaRPGLeveling(rpgBridge);
            return;
        }

        // Strategy 2: Native fallback — not available on Hytale server
        registerNativeFallback(eventRegistry);
    }

    /**
     * Hooks into RPG Leveling's ExperienceGainedListener for
     * entity-kill events, giving us access to entity levels.
     */
    private void registerViaRPGLeveling(RPGLevelingBridge rpgBridge) {
        RPGLevelingAPI api = rpgBridge.getApi();
        if (api == null) {
            LOGGER.warn("RPGLevelingAPI instance is null — native mob kill events are not available.");
            LOGGER.warn("Install RPG Leveling to enable mob kill income.");
            return;
        }

        api.registerExperienceGainedListener(event -> {
            if (!event.getSource().equals(XPSource.ENTITY_KILL)) return;
            EntityKillContext killCtx = event.getEntityKillContext();
            if (killCtx == null) return;

            com.hypixel.hytale.server.core.universe.PlayerRef playerRef = event.getPlayer();
            UUID playerUuid = playerRef.getUuid();
            MessageUtil.cachePlayerRef(playerUuid, playerRef);
            handleRPGMobKill(playerUuid, killCtx);
        });

        registeredViaRPG = true;
        LOGGER.info("MobKill listener registered via RPG Leveling API (level-scaling enabled).");
    }

    /**
     * Native fallback: Hytale server does not expose an EntityDeathEvent
     * via EventBus. Death is handled via ECS DeathComponent changes which
     * are too complex for a simple listener. Mob kill rewards require RPG Leveling.
     */
    private void registerNativeFallback(EventRegistry eventRegistry) {
        LOGGER.warn("Native mob kill events are not available. Mob kill rewards require RPG Leveling plugin.");
        LOGGER.warn("Install RPG Leveling to enable mob kill income.");
    }

    // ── RPG Leveling handler ────────────────────────────────────

    private void handleRPGMobKill(UUID playerUuid, EntityKillContext killCtx) {
        try {
            IncomeConfig config = plugin.getConfigManager().getConfig();

            // Retrieve mob info from kill context
            UUID entityUuid = killCtx.getEntityUuid();
            int entityLevel = killCtx.hasEntityLevel() ? killCtx.getEntityLevel() : 0;

            // Try to get entity name via reflection (real API may have more methods)
            String entityType = resolveEntityName(killCtx, entityUuid);

            // ── Guard: cooldown / rate limit ──
            if (!cooldownTracker.canReceiveMobReward(playerUuid)) {
                debugLog("Player {} hit mob-kill rate limit.", playerUuid);
                return;
            }

            // ── Guard: anti-farm (same entity repeated kills) ──
            double farmMultiplier = antiFarmManager.getAndUpdateMobMultiplier(playerUuid, entityType);
            if (farmMultiplier <= 0.0) {
                debugLog("Anti-farm blocked reward for {} killing {}.", playerUuid, entityType);
                return;
            }

            // ── Resolve player level (for level-diff scaling) ──
            RPGLevelingBridge rpgBridge = plugin.getRPGBridge();
            int playerLevel = (rpgBridge != null) ? rpgBridge.getPlayerLevel(playerUuid) : 1;

            // ── Resolve VIP multiplier ──
            double vipMultiplier = multiplierResolver.resolve(playerUuid);

            // ── Determine mob tier ──
            String tier = resolveMobTier(entityType, entityLevel, config);

            // ── Calculate reward ──
            RewardResult result = rewardCalculator.calculateMobKill(
                    entityType, tier, playerLevel, entityLevel, vipMultiplier
            );

            if (!result.isValid()) {
                debugLog("No valid reward for tier={} entity={}.", tier, entityType);
                return;
            }

            // Apply anti-farm diminishing returns
            double finalAmount = result.amount() * farmMultiplier;
            finalAmount = Math.round(finalAmount * 100.0) / 100.0;
            if (finalAmount < 0.01) return;

            // ── Deposit ──
            String reason = String.format("MobKill: %s (Lvl %d)", entityType, entityLevel);
            boolean deposited = economyBridge.deposit(playerUuid, finalAmount, reason);

            if (deposited) {
                cooldownTracker.recordMobKill(playerUuid);
                notifyPlayer(playerUuid, finalAmount, entityType, "mob");
                debugLog("Rewarded {} → {} coins for killing {} (tier={}, lvl={}, farmMult={}).",
                        playerUuid, finalAmount, entityType, tier, entityLevel, farmMultiplier);
            }

        } catch (Throwable e) {
            LOGGER.error("Error processing RPG mob kill reward for player " + playerUuid, e);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    /**
     * Resolves the reward tier for a mob. Checks entity overrides first,
     * then falls back to heuristic tier classification.
     */
    private String resolveMobTier(String entityType, int entityLevel, IncomeConfig config) {
        // Check overrides first
        if (config.getMobKills().getEntityOverrides() != null &&
                config.getMobKills().getEntityOverrides().containsKey(entityType)) {
            return entityType; // Will be handled as an override in RewardCalculator
        }

        // Heuristic tier classification based on entity type and level
        String lower = entityType.toLowerCase();

        if (lower.contains("boss") || lower.contains("dragon") || lower.contains("titan")) {
            if (lower.contains("world") || lower.contains("raid")) return "WORLDBOSS";
            return "BOSS";
        }
        if (lower.contains("elite") || lower.contains("champion")) return "ELITE";
        if (lower.contains("miniboss") || lower.contains("chief")) return "MINIBOSS";

        // Level-based classification when entity level is known
        if (entityLevel > 0) {
            if (entityLevel >= 50) return "BOSS";
            if (entityLevel >= 35) return "ELITE";
            if (entityLevel >= 20) return "MINIBOSS";
            if (entityLevel >= 10) return "HOSTILE";
            if (entityLevel >= 5) return "PASSIVE";
            return "CRITTER";
        }

        // Passive / critter heuristics based on name
        if (lower.contains("chicken") || lower.contains("rabbit") || lower.contains("bug")
                || lower.contains("butterfly") || lower.contains("rat")) {
            return "CRITTER";
        }
        if (lower.contains("cow") || lower.contains("sheep") || lower.contains("pig")
                || lower.contains("deer") || lower.contains("horse")) {
            return "PASSIVE";
        }

        // Default to HOSTILE for unknown combat entities
        return "HOSTILE";
    }

    private boolean isBlacklisted(String entityType, IncomeConfig config) {
        List<String> blacklist = config.getMobKills().getEntityBlacklist();
        if (blacklist == null || blacklist.isEmpty()) return false;

        String lower = entityType.toLowerCase();
        return blacklist.stream()
                .anyMatch(entry -> lower.contains(entry.toLowerCase()));
    }

    private void notifyPlayer(UUID playerUuid, double amount, String source, String category) {
        IncomeConfig config = plugin.getConfigManager().getConfig();
        if (config.getGeneral().isNotifyOnReward()) {
            MessageUtil.sendRewardNotification(plugin, playerUuid, amount, source, category);
        }
    }

    private void debugLog(String message, Object... args) {
        IncomeConfig config = plugin.getConfigManager().getConfig();
        if (config.getGeneral().isDebugMode()) {
            LOGGER.info("[DEBUG] " + String.format(message.replace("{}", "%s"), args));
        }
    }

    public boolean isRegisteredViaRPG() {
        return registeredViaRPG;
    }

    /**
     * Пытается получить имя сущности из EntityKillContext через reflection.
     * Реальный RPGLeveling API может иметь методы getEntityName(), getEntityType(),
     * getPrefabName() и т.д., которых нет в стабах.
     */
    private String resolveEntityName(EntityKillContext killCtx, UUID entityUuid) {
        // Попытка 1: getEntityName()
        try {
            java.lang.reflect.Method m = killCtx.getClass().getMethod("getEntityName");
            Object result = m.invoke(killCtx);
            if (result instanceof String s && !s.isEmpty()) return s;
        } catch (Exception ignored) {}

        // Попытка 2: getEntityType()
        try {
            java.lang.reflect.Method m = killCtx.getClass().getMethod("getEntityType");
            Object result = m.invoke(killCtx);
            if (result instanceof String s && !s.isEmpty()) return s;
            if (result != null) return result.toString();
        } catch (Exception ignored) {}

        // Попытка 3: getPrefabName()
        try {
            java.lang.reflect.Method m = killCtx.getClass().getMethod("getPrefabName");
            Object result = m.invoke(killCtx);
            if (result instanceof String s && !s.isEmpty()) return s;
        } catch (Exception ignored) {}

        // Попытка 4: getEntityId()
        try {
            java.lang.reflect.Method m = killCtx.getClass().getMethod("getEntityId");
            Object result = m.invoke(killCtx);
            if (result instanceof String s && !s.isEmpty()) return s;
        } catch (Exception ignored) {}

        // Fallback: "Mob" — лучше чем UUID фрагмент
        debugLog("Could not resolve entity name from EntityKillContext, methods: {}",
                java.util.Arrays.toString(killCtx.getClass().getMethods()));
        return "Mob";
    }
}
