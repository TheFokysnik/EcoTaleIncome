package com.crystalrealm.ecotaleincome;

import com.crystalrealm.ecotaleincome.commands.IncomeCommandCollection;
import com.crystalrealm.ecotaleincome.config.ConfigManager;
import com.crystalrealm.ecotaleincome.config.IncomeConfig;
import com.crystalrealm.ecotaleincome.economy.EconomyBridge;
import com.crystalrealm.ecotaleincome.economy.GenericEconomyProvider;
import com.crystalrealm.ecotaleincome.listeners.BlockPlaceListener;
import com.crystalrealm.ecotaleincome.listeners.FarmingListener;
import com.crystalrealm.ecotaleincome.listeners.MiningListener;
import com.crystalrealm.ecotaleincome.listeners.MobKillListener;
import com.crystalrealm.ecotaleincome.listeners.WoodcuttingListener;
import com.crystalrealm.ecotaleincome.protection.AntiFarmManager;
import com.crystalrealm.ecotaleincome.protection.CooldownTracker;
import com.crystalrealm.ecotaleincome.protection.PlacedBlockTracker;
import com.crystalrealm.ecotaleincome.reward.MultiplierResolver;
import com.crystalrealm.ecotaleincome.reward.RewardCalculator;
import com.crystalrealm.ecotaleincome.leveling.GenericLevelProvider;
import com.crystalrealm.ecotaleincome.leveling.LevelBridge;
import com.crystalrealm.ecotaleincome.leveling.MMOSkillTreeProvider;
import com.crystalrealm.ecotaleincome.lang.LangManager;

import com.crystalrealm.ecotaleincome.util.PermissionHelper;
import com.crystalrealm.ecotaleincome.util.PluginLogger;
import com.crystalrealm.ecotaleincome.util.RewardAggregator;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
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
 * @version 1.3.0
 */
public class EcoTaleIncomePlugin extends JavaPlugin {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();
    private static EcoTaleIncomePlugin instance;

    // ── Core Services ───────────────────────────────────────────
    private ConfigManager configManager;
    private LangManager langManager;
    private EconomyBridge economyBridge;
    private LevelBridge levelBridge;
    private RewardCalculator rewardCalculator;
    private MultiplierResolver multiplierResolver;
    private AntiFarmManager antiFarmManager;
    private CooldownTracker cooldownTracker;
    private PlacedBlockTracker placedBlockTracker;
    private ScheduledFuture<?> cleanupTask;
    private RewardAggregator rewardAggregator;

    // ── Listeners ───────────────────────────────────────────────
    private MobKillListener mobKillListener;
    private MiningListener miningListener;
    private WoodcuttingListener woodcuttingListener;
    private FarmingListener farmingListener;    private BlockPlaceListener blockPlaceListener;
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
        placedBlockTracker = new PlacedBlockTracker();

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

        // 2. Подключение к системе уровней (опционально)
        levelBridge = new LevelBridge();
        levelBridge.registerProvider("mmoskilltree", new MMOSkillTreeProvider());
        var levelCfg = configManager.getConfig().getGenericLeveling();
        if (levelCfg.isConfigured()) {
            LOGGER.info("GenericLeveling configured — registering adapter for '{}'", levelCfg.getClassName());
            levelBridge.registerProvider("generic", new GenericLevelProvider(
                    levelCfg.getClassName(), levelCfg.getInstanceMethod(), levelCfg.GetLevelMethod()));
        }
        levelBridge.activate(configManager.getConfig().getGeneral().getLevelProvider());
        if (levelBridge.isAvailable()) {
            LOGGER.info("Level provider connected: {}", levelBridge.getProviderName());
        } else {
            LOGGER.info("No level provider available. Mob rewards will use tier-only mode.");
        }

        // 3. Создание и регистрация слушателей
        registerListeners();

        // 3b. Кеширование Player-объектов для уведомлений
        registerPlayerTracking();

        // 3c. Reward aggregator for "popup" notification mode
        rewardAggregator = new RewardAggregator(this);
        LOGGER.info("RewardAggregator initialized (window={}ms)",
                configManager.getConfig().getGeneral().getAggregateWindowMs());

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

        if (rewardAggregator != null) rewardAggregator.shutdown();
        if (cleanupTask != null) cleanupTask.cancel(false);
        if (antiFarmManager != null) antiFarmManager.clearAll();
        if (cooldownTracker != null) cooldownTracker.clearAll();
        if (placedBlockTracker != null) placedBlockTracker.clearAll();
        if (langManager != null) langManager.clearPlayerData();

        com.crystalrealm.ecotaleincome.util.MessageUtil.clearCache();

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
        // Block Place Tracking (exploit protection)
        blockPlaceListener = new BlockPlaceListener(this, placedBlockTracker);
        blockPlaceListener.register(getEntityStoreRegistry());

        // Mob Kills
        mobKillListener = new MobKillListener(
                this, rewardCalculator, economyBridge,
                multiplierResolver, antiFarmManager, cooldownTracker
        );
        mobKillListener.register(getEventRegistry(), levelBridge, getEntityStoreRegistry());

        // Mining
        miningListener = new MiningListener(
                this, rewardCalculator, economyBridge,
                multiplierResolver, antiFarmManager, cooldownTracker,
                placedBlockTracker
        );
        miningListener.register(getEntityStoreRegistry());

        // Woodcutting
        woodcuttingListener = new WoodcuttingListener(
                this, rewardCalculator, economyBridge,
                multiplierResolver, antiFarmManager, cooldownTracker,
                placedBlockTracker
        );
        woodcuttingListener.register(getEntityStoreRegistry());

        // Farming
        farmingListener = new FarmingListener(
                this, rewardCalculator, economyBridge,
                multiplierResolver, antiFarmManager, cooldownTracker,
                placedBlockTracker
        );
        farmingListener.register(getEntityStoreRegistry());
    }

    /**
     * Регистрирует PlayerReadyEvent для кеширования Player-объектов.
     * Необходимо для отправки actionbar-уведомлений (Player.sendActionBar).
     */
    @SuppressWarnings("unchecked")
    private void registerPlayerTracking() {
        String[] candidates = {
                "com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent",
                "com.hypixel.hytale.event.server.PlayerReadyEvent",
        };

        ClassLoader serverLoader = getClass().getClassLoader();
        com.hypixel.hytale.event.EventRegistry eventRegistry = getEventRegistry();

        for (String className : candidates) {
            try {
                Class<?> eventClass = Class.forName(className, true, serverLoader);
                java.lang.reflect.Method registerMethod = eventRegistry.getClass()
                        .getMethod("registerGlobal", Class.class, java.util.function.Consumer.class);

                java.util.function.Consumer<Object> handler = event -> {
                    try {
                        Object player = extractPlayer(event);
                        if (player instanceof com.hypixel.hytale.server.core.entity.entities.Player p) {
                            java.util.UUID uuid = p.getUuid();
                            if (uuid != null) {
                                com.crystalrealm.ecotaleincome.util.MessageUtil.cachePlayer(uuid, p);
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.debug("Failed to cache player from event: {}", e.getMessage());
                    }
                };

                registerMethod.invoke(eventRegistry, eventClass, handler);
                LOGGER.info("Player tracking registered via {} — actionbar notifications enabled.", className);
                return;

            } catch (ClassNotFoundException ignored) {
            } catch (Exception e) {
                LOGGER.debug("Could not register player tracking via {}: {}", className, e.getMessage());
            }
        }

        LOGGER.warn("PlayerReadyEvent not found — actionbar notifications will fall back to chat.");
    }

    /**
     * Извлекает Player из события через reflection.
     */
    @javax.annotation.Nullable
    private static Object extractPlayer(@javax.annotation.Nonnull Object event) {
        for (String methodName : new String[]{"getPlayer", "getSource"}) {
            try {
                java.lang.reflect.Method getter = event.getClass().getMethod(methodName);
                Object result = getter.invoke(event);
                if (result instanceof com.hypixel.hytale.server.core.entity.entities.Player) {
                    return result;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) { break; }
        }
        return null;
    }

    /**
     * Периодическая очистка кешей защиты от утечек памяти.
     */
    private void cleanupCaches() {
        if (antiFarmManager != null) antiFarmManager.cleanup();
        if (cooldownTracker != null) cooldownTracker.cleanup();
        if (placedBlockTracker != null) {
            long expireMs = configManager.getConfig().getProtection()
                    .getPlacedBlockExpireMinutes() * 60_000L;
            placedBlockTracker.cleanup(expireMs);
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  PUBLIC GETTERS
    // ═════════════════════════════════════════════════════════════

    @Nonnull
    public String getVersion() {
        return "1.3.0";
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

    /** @return мост к системе уровней; всегда non-null, но isAvailable() может быть false */
    @Nonnull
    public LevelBridge getLevelBridge() {
        return levelBridge;
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

    @javax.annotation.Nullable
    public RewardAggregator getRewardAggregator() {
        return rewardAggregator;
    }
}
