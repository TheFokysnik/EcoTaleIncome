package com.crystalrealm.ecotaleincome.api;

import com.crystalrealm.ecotaleincome.EcoTaleIncomePlugin;
import com.crystalrealm.ecotaleincome.economy.EconomyBridge;
import com.crystalrealm.ecotaleincome.economy.EconomyProvider;
import com.crystalrealm.ecotaleincome.util.PluginLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Public API for EcoTaleIncome — allows external plugins to integrate
 * their own economy systems with EcoTaleIncome.
 *
 * <h3>Quick Start (for other mod developers)</h3>
 * <pre>{@code
 * // 1. Implement the EconomyProvider interface
 * public class MyEconomyProvider implements EconomyProvider {
 *     public String getName() { return "MyEconomy"; }
 *     public boolean isAvailable() { return true; }
 *     public boolean deposit(UUID player, double amount, String reason) { ... }
 *     public double getBalance(UUID player) { ... }
 * }
 *
 * // 2. Register your provider (in your plugin's start() method)
 * EcoTaleIncomeAPI.registerEconomyProvider("myeconomy", new MyEconomyProvider());
 *
 * // 3. Set "EconomyProvider": "myeconomy" in EcoTaleIncome config
 * }</pre>
 *
 * <h3>Auto-activation</h3>
 * <p>If your provider is registered after EcoTaleIncome has started and the
 * config specifies your provider key, call {@link #activateProvider(String)}
 * to switch to it at runtime.</p>
 *
 * @author CrystalRealm
 * @since 1.0.0
 */
public final class EcoTaleIncomeAPI {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    private EcoTaleIncomeAPI() {} // No instantiation

    // ═════════════════════════════════════════════════════════════
    //  ECONOMY PROVIDER REGISTRATION
    // ═════════════════════════════════════════════════════════════

    /**
     * Registers a custom economy provider with EcoTaleIncome.
     *
     * <p>Call this from your plugin's {@code start()} method. The provider
     * becomes available for selection in EcoTaleIncome's config file under
     * {@code General.EconomyProvider}.</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * EcoTaleIncomeAPI.registerEconomyProvider("vault", new VaultProvider());
     * }</pre>
     *
     * @param key      unique identifier for the provider (lowercase recommended).
     *                 This is the value users set in {@code General.EconomyProvider}.
     * @param provider your {@link EconomyProvider} implementation
     * @return true if registered successfully, false if EcoTaleIncome is not loaded
     */
    public static boolean registerEconomyProvider(@Nonnull String key,
                                                  @Nonnull EconomyProvider provider) {
        EconomyBridge bridge = getBridge();
        if (bridge == null) {
            LOGGER.warn("Cannot register economy provider '{}': EcoTaleIncome is not loaded.", key);
            return false;
        }

        bridge.registerProvider(key, provider);
        LOGGER.info("External economy provider registered via API: {} ({})", key, provider.getName());
        return true;
    }

    /**
     * Activates a specific economy provider by key.
     *
     * <p>Useful when registering a provider after EcoTaleIncome has already
     * started and you want to switch to it immediately.</p>
     *
     * @param key the provider key to activate
     * @return true if the provider was activated successfully
     */
    public static boolean activateProvider(@Nonnull String key) {
        EconomyBridge bridge = getBridge();
        if (bridge == null) {
            LOGGER.warn("Cannot activate provider '{}': EcoTaleIncome is not loaded.", key);
            return false;
        }

        boolean result = bridge.activate(key);
        if (result) {
            LOGGER.info("Economy provider switched to '{}' via API.", key);
        }
        return result;
    }

    /**
     * Returns the name of the currently active economy provider.
     *
     * @return provider name, or "none" if no provider is active
     */
    @Nonnull
    public static String getActiveProviderName() {
        EconomyBridge bridge = getBridge();
        return bridge != null ? bridge.getProviderName() : "none";
    }

    /**
     * Checks whether EcoTaleIncome has an active economy provider.
     *
     * @return true if an economy provider is active and available
     */
    public static boolean isEconomyAvailable() {
        EconomyBridge bridge = getBridge();
        return bridge != null && bridge.isAvailable();
    }

    /**
     * Returns the plugin version string.
     *
     * @return version, or "unknown" if not loaded
     */
    @Nonnull
    public static String getVersion() {
        try {
            return EcoTaleIncomePlugin.getInstance().getVersion();
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  INTERNAL
    // ═════════════════════════════════════════════════════════════

    @Nullable
    private static EconomyBridge getBridge() {
        try {
            EcoTaleIncomePlugin instance = EcoTaleIncomePlugin.getInstance();
            return instance != null ? instance.getEconomyBridge() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
