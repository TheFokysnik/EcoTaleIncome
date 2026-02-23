package com.crystalrealm.ecotaleincome.listeners;

import com.crystalrealm.ecotaleincome.EcoTaleIncomePlugin;
import com.crystalrealm.ecotaleincome.config.IncomeConfig;
import com.crystalrealm.ecotaleincome.economy.EconomyBridge;
import com.crystalrealm.ecotaleincome.leveling.LevelBridge;
import com.crystalrealm.ecotaleincome.protection.AntiFarmManager;
import com.crystalrealm.ecotaleincome.protection.CooldownTracker;
import com.crystalrealm.ecotaleincome.reward.MultiplierResolver;
import com.crystalrealm.ecotaleincome.reward.RewardCalculator;
import com.crystalrealm.ecotaleincome.reward.RewardResult;
import com.crystalrealm.ecotaleincome.util.MessageUtil;

import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.crystalrealm.ecotaleincome.util.PluginLogger;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

/**
 * Listens for mob kills and distributes currency rewards.
 *
 * <p>Two strategies are used:
 * <ol>
 *   <li><b>RPG Leveling integration</b> — if available, hooks into
 *       the XP listener via reflection + Proxy for entity-kill events,
 *       providing entity level data for level-difference scaling.</li>
 *   <li><b>Native fallback</b> — there is no native EntityDeathEvent
 *       on the Hytale server. If no level provider is absent, mob kill
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
    private boolean registeredViaNative = false;
    private boolean entityNameMethodsLogged = false;

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
     * @param levelBridge      Level bridge (may have RPG Leveling available)
     * @param entityStoreRegistry  ECS registry for native death system (nullable for API compat)
     */
    public void register(EventRegistry eventRegistry,
                         @Nullable LevelBridge levelBridge,
                         @Nullable ComponentRegistryProxy<EntityStore> entityStoreRegistry) {

        IncomeConfig config = plugin.getConfigManager().getConfig();
        if (!config.getMobKills().isEnabled()) {
            LOGGER.info("MobKill rewards are disabled in config — skipping listener registration.");
            return;
        }

        // Strategy 1: RPG Leveling integration via reflection (preferred — provides entity level data)
        if (levelBridge != null && levelBridge.isAvailable() && config.getMobKills().isUseRPGLeveling()) {
            Object rawApi = levelBridge.getRawRpgApi();
            if (rawApi != null) {
                registerViaReflection(rawApi);
                return;
            }
        }

        // Strategy 2: Native ECS DeathSystem (works with any level plugin or none)
        if (entityStoreRegistry != null) {
            registerViaNativeDeathSystem(entityStoreRegistry);
            return;
        }

        // Strategy 3: No method available
        registerNativeFallback(eventRegistry);
    }

    /** @deprecated Use {@link #register(EventRegistry, LevelBridge, ComponentRegistryProxy)} */
    @Deprecated
    public void register(EventRegistry eventRegistry,
                         @Nullable LevelBridge levelBridge) {
        register(eventRegistry, levelBridge, null);
    }

    /**
     * Hooks into RPG Leveling's ExperienceGainedListener via reflection + Proxy,
     * so we don't need a compile-time dependency on org.zuxaw.plugin.api.
     */
    private void registerViaReflection(Object rawApi) {
        try {
            // Find registerExperienceGainedListener method
            Method registerMethod = null;
            Class<?> listenerInterface = null;
            for (Method m : rawApi.getClass().getMethods()) {
                if (m.getName().equals("registerExperienceGainedListener") && m.getParameterCount() == 1) {
                    registerMethod = m;
                    listenerInterface = m.getParameterTypes()[0];
                    break;
                }
            }

            if (registerMethod == null || listenerInterface == null) {
                LOGGER.warn("Could not find registerExperienceGainedListener — mob kill rewards disabled.");
                return;
            }

            // Create Proxy for the listener interface
            final Class<?> iface = listenerInterface;
            Object proxy = Proxy.newProxyInstance(
                    iface.getClassLoader(),
                    new Class<?>[]{iface},
                    (proxyObj, method, args) -> {
                        if (method.getName().startsWith("on") && args != null && args.length == 1) {
                            handleXPEvent(args[0]);
                        }
                        return null;
                    }
            );

            registerMethod.invoke(rawApi, proxy);
            registeredViaRPG = true;
            LOGGER.info("MobKill listener registered via RPG Leveling API (reflection, level-scaling enabled).");

        } catch (Exception e) {
            LOGGER.error("Failed to register RPG Leveling listener via reflection: {}", e.getMessage());
        }
    }

    /**
     * Handles an XP event received via the Proxy listener.
     * Extracts source, player, and EntityKillContext via reflection.
     */
    private void handleXPEvent(Object event) {
        try {
            // Check source == ENTITY_KILL
            Method getSource = event.getClass().getMethod("getSource");
            Object source = getSource.invoke(event);
            if (source == null) return;
            String sourceName = source.toString();
            // Check if source is ENTITY_KILL (handle both enum and XPSource class)
            if (!sourceName.contains("ENTITY_KILL")) {
                try {
                    Method nameMethod = source.getClass().getMethod("name");
                    String name = (String) nameMethod.invoke(source);
                    if (!"ENTITY_KILL".equals(name)) return;
                } catch (NoSuchMethodException ex) {
                    // Try getName() or just rely on toString check
                    if (!sourceName.equals("ENTITY_KILL")) return;
                }
            }

            // Get EntityKillContext
            Method getCtx = event.getClass().getMethod("getEntityKillContext");
            Object killCtx = getCtx.invoke(event);
            if (killCtx == null) return;

            // Get PlayerRef
            Method getPlayer = event.getClass().getMethod("getPlayer");
            Object playerRef = getPlayer.invoke(event);
            if (playerRef == null) return;

            Method getUuid = playerRef.getClass().getMethod("getUuid");
            UUID playerUuid = (UUID) getUuid.invoke(playerRef);

            MessageUtil.cachePlayerRef(playerUuid, playerRef);
            handleRPGMobKill(playerUuid, killCtx);

        } catch (Exception e) {
            LOGGER.debug("Error handling XP event: {}", e.getMessage());
        }
    }

    /**
     * Registers a native ECS {@link MobDeathSystem} that detects NPC deaths
     * via the DeathComponent. Works with any level provider (or none).
     */
    private void registerViaNativeDeathSystem(ComponentRegistryProxy<EntityStore> registry) {
        MobDeathSystem deathSystem = new MobDeathSystem((playerUuid, npcTypeId) -> {
            handleNativeMobKill(playerUuid, npcTypeId);
        });
        registry.registerSystem(deathSystem);
        registeredViaNative = true;
        LOGGER.info("MobKill listener registered via native ECS DeathSystem (level-scaling uses LevelBridge).");
    }

    /**
     * Last resort fallback when neither RPG Leveling nor ECS registry are available.
     */
    private void registerNativeFallback(EventRegistry eventRegistry) {
        LOGGER.warn("No mob kill detection method available. Mob kill rewards disabled.");
        LOGGER.warn("Install RPG Leveling or ensure ECS registry is passed to enable mob kill income.");
    }

    // ── RPG Leveling handler ────────────────────────────────────

    private void handleRPGMobKill(UUID playerUuid, Object killCtx) {
        try {
            IncomeConfig config = plugin.getConfigManager().getConfig();

            // Retrieve mob info from kill context via reflection
            UUID entityUuid = null;
            int entityLevel = 0;
            try {
                Method getEntityUuid = killCtx.getClass().getMethod("getEntityUuid");
                entityUuid = (UUID) getEntityUuid.invoke(killCtx);
            } catch (Exception ignored) {}

            try {
                Method hasLvl = killCtx.getClass().getMethod("hasEntityLevel");
                Object has = hasLvl.invoke(killCtx);
                if (has instanceof Boolean b && b) {
                    Method getLvl = killCtx.getClass().getMethod("getEntityLevel");
                    Object lvl = getLvl.invoke(killCtx);
                    if (lvl instanceof Number n) entityLevel = n.intValue();
                }
            } catch (Exception ignored) {}

            // Try to get entity name via reflection
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

            // ── Resolve player level (via LevelBridge) ──
            int playerLevel = plugin.getLevelBridge().getPlayerLevel(playerUuid);

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

    // ── Native ECS handler ────────────────────────────────────

    /**
     * Handles a mob kill detected via the native ECS DeathSystem.
     * No entity level is available (only RPG Leveling provides that),
     * so level scaling uses player level only with tier-based heuristics.
     */
    private void handleNativeMobKill(UUID playerUuid, String entityType) {
        try {
            IncomeConfig config = plugin.getConfigManager().getConfig();

            // ── Guard: cooldown / rate limit ──
            if (!cooldownTracker.canReceiveMobReward(playerUuid)) {
                debugLog("Player {} hit mob-kill rate limit.", playerUuid);
                return;
            }

            // ── Guard: anti-farm ──
            double farmMultiplier = antiFarmManager.getAndUpdateMobMultiplier(playerUuid, entityType);
            if (farmMultiplier <= 0.0) {
                debugLog("Anti-farm blocked reward for {} killing {}.", playerUuid, entityType);
                return;
            }

            // ── Resolve player level (via LevelBridge — works with any provider) ──
            int playerLevel = plugin.getLevelBridge().getPlayerLevel(playerUuid);

            // ── Resolve VIP multiplier ──
            double vipMultiplier = multiplierResolver.resolve(playerUuid);

            // ── Determine mob tier (no entity level available in native mode) ──
            String tier = resolveMobTier(entityType, 0, config);

            // ── Calculate reward ──
            RewardResult result = rewardCalculator.calculateMobKill(
                    entityType, tier, playerLevel, 0, vipMultiplier
            );

            if (result == null || !result.isValid()) {
                debugLog("No valid reward for tier={} entity={}.", tier, entityType);
                return;
            }

            // Apply anti-farm diminishing returns
            double finalAmount = result.amount() * farmMultiplier;
            finalAmount = Math.round(finalAmount * 100.0) / 100.0;
            if (finalAmount < 0.01) return;

            // ── Deposit ──
            String reason = String.format("MobKill: %s (native)", entityType);
            boolean deposited = economyBridge.deposit(playerUuid, finalAmount, reason);

            if (deposited) {
                cooldownTracker.recordMobKill(playerUuid);
                notifyPlayer(playerUuid, finalAmount, entityType, "mob");
                debugLog("Rewarded {} → {} coins for killing {} (tier={}, native, farmMult={}).",
                        playerUuid, finalAmount, entityType, tier, farmMultiplier);
            }

        } catch (Throwable e) {
            LOGGER.error("Error processing native mob kill reward for player " + playerUuid, e);
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

    public boolean isRegisteredViaNative() {
        return registeredViaNative;
    }

    /**
     * Пытается получить имя сущности из kill context through reflection.
     * The real RPGLeveling API may have methods like getEntityName(), getEntityType(),
     * getPrefabName() etc. that are not in stubs.
     */
    private String resolveEntityName(Object killCtx, UUID entityUuid) {
        // Retrieve all public methods for diagnostics
        java.lang.reflect.Method[] methods = killCtx.getClass().getMethods();

        // Log available methods ONCE so we can discover the real API
        if (!entityNameMethodsLogged) {
            entityNameMethodsLogged = true;
            StringBuilder sb = new StringBuilder("EntityKillContext methods:");
            for (java.lang.reflect.Method m : methods) {
                if (m.getDeclaringClass() == Object.class) continue;
                sb.append("\n  ").append(m.getReturnType().getSimpleName())
                  .append(" ").append(m.getName()).append("(");
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(params[i].getSimpleName());
                }
                sb.append(")");
            }
            LOGGER.info(sb.toString());
        }

        // Try known method names via reflection (real class may have any of these)
        String[] methodNames = {
            "getEntityName", "getEntityType", "getPrefabName",
            "getEntityId", "getNpcTypeId", "getRole", "getNpcId",
            "getEntityTypeName", "getMobName", "getMobType"
        };
        for (String name : methodNames) {
            try {
                java.lang.reflect.Method m = killCtx.getClass().getMethod(name);
                Object result = m.invoke(killCtx);
                if (result instanceof String s && !s.isEmpty()) {
                    LOGGER.info("Entity name resolved via {}(): '{}'", name, s);
                    return s;
                }
                if (result != null) {
                    String str = result.toString();
                    if (!str.isEmpty() && !str.contains("@")) {
                        LOGGER.info("Entity name resolved via {}(): '{}'", name, str);
                        return str;
                    }
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                LOGGER.debug("resolveEntityName: {}() threw: {}", name, e.getMessage());
            }
        }

        // Try all zero-arg methods that return String
        for (java.lang.reflect.Method m : methods) {
            if (m.getParameterCount() != 0) continue;
            if (m.getReturnType() != String.class) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            String mName = m.getName();
            // Skip known UUID/level methods
            if (mName.equals("toString") || mName.equals("getClass")) continue;
            try {
                Object result = m.invoke(killCtx);
                if (result instanceof String s && !s.isEmpty()) {
                    LOGGER.info("Entity name resolved via {}(): '{}'", mName, s);
                    return s;
                }
            } catch (Exception ignored) {}
        }

        // Fallback: "Mob"
        LOGGER.info("Could not resolve entity name from EntityKillContext for entity {}", entityUuid);
        return "Mob";
    }
}
