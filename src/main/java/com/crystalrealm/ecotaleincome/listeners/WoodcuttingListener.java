package com.crystalrealm.ecotaleincome.listeners;

import com.crystalrealm.ecotaleincome.EcoTaleIncomePlugin;
import com.crystalrealm.ecotaleincome.config.IncomeConfig;
import com.crystalrealm.ecotaleincome.economy.EconomyBridge;
import com.crystalrealm.ecotaleincome.protection.AntiFarmManager;
import com.crystalrealm.ecotaleincome.protection.CooldownTracker;
import com.crystalrealm.ecotaleincome.reward.MultiplierResolver;
import com.crystalrealm.ecotaleincome.reward.RewardCalculator;
import com.crystalrealm.ecotaleincome.reward.RewardResult;
import com.crystalrealm.ecotaleincome.util.MessageUtil;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.crystalrealm.ecotaleincome.util.PluginLogger;

import java.util.Map;
import java.util.UUID;

/**
 * Listens for block break events and rewards players for chopping trees.
 *
 * <p>Wood detection uses block type ID patterns ({@code _log}, {@code _wood},
 * {@code stripped_log}) and block type group checks. Optionally requires the
 * player to hold an axe-type tool.</p>
 *
 * <p>Uses the ECS {@link EntityEventSystem} pattern to handle
 * {@link BreakBlockEvent} via the entity store registry.</p>
 */
public class WoodcuttingListener {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    private final EcoTaleIncomePlugin plugin;
    private final RewardCalculator rewardCalculator;
    private final EconomyBridge economyBridge;
    private final MultiplierResolver multiplierResolver;
    private final AntiFarmManager antiFarmManager;
    private final CooldownTracker cooldownTracker;

    public WoodcuttingListener(EcoTaleIncomePlugin plugin,
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

    public void register(ComponentRegistryProxy<EntityStore> registry) {
        IncomeConfig config = plugin.getConfigManager().getConfig();
        if (!config.getWoodcutting().isEnabled()) {
            LOGGER.info("Woodcutting rewards are disabled — skipping listener registration.");
            return;
        }

        registry.registerSystem(new WoodcuttingBreakSystem());
        LOGGER.info("Woodcutting listener registered ({} tree types configured).",
                config.getWoodcutting().getTrees().size());
    }

    // ── ECS Event System (inner class) ──────────────────────────

    /**
     * ECS system that handles {@link BreakBlockEvent} for woodcutting rewards.
     * Registered via {@link ComponentRegistryProxy#registerSystem}.
     */
    private class WoodcuttingBreakSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

        protected WoodcuttingBreakSystem() {
            super(BreakBlockEvent.class);
        }

        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk,
                           Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer,
                           BreakBlockEvent event) {
            try {
                PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());
                if (playerRef == null) return;

                BlockType blockType = event.getBlockType();
                if (blockType == null) return;
                String blockId = blockType.getId();

                IncomeConfig config = plugin.getConfigManager().getConfig();
                UUID playerUuid = playerRef.getUuid();
                MessageUtil.cachePlayerRef(playerUuid, playerRef);

                boolean isWood = isWoodBlock(blockType, blockId);
                debugLog("Block broken: id={}, group={}, isWood={}", blockId,
                        blockType.getGroup() != null ? blockType.getGroup() : "null", isWood);
                if (!isWood) return;

                // ── Guard: tool requirement ──
                if (config.getWoodcutting().isToolRequired() && !isHoldingAxe(event)) {
                    debugLog("Player {} broke wood {} without axe — no reward.", playerUuid, blockId);
                    return;
                }

                // ── Guard: cooldown / rate limit ──
                if (!cooldownTracker.canReceiveWoodcuttingReward(playerUuid)) {
                    debugLog("Player {} hit woodcutting rate limit.", playerUuid);
                    return;
                }

                // ── Guard: same-block cooldown ──
                Vector3i pos = event.getTargetBlock();
                int blockX = pos.getX();
                int blockY = pos.getY();
                int blockZ = pos.getZ();
                if (!cooldownTracker.canReceiveBlockReward(playerUuid, blockX, blockY, blockZ)) {
                    return;
                }

                // ── Anti-farm ──
                double farmMultiplier = antiFarmManager.getAndUpdateWoodMultiplier(playerUuid, blockId);
                if (farmMultiplier <= 0.0) return;

                // ── VIP multiplier ──
                double vipMultiplier = multiplierResolver.resolve(playerUuid);

                // ── Resolve tree name ──
                String treeName = resolveTreeName(blockType, blockId);

                // ── Calculate reward ──
                RewardResult result = rewardCalculator.calculateWoodcutting(
                        treeName, vipMultiplier
                );

                // Fallback: try custom blocks if standard calculation failed
                if (result == null || !result.isValid()) {
                    result = tryCustomBlockReward(blockId, vipMultiplier);
                }

                if (result == null || !result.isValid()) {
                    debugLog("No reward configured for tree: {} (resolved: {}).", blockId, treeName);
                    return;
                }

                // Apply anti-farm
                double finalAmount = result.amount() * farmMultiplier;
                finalAmount = Math.round(finalAmount * 100.0) / 100.0;
                if (finalAmount < 0.01) return;

                // ── Deposit ──
                String reason = String.format("Woodcutting: %s", treeName);
                boolean deposited = economyBridge.deposit(playerUuid, finalAmount, reason);

                if (deposited) {
                    cooldownTracker.recordWoodcutting(playerUuid);
                    cooldownTracker.recordBlockBreak(playerUuid, blockX, blockY, blockZ);
                    notifyPlayer(playerUuid, finalAmount, treeName, "wood");
                    debugLog("Rewarded {} → {} coins for chopping {}.",
                            playerUuid, finalAmount, treeName);
                }

            } catch (Throwable e) {
                LOGGER.error("Error processing woodcutting reward: {}", e.getMessage(), e);
            }
        }
    }

    // ── Wood Detection ──────────────────────────────────────────

    /**
     * Determines if a block is a log or wood block using block type
     * group and ID pattern matching.
     */
    private boolean isWoodBlock(BlockType blockType, String blockId) {
        // Check block type group
        String group = blockType.getGroup();
        if (group != null) {
            String groupLower = group.toLowerCase();
            if (groupLower.contains("log") || groupLower.contains("wood")
                    || groupLower.contains("tree")) {
                return true;
            }
        }

        // ID pattern matching
        String lower = blockId.toLowerCase();
        if (lower.contains("_log") || lower.contains("_wood")
                || lower.startsWith("log_") || lower.startsWith("wood_")
                || lower.contains("stripped_log") || lower.contains("tree_trunk")) {
            return true;
        }

        // Custom blocks fallback (category: wood)
        return matchesCustomBlock(blockId, "wood");
    }

    /**
     * Extracts the tree name from a block type for config lookup.
     */
    private String resolveTreeName(BlockType blockType, String blockId) {
        IncomeConfig config = plugin.getConfigManager().getConfig();
        Map<String, IncomeConfig.RewardRange> trees = config.getWoodcutting().getTrees();

        String lower = blockId.toLowerCase();

        // Direct match
        for (String configuredTree : trees.keySet()) {
            if (lower.contains(configuredTree.toLowerCase())) {
                return configuredTree;
            }
        }

        // Strip common suffixes
        String stripped = lower
                .replace("_log", "")
                .replace("_wood", "")
                .replace("stripped_", "")
                .replace("log_", "")
                .replace("wood_", "")
                .replace("_trunk", "")
                .replace("tree_", "");

        for (String configuredTree : trees.keySet()) {
            if (stripped.contains(configuredTree.toLowerCase()) ||
                    configuredTree.toLowerCase().contains(stripped)) {
                return configuredTree;
            }
        }

        return blockId;
    }

    /**
     * Checks whether the player is holding an axe-type tool via
     * the item in hand from the break event.
     */
    private boolean isHoldingAxe(BreakBlockEvent event) {
        ItemStack itemInHand = event.getItemInHand();
        if (itemInHand == null || itemInHand.isEmpty()) return false;

        String itemId = itemInHand.getItemId().toLowerCase();
        return itemId.contains("axe");
    }

    // ── Custom Blocks ────────────────────────────────────────────

    /**
     * Checks if a block ID matches any custom block entry with the given categories.
     */
    private boolean matchesCustomBlock(String blockId, String... categories) {
        IncomeConfig config = plugin.getConfigManager().getConfig();
        IncomeConfig.CustomBlocksSection customBlocks = config.getCustomBlocks();
        if (!customBlocks.isEnabled()) return false;

        String lower = blockId.toLowerCase();
        for (Map.Entry<String, IncomeConfig.CustomBlockEntry> entry : customBlocks.getBlocks().entrySet()) {
            String key = entry.getKey().toLowerCase();
            String cat = entry.getValue().getCategory().toLowerCase();
            for (String allowedCat : categories) {
                if (cat.equals(allowedCat) && (lower.contains(key) || key.contains(lower) || lower.equals(key))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Tries to calculate a reward from the CustomBlocks config section.
     * Used as a fallback when standard wood detection fails.
     */
    private RewardResult tryCustomBlockReward(String blockId, double vipMultiplier) {
        IncomeConfig config = plugin.getConfigManager().getConfig();
        IncomeConfig.CustomBlocksSection customBlocks = config.getCustomBlocks();
        if (!customBlocks.isEnabled()) return null;

        String lower = blockId.toLowerCase();
        for (Map.Entry<String, IncomeConfig.CustomBlockEntry> entry : customBlocks.getBlocks().entrySet()) {
            String key = entry.getKey().toLowerCase();
            String cat = entry.getValue().getCategory().toLowerCase();
            if (cat.equals("wood")
                    && (lower.contains(key) || key.contains(lower) || lower.equals(key))) {
                double baseReward = entry.getValue().roll();
                double finalReward = Math.round(baseReward * vipMultiplier * 100.0) / 100.0;
                if (finalReward < 0.01) return null;
                debugLog("CustomBlock match: {} → {} (cat=wood, base={}, final={})",
                        blockId, entry.getKey(), baseReward, finalReward);
                return new RewardResult(finalReward, baseReward, entry.getKey(), "wood");
            }
        }
        return null;
    }

    // ── Helpers ─────────────────────────────────────────────────

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
}
