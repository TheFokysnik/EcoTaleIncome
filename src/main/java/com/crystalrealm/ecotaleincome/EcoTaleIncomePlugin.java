package com.crystalrealm.ecotaleincome;

import com.crystalrealm.ecotaleincome.commands.IncomeCommandCollection;
import com.crystalrealm.ecotaleincome.config.ConfigManager;
import com.crystalrealm.ecotaleincome.config.IncomeConfig;
import com.crystalrealm.ecotaleincome.economy.EconomyBridge;
import com.crystalrealm.ecotaleincome.economy.GenericEconomyProvider;
import com.crystalrealm.ecotaleincome.listeners.FarmingListener;
import com.crystalrealm.ecotaleincome.listeners.MiningListener;
import com.crystalrealm.ecotaleincome.listeners.MobKillListener;
import com.crystalrealm.ecotaleincome.listeners.WoodcuttingListener;
import com.crystalrealm.ecotaleincome.protection.AntiFarmManager;
import com.crystalrealm.ecotaleincome.protection.CooldownTracker;
import com.crystalrealm.ecotaleincome.reward.MultiplierResolver;
import com.crystalrealm.ecotaleincome.reward.RewardCalculator;
import com.crystalrealm.ecotaleincome.rpg.RPGLevelingBridge;
import com.crystalrealm.ecotaleincome.lang.LangManager;

import com.crystalrealm.ecotaleincome.util.PermissionHelper;
import com.crystalrealm.ecotaleincome.util.PluginLogger;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * EcoTaleIncome — серверный плагин для начисления валюты Ecotale за
 * убийство мобов, добычу руды, рубку деревьев и сбор урожая.
 *
 * <p>Интегрируется с RPG Leveling для масштабирования наград на основе
 * разницы уровней игрока и моба.</p>
 *
 * <h3>Архитектура</h3>
 * <pre>
 *   Events → Listeners → RewardCalculator → EconomyBridge (Ecotale deposit)
 *                ↕               ↕
 *          Protection      RPGLevelingBridge
 *       (AntiFarm/Cooldown)  (level scaling)
 * </pre>
 *
 * @author CrystalRealm
 * @version 1.1.1
 */
public class EcoTaleIncomePlugin extends JavaPlugin {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();
    private static EcoTaleIncomePlugin instance;

    // ── Core Services ───────────────────────────────────────────
    private ConfigManager configManager;
    private LangManager langManager;
    private EconomyBridge economyBridge;
    private RPGLevelingBridge rpgBridge;
    private RewardCalculator rewardCalculator;
    private MultiplierResolver multiplierResolver;
    private AntiFarmManager antiFarmManager;
    private CooldownTracker cooldownTracker;
    private ScheduledFuture<?> cleanupTask;

    // ── Listeners ───────────────────────────────────────────────
    private MobKillListener mobKillListener;
    private MiningListener miningListener;
    private WoodcuttingListener woodcuttingListener;
    private FarmingListener farmingListener;

    /**
     * Обязательный конструктор для Hytale plugins.
     *
     * @param init инициализатор плагина
     */
    public EcoTaleIncomePlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    // ═════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═════════════════════════════════════════════════════════════

    /**
     * Фаза SETUP — регистрация команд, загрузка конфигурации,
     * инициализация внутренних сервисов. Вызывается до start().
     * Зависимости (Ecotale, RPGLeveling) ещё могут быть недоступны.
     */
    @Override
    protected void setup() {
        LOGGER.info("EcoTaleIncome v{} — Setting up...", getVersion());

        // 1. Загрузка конфигурации
        configManager = new ConfigManager(getDataDirectory());
        configManager.loadOrCreate();
        LOGGER.info("Configuration loaded from {}", configManager.getConfigPath());

        // 1b. Permission resolver (reads permissions.json for group-based checks)
        PermissionHelper.getInstance().init(getDataDirectory());

        IncomeConfig config = configManager.getConfig();

        // 1b. Локализация (RU/EN)
        langManager = new LangManager(getDataDirectory());
        langManager.load(config.getGeneral().getLanguage());
        LOGGER.info("Language system loaded. Server language: {}", langManager.getServerLang());

        // 2. Инициализация защиты
        antiFarmManager = new AntiFarmManager(config.getProtection().getAntiFarm());
        cooldownTracker = new CooldownTracker(config.getProtection());

        // 3. Множители (VIP/Premium через права)
        multiplierResolver = new MultiplierResolver(config.getMultipliers());

        // 4. Калькулятор наград
        rewardCalculator = new RewardCalculator(config, multiplierResolver);

        // 5. Регистрация команд
        getCommandRegistry().registerCommand(new IncomeCommandCollection(this));

        LOGGER.info("Setup phase complete.");
    }

    /**
     * Фаза START — все плагины загружены, безопасно обращаться
     * к Ecotale и RPG Leveling API.
     */
    @Override
    protected void start() {
        LOGGER.info("Starting EcoTaleIncome...");

        // 1. Подключение к экономике (провайдер из конфига)
        economyBridge = new EconomyBridge();

        // Register GenericEconomyProvider if configured
        var genericCfg = configManager.getConfig().getGenericEconomy();
        if (genericCfg.isConfigured()) {
            LOGGER.info("GenericEconomy configured — registering reflection-based adapter for '{}'", genericCfg.getClassName());
            economyBridge.registerProvider("generic", new GenericEconomyProvider(
                    genericCfg.getClassName(),
                    genericCfg.getInstanceMethod(),
                    genericCfg.getDepositMethod(),
                    genericCfg.getBalanceMethod(),
                    genericCfg.isDepositHasReason()
            ));
        }

        String providerKey = configManager.getConfig().getGeneral().getEconomyProvider();
        if (!economyBridge.activate(providerKey)) {
            LOGGER.error("No economy provider available! EcoTaleIncome requires an economy plugin. Disabling rewards.");
            return;
        }
        LOGGER.info("Economy connected via provider: {}", economyBridge.getProviderName());

        // 2. Подключение к RPG Leveling (опционально)
        rpgBridge = new RPGLevelingBridge();
        if (rpgBridge.isAvailable()) {
            LOGGER.info("RPG Leveling detected (v{}). Level-based scaling enabled.",
                    rpgBridge.getVersion());
        } else {
            LOGGER.info("RPG Leveling not found. Mob rewards will use tier-only mode.");
            rpgBridge = null; // Явно null если недоступен
        }

        // 3. Создание и регистрация слушателей
        registerListeners();

        // 4. Периодическая очистка кешей (каждые 5 минут)
        cleanupTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
                this::cleanupCaches, 5, 5, TimeUnit.MINUTES
        );

        LOGGER.info("EcoTaleIncome started! Rewards are active.");
    }

    /**
     * Фаза SHUTDOWN — очистка ресурсов, освобождение памяти.
     */
    @Override
    protected void shutdown() {
        LOGGER.info("EcoTaleIncome shutting down...");

        if (cleanupTask != null) cleanupTask.cancel(false);
        if (antiFarmManager != null) antiFarmManager.clearAll();
        if (cooldownTracker != null) cooldownTracker.clearAll();
        if (langManager != null) langManager.clearPlayerData();

        instance = null;
        LOGGER.info("EcoTaleIncome shutdown complete.");
    }

    // ═════════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ═════════════════════════════════════════════════════════════

    /**
     * Создаёт и регистрирует слушателей событий.
     * Каждый слушатель получает все необходимые зависимости через конструктор.
     */
    private void registerListeners() {
        // Mob Kills
        mobKillListener = new MobKillListener(
                this, rewardCalculator, economyBridge,
                multiplierResolver, antiFarmManager, cooldownTracker
        );
        mobKillListener.register(getEventRegistry(), rpgBridge);

        // Mining
        miningListener = new MiningListener(
                this, rewardCalculator, economyBridge,
                multiplierResolver, antiFarmManager, cooldownTracker
        );
        miningListener.register(getEntityStoreRegistry());

        // Woodcutting
        woodcuttingListener = new WoodcuttingListener(
                this, rewardCalculator, economyBridge,
                multiplierResolver, antiFarmManager, cooldownTracker
        );
        woodcuttingListener.register(getEntityStoreRegistry());

        // Farming
        farmingListener = new FarmingListener(
                this, rewardCalculator, economyBridge,
                multiplierResolver, antiFarmManager, cooldownTracker
        );
        farmingListener.register(getEntityStoreRegistry());
    }

    /**
     * Периодическая очистка кешей защиты от утечек памяти.
     */
    private void cleanupCaches() {
        if (antiFarmManager != null) antiFarmManager.cleanup();
        if (cooldownTracker != null) cooldownTracker.cleanup();
    }

    // ═════════════════════════════════════════════════════════════
    //  PUBLIC GETTERS
    // ═════════════════════════════════════════════════════════════

    @Nonnull
    public String getVersion() {
        return "1.1.1";
    }

    @Nonnull
    public static EcoTaleIncomePlugin getInstance() {
        return instance;
    }

    @Nonnull
    public ConfigManager getConfigManager() {
        return configManager;
    }

    @Nonnull
    public EconomyBridge getEconomyBridge() {
        return economyBridge;
    }

    /** @return мост к RPG Leveling; {@code null} если RPG Leveling не установлен */
    @Nullable
    public RPGLevelingBridge getRPGBridge() {
        return rpgBridge;
    }

    @Nonnull
    public RewardCalculator getRewardCalculator() {
        return rewardCalculator;
    }

    @Nonnull
    public MultiplierResolver getMultiplierResolver() {
        return multiplierResolver;
    }

    @Nonnull
    public AntiFarmManager getAntiFarmManager() {
        return antiFarmManager;
    }

    @Nonnull
    public CooldownTracker getCooldownTracker() {
        return cooldownTracker;
    }

    @Nonnull
    public LangManager getLangManager() {
        return langManager;
    }
}
