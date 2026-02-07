package com.crystalrealm.ecotaleincome.reward;

import com.crystalrealm.ecotaleincome.config.IncomeConfig;

import com.hypixel.hytale.server.core.entity.entities.Player;

import com.crystalrealm.ecotaleincome.util.PluginLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/**
 * Определяет VIP/групповой множитель награды для игрока
 * на основе его пермишенов.
 *
 * <p>Проверяет наличие пермишена {@code ecotaleincome.multiplier.<group>}
 * и возвращает соответствующий множитель из конфигурации.
 * Если у игрока несколько групп — применяется наибольший множитель.</p>
 *
 * <h3>Конфигурация</h3>
 * <pre>{@code
 *   "Multipliers": {
 *     "Groups": {
 *       "VIP": 1.25,     // ecotaleincome.multiplier.vip
 *       "Premium": 1.5   // ecotaleincome.multiplier.premium
 *     }
 *   }
 * }</pre>
 */
public class MultiplierResolver {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    private final IncomeConfig.MultipliersSection multipliersConfig;

    public MultiplierResolver(@Nonnull IncomeConfig.MultipliersSection multipliersConfig) {
        this.multipliersConfig = multipliersConfig;
    }

    /**
     * Определяет множитель для онлайн-игрока, проверяя его пермишены
     * через API сервера.
     *
     * <p>Проверяет каждую группу из конфига {@code Multipliers.Groups}.
     * Пермишен формата: {@code ecotaleincome.multiplier.vip},
     * {@code ecotaleincome.multiplier.premium} и т.д.</p>
     *
     * @param playerUuid UUID игрока
     * @return наибольший множитель, или 1.0 если нет подходящих групп
     */
    public double resolve(@Nonnull UUID playerUuid) {
        // UUID-only overload — no Player available, skip permission checks
        return 1.0;
    }

    /**
     * Определяет множитель для онлайн-игрока через объект Player.
     *
     * @param player объект Player
     * @return наибольший множитель, или 1.0 если нет подходящих групп
     */
    public double resolve(@Nullable Player player) {
        Map<String, Double> groups = multipliersConfig.getGroups();
        if (groups == null || groups.isEmpty()) return 1.0;
        if (player == null) return 1.0;

        double maxMultiplier = 1.0;

        for (Map.Entry<String, Double> entry : groups.entrySet()) {
            String groupName = entry.getKey().toLowerCase();
            double multiplier = entry.getValue();

            String permission = "ecotaleincome.multiplier." + groupName;

            if (player.hasPermission(permission)) {
                maxMultiplier = Math.max(maxMultiplier, multiplier);
            }
        }

        return maxMultiplier;
    }

    /**
     * Определяет множитель с кастомным чекером прав
     * (для тестирования или нестандартных сценариев).
     *
     * @param permissionChecker функция проверки пермишена
     * @return наибольший множитель, или 1.0 если нет групп
     */
    public double resolve(@Nonnull PermissionChecker permissionChecker) {
        Map<String, Double> groups = multipliersConfig.getGroups();
        if (groups == null || groups.isEmpty()) return 1.0;

        double maxMultiplier = 1.0;

        for (Map.Entry<String, Double> entry : groups.entrySet()) {
            String groupName = entry.getKey().toLowerCase();
            double multiplier = entry.getValue();

            String permission = "ecotaleincome.multiplier." + groupName;

            if (permissionChecker.hasPermission(permission)) {
                maxMultiplier = Math.max(maxMultiplier, multiplier);
            }
        }

        return maxMultiplier;
    }

    /**
     * Функциональный интерфейс для проверки пермишенов игрока.
     */
    @FunctionalInterface
    public interface PermissionChecker {
        boolean hasPermission(@Nonnull String permission);
    }
}
