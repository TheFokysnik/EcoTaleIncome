package com.crystalrealm.ecotaleincome.reward;

import com.crystalrealm.ecotaleincome.config.IncomeConfig;
import com.crystalrealm.ecotaleincome.config.IncomeConfig.RewardRange;

import com.crystalrealm.ecotaleincome.util.PluginLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

/**
 * Центральный калькулятор наград.
 *
 * <p>Объединяет базовую награду, множитель уровня (RPG Leveling),
 * бонус глубины (руда), и VIP-множитель.</p>
 *
 * <h3>Формула для мобов:</h3>
 * <pre>
 *   finalReward = baseTier.roll() × levelScaleMultiplier × vipMultiplier
 * </pre>
 *
 * <h3>Формула для руды:</h3>
 * <pre>
 *   finalReward = oreRange.roll() × depthBonus × vipMultiplier
 * </pre>
 *
 * <h3>Формула для дерева/урожая:</h3>
 * <pre>
 *   finalReward = range.roll() × vipMultiplier
 * </pre>
 */
public class RewardCalculator {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    private final IncomeConfig config;
    private final MultiplierResolver multiplierResolver;

    public RewardCalculator(@Nonnull IncomeConfig config,
                            @Nonnull MultiplierResolver multiplierResolver) {
        this.config = config;
        this.multiplierResolver = multiplierResolver;
    }

    // ─── Mob Kill Reward ──────────────────────────────────────────

    /**
     * Рассчитывает награду за убийство моба.
     *
     * @param entityName      название сущности (например, "Zombie", "Dragon_Fire")
     * @param entityTier      тир моба ("HOSTILE", "BOSS", и т.д.)
     * @param playerLevel     уровень игрока (из RPG Leveling, или -1)
     * @param entityLevel     уровень моба (из RPG Leveling, или -1)
     * @param vipMultiplier   множитель VIP-группы
     * @return результат расчёта, или null если награды нет
     */
    @Nullable
    public RewardResult calculateMobKill(@Nonnull String entityName,
                                         @Nonnull String entityTier,
                                         int playerLevel,
                                         int entityLevel,
                                         double vipMultiplier) {
        IncomeConfig.MobKillsSection mobConfig = config.getMobKills();

        // Проверка чёрного списка
        if (mobConfig.getEntityBlacklist().contains(entityName)) {
            return null;
        }

        // 1. Определить базовый диапазон награды
        RewardRange range = mobConfig.getEntityOverrides().get(entityName);
        if (range == null) {
            range = mobConfig.getBaseTiers().get(entityTier);
        }
        if (range == null) {
            LOGGER.debug("No reward range for entity {} (tier: {})", entityName, entityTier);
            return null;
        }

        double baseReward = range.roll();

        // 2. Применить масштабирование по уровню
        double levelMultiplier = 1.0;
        if (mobConfig.isUseRPGLevelScaling() && playerLevel > 0 && entityLevel > 0) {
            int levelDiff = entityLevel - playerLevel;
            levelMultiplier = mobConfig.getLevelScaling().getMultiplierForDiff(levelDiff);
        }

        // 3. Финальный расчёт
        double finalReward = round(baseReward * levelMultiplier * vipMultiplier);

        if (config.getGeneral().isDebugMode()) {
            LOGGER.info("[DEBUG] Mob: {} | Tier: {} | Base: {:.2f} | LevelMult: {:.2f} (P:{} vs M:{}) | VIP: {:.2f} | Final: {:.2f}",
                    entityName, entityTier, baseReward, levelMultiplier,
                    playerLevel, entityLevel, vipMultiplier, finalReward);
        }

        return new RewardResult(finalReward, baseReward, entityName, "mob");
    }

    // ─── Mining Reward ────────────────────────────────────────────

    /**
     * Рассчитывает награду за добычу руды.
     *
     * @param oreName       название руды (ключ из конфига, например "Gold")
     * @param blockY        Y-координата блока
     * @param vipMultiplier множитель VIP-группы
     * @return результат расчёта, или null если руда не в конфиге
     */
    @Nullable
    public RewardResult calculateMining(@Nonnull String oreName,
                                        int blockY,
                                        double vipMultiplier) {
        IncomeConfig.MiningSection miningConfig = config.getMining();

        RewardRange range = findInMap(miningConfig.getOres(), oreName);
        if (range == null) return null;

        double baseReward = range.roll();
        double depthBonus = miningConfig.getDepthBonus().calculateBonus(blockY);
        double finalReward = round(baseReward * depthBonus * vipMultiplier);

        if (config.getGeneral().isDebugMode()) {
            LOGGER.info("[DEBUG] Ore: {} | Base: {:.2f} | Depth(y={}): {:.2f} | VIP: {:.2f} | Final: {:.2f}",
                    oreName, baseReward, blockY, depthBonus, vipMultiplier, finalReward);
        }

        return new RewardResult(finalReward, baseReward, oreName, "ore");
    }

    // ─── Woodcutting Reward ───────────────────────────────────────

    /**
     * Рассчитывает награду за рубку дерева.
     *
     * @param woodName      тип древесины (ключ из конфига, например "Oak")
     * @param vipMultiplier множитель VIP-группы
     * @return результат расчёта, или null если тип не в конфиге
     */
    @Nullable
    public RewardResult calculateWoodcutting(@Nonnull String woodName,
                                             double vipMultiplier) {
        RewardRange range = findInMap(config.getWoodcutting().getTrees(), woodName);
        if (range == null) return null;

        double baseReward = range.roll();
        double finalReward = round(baseReward * vipMultiplier);

        return new RewardResult(finalReward, baseReward, woodName, "wood");
    }

    // ─── Farming Reward ───────────────────────────────────────────

    /**
     * Рассчитывает награду за сбор урожая.
     *
     * @param cropName      тип культуры (ключ из конфига, например "Wheat")
     * @param vipMultiplier множитель VIP-группы
     * @return результат расчёта, или null если тип не в конфиге
     */
    @Nullable
    public RewardResult calculateFarming(@Nonnull String cropName,
                                         double vipMultiplier) {
        RewardRange range = findInMap(config.getFarming().getCrops(), cropName);
        if (range == null) return null;

        double baseReward = range.roll();
        double finalReward = round(baseReward * vipMultiplier);

        return new RewardResult(finalReward, baseReward, cropName, "crop");
    }

    // ─── Util ─────────────────────────────────────────────────────

    /**
     * Case-insensitive поиск ключа в карте наград.
     */
    @Nullable
    private RewardRange findInMap(@Nonnull Map<String, RewardRange> map,
                                  @Nonnull String key) {
        // Точное совпадение
        RewardRange range = map.get(key);
        if (range != null) return range;

        // Case-insensitive поиск
        for (Map.Entry<String, RewardRange> entry : map.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }

        // Проверка contains (для "Gold_Ore" → "Gold")
        for (Map.Entry<String, RewardRange> entry : map.entrySet()) {
            if (key.toLowerCase().contains(entry.getKey().toLowerCase())) {
                return entry.getValue();
            }
        }

        // Fallback: "Default" catch-all entry
        RewardRange defaultRange = map.get("Default");
        if (defaultRange != null) return defaultRange;

        return null;
    }

    /**
     * Округляет до 2 знаков и гарантирует минимум 0.01 для положительных наград.
     */
    private double round(double value) {
        if (value <= 0) return 0.0;
        double rounded = Math.round(value * 100.0) / 100.0;
        return Math.max(rounded, 0.01);
    }
}
