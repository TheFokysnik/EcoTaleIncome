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
 * Listens for block break events and rewards players for mining ores.
 *
 * <p>Ore detection uses a combination of block type group checks and
 * ID pattern matching against the configured ore map. A depth bonus
 * is applied for ores mined deeper underground.</p>
 *
 * <p>Optionally requires the player to use a pickaxe-type tool
 * when {@code Mining.ToolRequired} is {@code true} in config.</p>
 *
 * <p>Uses the ECS {@link EntityEventSystem} pattern to handle
 * {@link BreakBlockEvent} via the entity store registry.</p>
 */
public class MiningListener {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    private final EcoTaleIncomePlugin plugin;
    private final RewardCalculator rewardCalculator;
    private final EconomyBridge economyBridge;
    private final MultiplierResolver multiplierResolver;
    private final AntiFarmManager antiFarmManager;
    private final CooldownTracker cooldownTracker;

    public MiningListener(EcoTaleIncomePlugin plugin,
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
        if (!config.getMining().isEnabled()) {
            LOGGER.info("Mining rewards are disabled in config — skipping listener registration.");
            return;
        }

        registry.registerSystem(new MiningBreakSystem());
        LOGGER.info("Mining listener registered ({} ore types configured).",
                config.getMining().getOres().size());
    }

    // ── ECS Event System (inner class) ──────────────────────────

    /**
     * ECS system that handles {@link BreakBlockEvent} for mining rewards.
     * Registered via {@link ComponentRegistryProxy#registerSystem}.
     */
    private class MiningBreakSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

        protected MiningBreakSystem() {
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

                // ── Quick check: is this an ore? ──
                if (!isOreBlock(blockType, blockId)) return;

                // ── Guard: tool requirement ──
                if (config.getMining().isToolRequired() && !isHoldingPickaxe(event)) {
                    debugLog("Player {} broke ore {} without pickaxe — no reward.", playerUuid, blockId);
                    return;
                }

                // ── Guard: cooldown / rate limit ──
                if (!cooldownTracker.canReceiveMiningReward(playerUuid)) {
                    debugLog("Player {} hit mining rate limit.", playerUuid);
                    return;
                }

                // ── Guard: same-block cooldown (anti-rapid-break) ──
                Vector3i pos = event.getTargetBlock();
                int blockX = pos.getX();
                int blockY = pos.getY();
                int blockZ = pos.getZ();
                if (!cooldownTracker.canReceiveBlockReward(playerUuid, blockX, blockY, blockZ)) {
                    return;
                }

                // ── Anti-farm check ──
                double farmMultiplier = antiFarmManager.getAndUpdateMiningMultiplier(playerUuid, blockId);
                if (farmMultiplier <= 0.0) return;

                // ── VIP multiplier ──
                double vipMultiplier = multiplierResolver.resolve(playerUuid);

                // ── Resolve ore name for config lookup ──
                String oreName = resolveOreName(blockType, blockId);

                // ── Calculate reward (with depth bonus) ──
                RewardResult result = rewardCalculator.calculateMining(
                        oreName, blockY, vipMultiplier
                );

                // Fallback: try custom blocks if standard calculation failed
                if (result == null || !result.isValid()) {
                    result = tryCustomBlockReward(blockId, blockY, vipMultiplier);
                }

                if (result == null || !result.isValid()) {
                    debugLog("No reward configured for ore: {} (resolved: {}).", blockId, oreName);
                    return;
                }

                // Apply anti-farm
                double finalAmount = result.amount() * farmMultiplier;
                finalAmount = Math.round(finalAmount * 100.0) / 100.0;
                if (finalAmount < 0.01) return;

                // ── Deposit ──
                String reason = String.format("Mining: %s (Y=%d)", oreName, blockY);
                boolean deposited = economyBridge.deposit(playerUuid, finalAmount, reason);

                if (deposited) {
                    cooldownTracker.recordMining(playerUuid);
                    cooldownTracker.recordBlockBreak(playerUuid, blockX, blockY, blockZ);
                    notifyPlayer(playerUuid, finalAmount, oreName, "ore");
                    debugLog("Rewarded {} → {} coins for mining {} at Y={}.",
                            playerUuid, finalAmount, oreName, blockY);
                }

            } catch (Throwable e) {
                LOGGER.error("Error processing mining reward.", e);
            }
        }
    }

    // ── Ore Detection ───────────────────────────────────────────

    /**
     * Determines if a block is an ore using block type group and ID matching.
     *
     * <p>Checks the block type's group for "ore" references first, then
     * falls back to ID pattern matching.</p>
     */
    private boolean isOreBlock(BlockType blockType, String blockId) {
        // Check block type group
        String group = blockType.getGroup();
        if (group != null && group.toLowerCase().contains("ore")) return true;

        // Pattern matching on block ID
        String lower = blockId.toLowerCase();
        if (lower.contains("_ore") || lower.startsWith("ore_")
                || lower.contains("ore_block") || lower.contains("raw_ore")) {
            return true;
        }

        // Custom blocks fallback (categories: ore, generic)
        return matchesCustomBlock(blockId, "ore", "generic");
    }

    /**
     * Extracts the ore name from a block type for config lookup.
     *
     * <p>Tries to match against configured ore names. For example,
     * a block named {@code copper_ore_block} should resolve to {@code Copper}.</p>
     */
    private String resolveOreName(BlockType blockType, String blockId) {
        IncomeConfig config = plugin.getConfigManager().getConfig();
        Map<String, IncomeConfig.RewardRange> ores = config.getMining().getOres();

        String lower = blockId.toLowerCase();

        // Direct match against configured ore names
        for (String configuredOre : ores.keySet()) {
            if (lower.contains(configuredOre.toLowerCase())) {
                return configuredOre;
            }
        }

        // Strip common suffixes and try again
        String stripped = lower
                .replace("_ore_block", "")
                .replace("_ore", "")
                .replace("ore_", "")
                .replace("raw_", "");

        for (String configuredOre : ores.keySet()) {
            if (stripped.contains(configuredOre.toLowerCase()) ||
                    configuredOre.toLowerCase().contains(stripped)) {
                return configuredOre;
            }
        }

        // Return the raw block ID as last resort
        return blockId;
    }

    /**
     * Checks whether the player is holding a pickaxe-type tool via
     * the item in hand from the break event.
     */
    private boolean isHoldingPickaxe(BreakBlockEvent event) {
        ItemStack itemInHand = event.getItemInHand();
        if (itemInHand == null || itemInHand.isEmpty()) return false;

        String itemId = itemInHand.getItemId().toLowerCase();
        return itemId.contains("pickaxe") || itemId.contains("pick");
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
     * Used as a fallback when standard ore detection fails.
     *
     * @return RewardResult or null if no matching custom block found
     */
    private RewardResult tryCustomBlockReward(String blockId, int blockY, double vipMultiplier) {
        IncomeConfig config = plugin.getConfigManager().getConfig();
        IncomeConfig.CustomBlocksSection customBlocks = config.getCustomBlocks();
        if (!customBlocks.isEnabled()) return null;

        String lower = blockId.toLowerCase();
        for (Map.Entry<String, IncomeConfig.CustomBlockEntry> entry : customBlocks.getBlocks().entrySet()) {
            String key = entry.getKey().toLowerCase();
            String cat = entry.getValue().getCategory().toLowerCase();
            if ((cat.equals("ore") || cat.equals("generic"))
                    && (lower.contains(key) || key.contains(lower) || lower.equals(key))) {
                double baseReward = entry.getValue().roll();
                double depthBonus = config.getMining().getDepthBonus().calculateBonus(blockY);
                double finalReward = Math.round(baseReward * depthBonus * vipMultiplier * 100.0) / 100.0;
                if (finalReward < 0.01) return null;
                debugLog("CustomBlock match: {} → {} (cat={}, base={}, depth={}, final={})",
                        blockId, entry.getKey(), cat, baseReward, depthBonus, finalReward);
                return new RewardResult(finalReward, baseReward, entry.getKey(), cat);
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
