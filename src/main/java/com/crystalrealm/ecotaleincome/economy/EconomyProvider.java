package com.crystalrealm.ecotaleincome.economy;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Interface for economy providers.
 *
 * <p>Implement this interface to add support for a new economy plugin.
 * The provider must be able to deposit currency and query player balances.</p>
 *
 * <p>Built-in providers:</p>
 * <ul>
 *   <li>{@code ecotale} — Ecotale economy (default)</li>
 * </ul>
 *
 * <p>To add a custom provider, implement this interface and register it
 * via {@link EconomyBridge#registerProvider(String, EconomyProvider)}.</p>
 */
public interface EconomyProvider {

    /**
     * Returns the display name of this economy provider.
     */
    @Nonnull
    String getName();

    /**
     * Checks whether the underlying economy plugin is loaded and available.
     */
    boolean isAvailable();

    /**
     * Deposits currency to a player's account.
     *
     * @param playerUuid player UUID
     * @param amount     positive amount to deposit
     * @param reason     reason for the transaction (for logging)
     * @return true if the deposit was successful
     */
    boolean deposit(@Nonnull UUID playerUuid, double amount, @Nonnull String reason);

    /**
     * Gets the current balance of a player.
     *
     * @param playerUuid player UUID
     * @return balance, or -1 if unavailable
     */
    double getBalance(@Nonnull UUID playerUuid);
}
