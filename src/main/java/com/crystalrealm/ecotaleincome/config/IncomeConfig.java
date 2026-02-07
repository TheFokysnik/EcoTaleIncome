package com.crystalrealm.ecotaleincome.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * POJO-модель конфигурации плагина EcoTaleIncome.
 * Десериализуется из JSON через Gson.
 */
public class IncomeConfig {

    private GeneralSection General = new GeneralSection();
    private MobKillsSection MobKills = new MobKillsSection();
    private MiningSection Mining = new MiningSection();
    private WoodcuttingSection Woodcutting = new WoodcuttingSection();
    private FarmingSection Farming = new FarmingSection();
    private CustomBlocksSection CustomBlocks = new CustomBlocksSection();
    private MultipliersSection Multipliers = new MultipliersSection();
    private ProtectionSection Protection = new ProtectionSection();
    private GenericEconomySection GenericEconomy = new GenericEconomySection();

    // ─── Getters ──────────────────────────────────────────────────

    public GeneralSection getGeneral() { return General; }
    public MobKillsSection getMobKills() { return MobKills; }
    public MiningSection getMining() { return Mining; }
    public WoodcuttingSection getWoodcutting() { return Woodcutting; }
    public FarmingSection getFarming() { return Farming; }
    public CustomBlocksSection getCustomBlocks() { return CustomBlocks; }
    public MultipliersSection getMultipliers() { return Multipliers; }
    public ProtectionSection getProtection() { return Protection; }
    public GenericEconomySection getGenericEconomy() { return GenericEconomy; }

    // ─── Вложенные классы секций ──────────────────────────────────

    /** Общие настройки плагина. */
    public static class GeneralSection {
        private boolean DebugMode = false;
        private String Language = "ru";
        private String EconomyProvider = "ecotale";
        private List<String> AllowedWorlds = List.of();
        private String MessagePrefix = "<dark_gray>[<gold>$<dark_gray>]";
        private boolean NotifyOnReward = true;
        private String NotifyFormat = "<green>+{amount} <dark_gray>• <gray>{source}";

        public boolean isDebugMode() { return DebugMode; }
        public void setDebugMode(boolean debug) { this.DebugMode = debug; }
        public String getLanguage() { return Language; }
        public void setLanguage(String lang) { this.Language = lang; }
        public String getEconomyProvider() { return EconomyProvider; }
        public List<String> getAllowedWorlds() { return AllowedWorlds; }
        public String getMessagePrefix() { return MessagePrefix; }
        public boolean isNotifyOnReward() { return NotifyOnReward; }
        public String getNotifyFormat() { return NotifyFormat; }
    }

    /** Раздел наград за убийство мобов. */
    public static class MobKillsSection {
        private boolean Enabled = true;
        private boolean UseRPGLevelScaling = true;
        private Map<String, RewardRange> BaseTiers = new HashMap<>();
        private LevelScalingSection LevelScaling = new LevelScalingSection();
        private Map<String, RewardRange> EntityOverrides = new HashMap<>();
        private List<String> EntityBlacklist = List.of();

        public boolean isEnabled() { return Enabled; }
        public boolean isUseRPGLevelScaling() { return UseRPGLevelScaling; }
        public boolean isUseRPGLeveling() { return UseRPGLevelScaling; }
        public Map<String, RewardRange> getBaseTiers() { return BaseTiers; }
        public LevelScalingSection getLevelScaling() { return LevelScaling; }
        public Map<String, RewardRange> getEntityOverrides() { return EntityOverrides; }
        public List<String> getEntityBlacklist() { return EntityBlacklist; }
    }

    /** Коэффициенты масштабирования по разнице уровней. */
    public static class LevelScalingSection {
        private double MuchLowerPenalty = 0.1;
        private double LowerPenalty = 0.4;
        private double SlightlyLowerPenalty = 0.7;
        private double EqualMultiplier = 1.0;
        private double SlightlyHigherBonus = 1.3;
        private double HigherBonus = 1.6;
        private double MuchHigherBonus = 2.0;

        public double getMuchLowerPenalty() { return MuchLowerPenalty; }
        public double getLowerPenalty() { return LowerPenalty; }
        public double getSlightlyLowerPenalty() { return SlightlyLowerPenalty; }
        public double getEqualMultiplier() { return EqualMultiplier; }
        public double getSlightlyHigherBonus() { return SlightlyHigherBonus; }
        public double getHigherBonus() { return HigherBonus; }
        public double getMuchHigherBonus() { return MuchHigherBonus; }

        /**
         * Возвращает множитель на основе разницы уровней (mobLevel - playerLevel).
         *
         * @param levelDiff разница: положительная = моб сильнее
         * @return множитель награды
         */
        public double getMultiplierForDiff(int levelDiff) {
            if (levelDiff <= -15) return MuchLowerPenalty;
            if (levelDiff <= -10) return LowerPenalty;
            if (levelDiff <= -5)  return SlightlyLowerPenalty;
            if (levelDiff <= 4)   return EqualMultiplier;
            if (levelDiff <= 9)   return SlightlyHigherBonus;
            if (levelDiff <= 14)  return HigherBonus;
            return MuchHigherBonus;
        }
    }

    /** Раздел наград за добычу руды. */
    public static class MiningSection {
        private boolean Enabled = true;
        private boolean ToolRequired = true;
        private DepthBonusSection DepthBonus = new DepthBonusSection();
        private Map<String, RewardRange> Ores = new HashMap<>();

        public boolean isEnabled() { return Enabled; }
        public boolean isToolRequired() { return ToolRequired; }
        public DepthBonusSection getDepthBonus() { return DepthBonus; }
        public Map<String, RewardRange> getOres() { return Ores; }
    }

    /** Бонус за глубину добычи. */
    public static class DepthBonusSection {
        private boolean Enabled = true;
        private double BonusPerBlock = 0.005;
        private double MaxBonus = 0.5;
        private int BaselineY = 64;

        public boolean isEnabled() { return Enabled; }
        public double getBonusPerBlock() { return BonusPerBlock; }
        public double getMaxBonus() { return MaxBonus; }
        public int getBaselineY() { return BaselineY; }

        /**
         * Рассчитывает бонусный множитель за глубину.
         *
         * @param blockY Y-координата блока
         * @return множитель (1.0 = нет бонуса)
         */
        public double calculateBonus(int blockY) {
            if (!Enabled || blockY >= BaselineY) return 1.0;
            int depth = BaselineY - blockY;
            double bonus = Math.min(depth * BonusPerBlock, MaxBonus);
            return 1.0 + bonus;
        }
    }

    /** Раздел наград за рубку деревьев. */
    public static class WoodcuttingSection {
        private boolean Enabled = true;
        private boolean ToolRequired = true;
        private Map<String, RewardRange> Trees = new HashMap<>();

        public boolean isEnabled() { return Enabled; }
        public boolean isToolRequired() { return ToolRequired; }
        public Map<String, RewardRange> getTrees() { return Trees; }
    }

    /**
     * Раздел наград за сбор урожая.
     *
     * <p>{@code HarvestMethod} определяет, какие события слушаются:</p>
     * <ul>
     *   <li>{@code "use"} — только F-клавиша (UseBlockEvent) — по умолчанию</li>
     *   <li>{@code "break"} — только ЛКМ (BreakBlockEvent)</li>
     *   <li>{@code "both"} — оба способа</li>
     * </ul>
     */
    public static class FarmingSection {
        private boolean Enabled = true;
        private boolean RequireFullyGrown = true;
        private String HarvestMethod = "both";
        private Map<String, RewardRange> Crops = new HashMap<>();

        public boolean isEnabled() { return Enabled; }
        public boolean isRequireFullyGrown() { return RequireFullyGrown; }
        public String getHarvestMethod() { return HarvestMethod; }
        public Map<String, RewardRange> getCrops() { return Crops; }
    }

    /**
     * Раздел кастомных блоков — позволяет добавлять свои блоки из других модов.
     * Каждый блок задаётся своим точным ID (или частью ID) и категорией награды.
     *
     * <p>Категории: "ore", "wood", "crop", "generic".</p>
     * <p>Пример: блок "mythical_ore" из мода можно добавить с категорией "ore".</p>
     */
    public static class CustomBlocksSection {
        private boolean Enabled = false;
        private Map<String, CustomBlockEntry> Blocks = new HashMap<>();

        public boolean isEnabled() { return Enabled; }
        public Map<String, CustomBlockEntry> getBlocks() { return Blocks; }
    }

    /** Запись кастомного блока — ID (или паттерн), категория и диапазон награды. */
    public static class CustomBlockEntry {
        private double Min = 0.0;
        private double Max = 0.0;
        private String Category = "generic";

        public CustomBlockEntry() {}

        public CustomBlockEntry(double min, double max, String category) {
            this.Min = min;
            this.Max = max;
            this.Category = category;
        }

        public double getMin() { return Min; }
        public double getMax() { return Max; }
        public String getCategory() { return Category; }

        public double roll() {
            if (Min >= Max) return Min;
            return Min + Math.random() * (Max - Min);
        }
    }

    /**
     * Настройки GenericEconomy — reflection-адаптер для любого плагина экономики.
     *
     * <p>Используется когда {@code EconomyProvider = "generic"}.
     * Владельцу сервера не нужно писать код — достаточно указать имя класса
     * и имена методов экономического плагина.</p>
     *
     * <ul>
     *   <li>{@code ClassName} — полное имя Java-класса API (например {@code "com.example.economy.EconomyAPI"})</li>
     *   <li>{@code InstanceMethod} — статический метод для получения экземпляра (пусто = статический API)</li>
     *   <li>{@code DepositMethod} — имя метода пополнения баланса</li>
     *   <li>{@code BalanceMethod} — имя метода получения баланса</li>
     *   <li>{@code DepositHasReason} — принимает ли метод deposit строку-причину третьим параметром</li>
     * </ul>
     */
    public static class GenericEconomySection {
        private String ClassName = "";
        private String InstanceMethod = "";
        private String DepositMethod = "deposit";
        private String BalanceMethod = "getBalance";
        private boolean DepositHasReason = false;

        public String getClassName() { return ClassName; }
        public String getInstanceMethod() { return InstanceMethod; }
        public String getDepositMethod() { return DepositMethod; }
        public String getBalanceMethod() { return BalanceMethod; }
        public boolean isDepositHasReason() { return DepositHasReason; }

        /** @return true если ClassName заполнен */
        public boolean isConfigured() {
            return ClassName != null && !ClassName.isBlank();
        }
    }

    /** VIP/группа множители. */
    public static class MultipliersSection {
        private Map<String, Double> Groups = new HashMap<>();

        public Map<String, Double> getGroups() { return Groups; }
    }

    /** Защита от абьюза. */
    public static class ProtectionSection {
        private int MaxRewardsPerMinute = 60;
        private long SameBlockCooldownMs = 500;
        private AntiFarmSection AntiFarm = new AntiFarmSection();

        public int getMaxRewardsPerMinute() { return MaxRewardsPerMinute; }
        public long getSameBlockCooldownMs() { return SameBlockCooldownMs; }
        public AntiFarmSection getAntiFarm() { return AntiFarm; }
    }

    /** Настройки антифарм-системы. */
    public static class AntiFarmSection {
        private boolean Enabled = true;
        private int WindowSeconds = 120;
        private int SameEntityThreshold = 20;
        private double MinMultiplier = 0.1;
        private double DecayRate = 0.05;

        public boolean isEnabled() { return Enabled; }
        public int getWindowSeconds() { return WindowSeconds; }
        public int getSameEntityThreshold() { return SameEntityThreshold; }
        public double getMinMultiplier() { return MinMultiplier; }
        public double getDecayRate() { return DecayRate; }
    }

    // ─── Общие модели ─────────────────────────────────────────────

    /** Диапазон награды (мин–макс). */
    public static class RewardRange {
        private double Min = 0.0;
        private double Max = 0.0;

        public RewardRange() {}

        public RewardRange(double min, double max) {
            this.Min = min;
            this.Max = max;
        }

        public double getMin() { return Min; }
        public double getMax() { return Max; }

        /**
         * Генерирует случайное значение в диапазоне [Min, Max].
         *
         * @return случайная награда
         */
        public double roll() {
            if (Min >= Max) return Min;
            return Min + Math.random() * (Max - Min);
        }
    }
}
