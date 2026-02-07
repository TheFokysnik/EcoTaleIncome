package com.crystalrealm.ecotaleincome.economy;

import com.crystalrealm.ecotaleincome.util.PluginLogger;

import javax.annotation.Nonnull;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Economy bridge that delegates to a pluggable {@link EconomyProvider}.
 *
 * <p>By default uses {@link EcotaleProvider}. Other economy plugins can be
 * supported by implementing {@link EconomyProvider} and registering via
 * {@link #registerProvider(String, EconomyProvider)}.</p>
 *
 * <p>The active provider is selected by config key ({@code General.EconomyProvider})
 * or auto-detected from registered providers.</p>
 */
public class EconomyBridge {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    /** Registered providers: key → provider */
    private final Map<String, EconomyProvider> providers = new LinkedHashMap<>();

    /** Currently active provider */
    private EconomyProvider activeProvider;

    public EconomyBridge() {
        // Register built-in provider
        registerProvider("ecotale", new EcotaleProvider());
    }

    /**
     * Registers an economy provider.
     *
     * @param key      unique identifier (e.g. "ecotale", "vault", "custom")
     * @param provider the provider implementation
     */
    public void registerProvider(@Nonnull String key, @Nonnull EconomyProvider provider) {
        providers.put(key.toLowerCase(), provider);
        LOGGER.info("Registered economy provider: {} ({})", key, provider.getName());
    }

    /**
     * Selects and activates a provider by config key.
     * Falls back to the first available provider if the requested one is not found.
     *
     * @param preferredKey the key from config (e.g. "ecotale")
     * @return true if a working provider was activated
     */
    public boolean activate(@Nonnull String preferredKey) {
        // Try preferred provider first
        EconomyProvider preferred = providers.get(preferredKey.toLowerCase());
        if (preferred != null && preferred.isAvailable()) {
            activeProvider = preferred;
            LOGGER.info("Economy provider activated: {} ({})", preferredKey, preferred.getName());
            return true;
        }

        if (preferred != null) {
            LOGGER.warn("Preferred economy provider '{}' is not available. Trying others...", preferredKey);
        } else {
            LOGGER.warn("Economy provider '{}' not found. Trying others...", preferredKey);
        }

        // Fallback: try any available provider
        for (Map.Entry<String, EconomyProvider> entry : providers.entrySet()) {
            if (entry.getValue().isAvailable()) {
                activeProvider = entry.getValue();
                LOGGER.info("Economy provider fallback: {} ({})", entry.getKey(), entry.getValue().getName());
                return true;
            }
        }

        LOGGER.error("No economy provider available! Rewards will not work.");
        activeProvider = null;
        return false;
    }

    /**
     * Checks if an economy provider is active and available.
     */
    public boolean isAvailable() {
        return activeProvider != null && activeProvider.isAvailable();
    }

    /**
     * Returns the name of the active economy provider.
     */
    @Nonnull
    public String getProviderName() {
        return activeProvider != null ? activeProvider.getName() : "none";
    }

    /**
     * Deposits currency to a player's account via the active provider.
     *
     * @param playerUuid player UUID
     * @param amount     positive amount to deposit
     * @param reason     reason for the transaction
     * @return true if successful
     */
    public boolean deposit(@Nonnull UUID playerUuid, double amount, @Nonnull String reason) {
        if (activeProvider == null || amount <= 0) return false;
        try {
            return activeProvider.deposit(playerUuid, amount, reason);
        } catch (Exception e) {
            LOGGER.warn("Failed to deposit {} to {}: {}", amount, playerUuid, e.getMessage());
            return false;
        }
    }

    /**
     * Gets a player's balance via the active provider.
     *
     * @param playerUuid player UUID
     * @return balance, or -1 if unavailable
     */
    public double getBalance(@Nonnull UUID playerUuid) {
        if (activeProvider == null) return -1;
        try {
            return activeProvider.getBalance(playerUuid);
        } catch (Exception e) {
            LOGGER.warn("Failed to get balance for {}: {}", playerUuid, e.getMessage());
            return -1;
        }
    }
}
