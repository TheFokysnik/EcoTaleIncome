package com.crystalrealm.ecotaleincome.rpg;

import com.crystalrealm.ecotaleincome.util.PluginLogger;
import org.zuxaw.plugin.api.RPGLevelingAPI;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Мост к RPG Leveling API (опциональная зависимость).
 *
 * <p>Предоставляет safe-обёртки для получения уровней игроков и мобов.
 * Если RPG Leveling не установлен, все методы возвращают безопасные значения.</p>
 */
public class RPGLevelingBridge {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    private boolean available;
    private RPGLevelingAPI api;

    public RPGLevelingBridge() {
        try {
            Class.forName("org.zuxaw.plugin.api.RPGLevelingAPI");
            if (RPGLevelingAPI.isAvailable()) {
                api = RPGLevelingAPI.get();
                available = (api != null);
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            available = false;
        }
    }

    /**
     * @return true если RPG Leveling загружен и API доступен
     */
    public boolean isAvailable() {
        return available && api != null;
    }

    /**
     * @return версия RPG Leveling API, или "N/A"
     */
    @Nonnull
    public String getVersion() {
        if (!isAvailable()) return "N/A";
        try {
            return RPGLevelingAPI.getVersion();
        } catch (Exception e) {
            return "N/A";
        }
    }

    /**
     * Получает уровень игрока.
     *
     * @param playerUuid UUID игрока
     * @return уровень, или -1 если недоступен
     */
    public int getPlayerLevel(@Nonnull UUID playerUuid) {
        if (!isAvailable()) return -1;

        try {
            return api.getPlayerLevel(playerUuid);
        } catch (Exception e) {
            LOGGER.warn("Failed to get player level for {}: {}", playerUuid, e.getMessage());
            return -1;
        }
    }

    /**
     * Получает информацию об уровне игрока (уровень, XP, прогресс).
     *
     * @param playerUuid UUID игрока
     * @return объект PlayerLevelInfo, или null
     */
    @Nullable
    public Object getPlayerLevelInfo(@Nonnull UUID playerUuid) {
        if (!isAvailable()) return null;

        try {
            return api.getPlayerLevelInfo(playerUuid);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @return прямая ссылка на RPG Leveling API (для слушателей)
     */
    @Nullable
    public RPGLevelingAPI getApi() {
        return api;
    }
}
