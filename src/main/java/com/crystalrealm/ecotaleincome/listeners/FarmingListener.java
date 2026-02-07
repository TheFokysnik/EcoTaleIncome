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
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.crystalrealm.ecotaleincome.util.PluginLogger;

import java.util.Map;
import java.util.UUID;

/**
 * Listens for crop harvesting and rewards players for farming.
 *
 * <p>This is a <strong>unique feature</strong> not available in EcotaleJobs —
 * players earn currency by harvesting fully-grown crops. Supports all
 * configurable crop types with individual reward ranges.</p>
 *
 * <p>Uses the ECS {@link EntityEventSystem} pattern to handle
 * {@link BreakBlockEvent} via the entity store registry.</p>
 *
 * <p>Note: Since ECS events provide {@link BlockType} rather than a full
 * block state, growth state detection is simplified — we check the block
 * type ID for "mature" indicators or assume fully grown.</p>
 */
public class FarmingListener {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    private final EcoTaleIncomePlugin plugin;
    private final RewardCalculator rewardCalculator;
    private final EconomyBridge economyBridge;
    private final MultiplierResolver multiplierResolver;
    private final AntiFarmManager antiFarmManager;
    private final CooldownTracker cooldownTracker;

    public FarmingListener(EcoTaleIncomePlugin plugin,
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
        if (!config.getFarming().isEnabled()) {
            LOGGER.info("Farming rewards are disabled — skipping listener registration.");
            return;
        }

        String method = config.getFarming().getHarvestMethod().toLowerCase();

        if (method.equals("use") || method.equals("both")) {
            registry.registerSystem(new FarmingUseSystem());
            LOGGER.info("Farming: F-key (UseBlock) listener registered.");
        }
        if (method.equals("break") || method.equals("both")) {
            registry.registerSystem(new FarmingBreakSystem());
            LOGGER.info("Farming: LMB (BreakBlock) listener registered.");
        }

        LOGGER.info("Farming listener active (method={}, {} crop types configured).",
                method, config.getFarming().getCrops().size());
    }

    // ── ECS Event System (inner class) ──────────────────────────

    /**
     * ECS system that handles {@link BreakBlockEvent} for farming rewards.
     * Registered via {@link ComponentRegistryProxy#registerSystem}.
     */
    private class FarmingBreakSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

        protected FarmingBreakSystem() {
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

                boolean isCrop = isCropBlock(blockType, blockId);
                debugLog("Block broken: id={}, group={}, isCrop={}", blockId,
                        blockType.getGroup() != null ? blockType.getGroup() : "null", isCrop);
                if (!isCrop) return;

                // ── Guard: fully-grown requirement ──
                if (config.getFarming().isRequireFullyGrown() && !isFullyGrown(blockType, blockId)) {
                    debugLog("Player {} broke immature crop {} — no reward.", playerUuid, blockId);
                    return;
                }

                // ── Guard: cooldown / rate limit ──
                if (!cooldownTracker.canReceiveFarmingReward(playerUuid)) {
                    debugLog("Player {} hit farming rate limit.", playerUuid);
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
                double farmMultiplier = antiFarmManager.getAndUpdateFarmingMultiplier(playerUuid, blockId);
                if (farmMultiplier <= 0.0) return;

                // ── VIP multiplier ──
                double vipMultiplier = multiplierResolver.resolve(playerUuid);

                // ── Resolve crop name ──
                String cropName = resolveCropName(blockType, blockId);

                // ── Calculate reward ──
                RewardResult result = rewardCalculator.calculateFarming(
                        cropName, vipMultiplier
                );

                // Fallback: try custom blocks if standard calculation failed
                if (result == null || !result.isValid()) {
                    result = tryCustomBlockReward(blockId, vipMultiplier);
                }

                if (result == null || !result.isValid()) {
                    debugLog("No reward configured for crop: {} (resolved: {}).", blockId, cropName);
                    return;
                }

                // Apply anti-farm
                double finalAmount = result.amount() * farmMultiplier;
                finalAmount = Math.round(finalAmount * 100.0) / 100.0;
                if (finalAmount < 0.01) return;

                // ── Deposit ──
                String reason = String.format("Farming: %s", cropName);
                boolean deposited = economyBridge.deposit(playerUuid, finalAmount, reason);

                if (deposited) {
                    cooldownTracker.recordFarming(playerUuid);
                    cooldownTracker.recordBlockBreak(playerUuid, blockX, blockY, blockZ);
                    notifyPlayer(playerUuid, finalAmount, cropName, "crop");
                    debugLog("Rewarded {} → {} coins for harvesting {}.",
                            playerUuid, finalAmount, cropName);
                }

            } catch (Throwable e) {
                LOGGER.error("Error processing farming reward.", e);
            }
        }
    }

    // ── ECS UseBlock System (F-key harvesting) ──────────────────

    /**
     * ECS system that handles {@link UseBlockEvent.Post} for farming rewards.
     *
     * <p>In Hytale, {@code UseBlockEvent} is abstract — the engine dispatches
     * {@code UseBlockEvent.Pre} (before interaction) and {@code UseBlockEvent.Post}
     * (after interaction completes). We listen on {@code Post} to reward players
     * after a successful crop harvest via the F key.</p>
     */
    private class FarmingUseSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Post> {

        protected FarmingUseSystem() {
            super(UseBlockEvent.Post.class);
        }

        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk,
                           Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer,
                           UseBlockEvent.Post event) {
            try {
                PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());
                if (playerRef == null) return;

                BlockType blockType = event.getBlockType();
                if (blockType == null) return;
                String blockId = blockType.getId();

                IncomeConfig config = plugin.getConfigManager().getConfig();
                UUID playerUuid = playerRef.getUuid();
                MessageUtil.cachePlayerRef(playerUuid, playerRef);

                boolean isCrop = isCropBlock(blockType, blockId);
                debugLog("Block used (F-key): id={}, group={}, isCrop={}", blockId,
                        blockType.getGroup() != null ? blockType.getGroup() : "null", isCrop);
                if (!isCrop) return;

                // ── Guard: fully-grown requirement ──
                if (config.getFarming().isRequireFullyGrown() && !isFullyGrown(blockType, blockId)) {
                    debugLog("Player {} used immature crop {} — no reward.", playerUuid, blockId);
                    return;
                }

                // ── Guard: cooldown / rate limit ──
                if (!cooldownTracker.canReceiveFarmingReward(playerUuid)) {
                    debugLog("Player {} hit farming rate limit.", playerUuid);
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
                double farmMultiplier = antiFarmManager.getAndUpdateFarmingMultiplier(playerUuid, blockId);
                if (farmMultiplier <= 0.0) return;

                // ── VIP multiplier ──
                double vipMultiplier = multiplierResolver.resolve(playerUuid);

                // ── Resolve crop name ──
                String cropName = resolveCropName(blockType, blockId);

                // ── Calculate reward ──
                RewardResult result = rewardCalculator.calculateFarming(
                        cropName, vipMultiplier
                );

                // Fallback: try custom blocks if standard calculation failed
                if (result == null || !result.isValid()) {
                    result = tryCustomBlockReward(blockId, vipMultiplier);
                }

                if (result == null || !result.isValid()) {
                    debugLog("No reward configured for crop: {} (resolved: {}).", blockId, cropName);
                    return;
                }

                // Apply anti-farm
                double finalAmount = result.amount() * farmMultiplier;
                finalAmount = Math.round(finalAmount * 100.0) / 100.0;
                if (finalAmount < 0.01) return;

                // ── Deposit ──
                String reason = String.format("Farming: %s", cropName);
                boolean deposited = economyBridge.deposit(playerUuid, finalAmount, reason);

                if (deposited) {
                    cooldownTracker.recordFarming(playerUuid);
                    cooldownTracker.recordBlockBreak(playerUuid, blockX, blockY, blockZ);
                    notifyPlayer(playerUuid, finalAmount, cropName, "crop");
                    debugLog("Rewarded {} → {} coins for harvesting {} (F-key).",
                            playerUuid, finalAmount, cropName);
                }

            } catch (Throwable e) {
                LOGGER.error("Error processing farming UseBlock reward.", e);
            }
        }
    }

    // ── Crop Detection ──────────────────────────────────────────

    /**
     * Sanitizes a raw Hytale block ID for matching.
     *
     * <p>Hytale block IDs can have unusual formats, e.g.:</p>
     * <pre>{@code *Plant_Crop_Pumpkin_Block_Eternal_State_Definitions_StageFinal}</pre>
     *
     * <p>This method strips the leading {@code *} and lowercases the result.</p>
     *
     * @param rawBlockId the raw block ID from the event
     * @return sanitized lowercase block ID
     */
    private String sanitizeBlockId(String rawBlockId) {
        String id = rawBlockId;
        // Strip leading special characters (* prefix observed in Hytale)
        while (!id.isEmpty() && !Character.isLetterOrDigit(id.charAt(0))) {
            id = id.substring(1);
        }
        return id.toLowerCase();
    }

    /**
     * Set of block ID keywords that indicate decorative plants, NOT crops.
     * These are excluded even if they match group/pattern checks.
     */
    private static final java.util.Set<String> DECORATIVE_EXCLUSIONS = java.util.Set.of(
            "flower", "rose", "tulip", "daisy", "dandelion", "orchid", "lily",
            "poppy", "cornflower", "bluebell", "lavender", "sunflower",
            "grass", "tall_grass", "short_grass", "fern", "large_fern",
            "dead_bush", "vine", "ivy", "moss", "lichen", "clover",
            "seagrass", "kelp", "reed", "cattail", "leaf", "leaves",
            "petal", "sapling", "seedling", "sprout"
    );

    /**
     * Determines if a block is a harvestable crop.
     *
     * <p>Hytale crop IDs follow the pattern {@code Plant_Crop_<Name>_Item}
     * and {@code Plant_Fruit_<Name>_Item}. These are matched first as a
     * guaranteed hit before any exclusion or config checks.</p>
     */
    private boolean isCropBlock(BlockType blockType, String blockId) {
        String lower = sanitizeBlockId(blockId);

        // ── Step 1 (highest priority): Hytale crop/fruit patterns ──
        // *Plant_Crop_Pumpkin_Block_Eternal_State_Definitions_StageFinal
        // Plant_Crop_Carrot_Item, Plant_Fruit_Apple_Item, etc.
        if (lower.startsWith("plant_crop_") || lower.startsWith("plant_fruit_")) {
            return true;
        }
        if (lower.contains("plant_crop_") || lower.contains("plant_fruit_")) {
            return true;
        }

        // ── Step 2: Exclude known decorative/non-crop blocks ──
        for (String exclusion : DECORATIVE_EXCLUSIONS) {
            if (lower.contains(exclusion)) {
                return false;
            }
        }

        // ── Step 3: Check if block matches any configured crop name ──
        IncomeConfig config = plugin.getConfigManager().getConfig();
        Map<String, IncomeConfig.RewardRange> crops = config.getFarming().getCrops();
        for (String cropKey : crops.keySet()) {
            if (lower.contains(cropKey.toLowerCase())) {
                return true;
            }
        }

        // ── Step 4: Check block group (strict: only "crop" or "farm") ──
        String group = blockType.getGroup();
        if (group != null) {
            String groupLower = group.toLowerCase();
            if (groupLower.contains("crop") || groupLower.contains("farm")
                    || groupLower.contains("harvest")) {
                return true;
            }
        }

        // ── Step 5: Explicit patterns ──
        if (lower.contains("_crop") || lower.contains("crop_")) {
            return true;
        }

        // ── Step 6: Custom blocks fallback (category: crop) ──
        return matchesCustomBlock(blockId, "crop");
    }

    /**
     * Checks if a crop block type is in its final growth stage.
     *
     * <p>Since ECS events provide {@link BlockType} rather than a full
     * block with state data, we use a simplified heuristic: check if the
     * block type ID contains "mature" or similar indicators. If growth
     * state cannot be determined, we assume fully grown.</p>
     */
    private boolean isFullyGrown(BlockType blockType, String blockId) {
        String lower = sanitizeBlockId(blockId);

        // If the ID explicitly indicates immature, reject it
        if (lower.contains("_stage_0") || lower.contains("_stage_1")
                || lower.contains("_age_0") || lower.contains("_age_1")
                || lower.contains("seedling") || lower.contains("sprout")
                || lower.contains("stage0") || lower.contains("stage1")
                || lower.contains("stage2")) {
            return false;
        }

        // If the ID indicates maturity, accept it
        // Hytale uses "StageFinal" for fully grown crops
        if (lower.contains("mature") || lower.contains("harvestable")
                || lower.contains("fully_grown") || lower.contains("ripe")
                || lower.contains("stagefinal") || lower.contains("stage_final")) {
            return true;
        }

        // Cannot determine growth state from BlockType alone — assume fully grown.
        return true;
    }

    /**
     * Extracts the crop name from a block type for config lookup.
     *
     * <p>Handles Hytale patterns like:</p>
     * <ul>
     *   <li>{@code Plant_Crop_Carrot_Item} → "Carrot"</li>
     *   <li>{@code Plant_Fruit_Apple_Item} → "Apple"</li>
     *   <li>{@code wheat_crop_mature} → "Wheat"</li>
     * </ul>
     */
    private String resolveCropName(BlockType blockType, String blockId) {
        IncomeConfig config = plugin.getConfigManager().getConfig();
        Map<String, IncomeConfig.RewardRange> crops = config.getFarming().getCrops();

        String lower = sanitizeBlockId(blockId);

        // ── Hytale native format: Plant_Crop_<Name>_Item / Plant_Fruit_<Name>_Item ──
        String extracted = extractHytaleCropName(lower);
        if (extracted != null) {
            // Try to match extracted name against config keys
            for (String configuredCrop : crops.keySet()) {
                if (extracted.equalsIgnoreCase(configuredCrop)) {
                    return configuredCrop;
                }
            }
            // No exact match — try partial
            for (String configuredCrop : crops.keySet()) {
                if (extracted.contains(configuredCrop.toLowerCase())
                        || configuredCrop.toLowerCase().contains(extracted)) {
                    return configuredCrop;
                }
            }
            // Return extracted name capitalized as fallback (hits "Default" in config)
            return extracted.substring(0, 1).toUpperCase() + extracted.substring(1);
        }

        // ── Generic: direct match against config keys ──
        for (String configuredCrop : crops.keySet()) {
            if (lower.contains(configuredCrop.toLowerCase())) {
                return configuredCrop;
            }
        }

        // Strip common suffixes and retry
        String stripped = lower
                .replace("_crop", "")
                .replace("crop_", "")
                .replace("_plant", "")
                .replace("_bush", "")
                .replace("_block", "")
                .replace("_mature", "")
                .replace("_item", "")
                .replace("_stage", "")
                .replaceAll("_\\d+$", "");

        for (String configuredCrop : crops.keySet()) {
            if (stripped.contains(configuredCrop.toLowerCase()) ||
                    configuredCrop.toLowerCase().contains(stripped)) {
                return configuredCrop;
            }
        }

        return blockId;
    }

    /**
     * Extracts the crop/fruit name from Hytale's native block ID format.
     *
     * <p>Real examples:</p>
     * <ul>
     *   <li>{@code *Plant_Crop_Pumpkin_Block_Eternal_State_Definitions_StageFinal} → "pumpkin"</li>
     *   <li>{@code Plant_Crop_Carrot_Item} → "carrot"</li>
     *   <li>{@code Plant_Fruit_Apple_Item} → "apple"</li>
     * </ul>
     *
     * @return extracted name in lowercase, or null if pattern doesn't match
     */
    private String extractHytaleCropName(String lowerBlockId) {
        // Find the start of plant_crop_ or plant_fruit_
        int cropIdx = lowerBlockId.indexOf("plant_crop_");
        int fruitIdx = lowerBlockId.indexOf("plant_fruit_");

        String name = null;
        if (cropIdx >= 0) {
            name = lowerBlockId.substring(cropIdx + "plant_crop_".length());
        } else if (fruitIdx >= 0) {
            name = lowerBlockId.substring(fruitIdx + "plant_fruit_".length());
        }

        if (name == null || name.isEmpty()) return null;

        // The name part is everything up to the first non-name token.
        // Real format: Pumpkin_Block_Eternal_State_Definitions_StageFinal
        //              Carrot_Item
        // We take only the FIRST segment (the actual crop name)
        int underscorePos = name.indexOf('_');
        if (underscorePos > 0) {
            name = name.substring(0, underscorePos);
        }

        return name.isEmpty() ? null : name;
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
     * Used as a fallback when standard crop detection fails.
     */
    private RewardResult tryCustomBlockReward(String blockId, double vipMultiplier) {
        IncomeConfig config = plugin.getConfigManager().getConfig();
        IncomeConfig.CustomBlocksSection customBlocks = config.getCustomBlocks();
        if (!customBlocks.isEnabled()) return null;

        String lower = blockId.toLowerCase();
        for (Map.Entry<String, IncomeConfig.CustomBlockEntry> entry : customBlocks.getBlocks().entrySet()) {
            String key = entry.getKey().toLowerCase();
            String cat = entry.getValue().getCategory().toLowerCase();
            if (cat.equals("crop")
                    && (lower.contains(key) || key.contains(lower) || lower.equals(key))) {
                double baseReward = entry.getValue().roll();
                double finalReward = Math.round(baseReward * vipMultiplier * 100.0) / 100.0;
                if (finalReward < 0.01) return null;
                debugLog("CustomBlock match: {} → {} (cat=crop, base={}, final={})",
                        blockId, entry.getKey(), baseReward, finalReward);
                return new RewardResult(finalReward, baseReward, entry.getKey(), "crop");
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
