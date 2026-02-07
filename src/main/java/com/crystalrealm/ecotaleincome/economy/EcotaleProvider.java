package com.crystalrealm.ecotaleincome.economy;

import com.ecotale.api.EcotaleAPI;
import com.crystalrealm.ecotaleincome.util.PluginLogger;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Economy provider implementation for the Ecotale plugin.
 * Uses {@link EcotaleAPI} for direct balance operations.
 */
public class EcotaleProvider implements EconomyProvider {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    private final boolean available;

    public EcotaleProvider() {
        boolean found;
        try {
            Class.forName("com.ecotale.api.EcotaleAPI");
            found = true;
        } catch (ClassNotFoundException e) {
            found = false;
        }
        this.available = found;
    }

    @Nonnull
    @Override
    public String getName() {
        return "Ecotale";
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public boolean deposit(@Nonnull UUID playerUuid, double amount, @Nonnull String reason) {
        if (!available || amount <= 0) return false;
        try {
            return EcotaleAPI.deposit(playerUuid, amount, reason);
        } catch (Exception e) {
            LOGGER.warn("Ecotale deposit failed for {} ({}): {}", playerUuid, amount, e.getMessage());
            return false;
        }
    }

    @Override
    public double getBalance(@Nonnull UUID playerUuid) {
        if (!available) return -1;
        try {
            return EcotaleAPI.getBalance(playerUuid);
        } catch (Exception e) {
            LOGGER.warn("Ecotale getBalance failed for {}: {}", playerUuid, e.getMessage());
            return -1;
        }
    }
}
