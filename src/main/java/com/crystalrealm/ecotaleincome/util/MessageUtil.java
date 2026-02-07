package com.crystalrealm.ecotaleincome.util;

import com.crystalrealm.ecotaleincome.EcoTaleIncomePlugin;
import com.crystalrealm.ecotaleincome.lang.LangManager;

import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Утилита для форматирования и отправки уведомлений о наградах.
 *
 * <p>Использует MiniMessage-формат для цветов/стилей — это нативный формат Hytale.
 * Отправляет уведомления через reflection на Player.sendActionBar() и Player.sendMessage().</p>
 */
public final class MessageUtil {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    private static final DecimalFormat COIN_FORMAT;

    /** Кеш PlayerRef объектов по UUID для отправки сообщений из ECS контекста */
    private static final Map<UUID, Object> PLAYER_REF_CACHE = new ConcurrentHashMap<>();

    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setDecimalSeparator('.');
        COIN_FORMAT = new DecimalFormat("#,##0.##", symbols);
    }

    private MessageUtil() {}

    // ── PlayerRef Cache ─────────────────────────────────────────

    /**
     * Кеширует PlayerRef для последующей отправки уведомлений.
     * Вызывается из ECS листенеров при обработке событий.
     */
    public static void cachePlayerRef(UUID uuid, Object playerRef) {
        if (uuid != null && playerRef != null) {
            PLAYER_REF_CACHE.put(uuid, playerRef);
        }
    }

    /** Очищает кеш (вызывается при shutdown). */
    public static void clearCache() {
        PLAYER_REF_CACHE.clear();
    }

    // ── Reward Notifications ────────────────────────────────────

    /**
     * Отправляет уведомление о награде игроку.
     *
     * <p>Пробует несколько способов доставки:
     * <ol>
     *   <li>PlayerRef → getPlayer() → sendActionBar(String) — показ над хотбаром</li>
     *   <li>PlayerRef → getPlayer() → sendMessage(String) — чат (fallback)</li>
     *   <li>PlayerRef → sendMessage(Object) — через PlayerRef напрямую</li>
     * </ol>
     */
    public static void sendRewardNotification(EcoTaleIncomePlugin plugin,
                                              UUID playerUuid,
                                              double amount,
                                              String source,
                                              String category) {
        try {
            String text = buildNotificationText(plugin, playerUuid, amount, source, category);

            Object playerRef = PLAYER_REF_CACHE.get(playerUuid);
            if (playerRef != null) {
                boolean sent = trySendViaPlayerRef(playerRef, text);
                if (sent) {
                    LOGGER.debug("Reward notification sent to {}: {}", playerUuid, text);
                } else {
                    LOGGER.debug("Could not send notification to {} (no delivery method worked)", playerUuid);
                }
            } else {
                LOGGER.debug("No cached PlayerRef for {} — cannot send notification", playerUuid);
            }
        } catch (Throwable e) {
            LOGGER.debug("Notification send failed for {}: {}", playerUuid, e.getMessage());
        }
    }

    /**
     * Формирует текст уведомления из lang-шаблона.
     */
    private static String buildNotificationText(EcoTaleIncomePlugin plugin,
                                                UUID playerUuid,
                                                double amount,
                                                String source,
                                                String category) {
        LangManager lang = plugin.getLangManager();
        String langCode = lang.getPlayerLang(playerUuid);
        String formatted = formatSourceName(source);

        // "reward.mob", "reward.ore", "reward.wood", "reward.crop"
        String key = "reward." + category;
        return lang.getForLang(langCode, key,
                "amount", formatCoins(amount),
                "source", formatted);
    }

    /**
     * Отправляет сообщение через PlayerRef → getPlayer() → sendActionBar/sendMessage.
     * Полностью через reflection для совместимости.
     *
     * @return true если сообщение было отправлено хотя бы одним способом
     */
    private static boolean trySendViaPlayerRef(Object playerRef, String text) {
        try {
            // Конвертируем MiniMessage → JSON и создаём Message.parse(json)
            String jsonText = MiniMessageParser.toJson(text);
            String plainText = MiniMessageParser.stripTags(text);
            Class<?> msgClass = null;
            Object parsedMsg = null;
            try {
                msgClass = Class.forName("com.hypixel.hytale.server.core.Message");
                Method parseMethod = msgClass.getMethod("parse", String.class);
                parsedMsg = parseMethod.invoke(null, jsonText);
            } catch (Exception e) {
                LOGGER.debug("Message.parse(json) failed: {}", e.getMessage());
            }

            // Шаг 1: PlayerRef.getPlayer() → Player
            Object player = null;
            try {
                Method getPlayer = playerRef.getClass().getMethod("getPlayer");
                player = getPlayer.invoke(playerRef);
            } catch (NoSuchMethodException e) {
                LOGGER.debug("PlayerRef.getPlayer() not available");
            }

            if (player != null) {
                // Попытка 1: player.sendMessage(Message) — цветной текст
                if (parsedMsg != null && msgClass != null) {
                    try {
                        Method sendMsg = player.getClass().getMethod("sendMessage", msgClass);
                        sendMsg.invoke(player, parsedMsg);
                        return true;
                    } catch (NoSuchMethodException ignored) {}
                }

                // Попытка 2: sendActionBar(String) — plain text fallback
                try {
                    Method sendActionBar = player.getClass().getMethod("sendActionBar", String.class);
                    sendActionBar.invoke(player, plainText);
                    return true;
                } catch (NoSuchMethodException ignored) {}

                // Попытка 3: sendMessage(String) — plain text fallback
                try {
                    Method sendMsg = player.getClass().getMethod("sendMessage", String.class);
                    sendMsg.invoke(player, plainText);
                    return true;
                } catch (NoSuchMethodException ignored) {}
            }

            // Попытка 4: PlayerRef.sendMessage(Message)
            if (parsedMsg != null && msgClass != null) {
                try {
                    Method sendMsg = playerRef.getClass().getMethod("sendMessage", msgClass);
                    sendMsg.invoke(playerRef, parsedMsg);
                    return true;
                } catch (NoSuchMethodException ignored) {}

                // Попытка 5: PlayerRef.sendMessage(Object)
                try {
                    Method sendMsg = playerRef.getClass().getMethod("sendMessage", Object.class);
                    sendMsg.invoke(playerRef, parsedMsg);
                    return true;
                } catch (NoSuchMethodException ignored) {}
            }

        } catch (Throwable e) {
            LOGGER.debug("trySendViaPlayerRef failed: {}", e.getMessage());
        }

        return false;
    }

    // ── Chat Messages (from commands) ───────────────────────────

    /**
     * Отправляет сообщение через CommandContext → sendMessage(Message).
     * Используется в командах.
     */
    public static void sendViaContext(Object context, String text) {
        try {
            String jsonText = MiniMessageParser.toJson(text);
            Class<?> msgClass = Class.forName("com.hypixel.hytale.server.core.Message");
            Method parseMethod = msgClass.getMethod("parse", String.class);
            Object msg = parseMethod.invoke(null, jsonText);

            Method sendMsg = context.getClass().getMethod("sendMessage", msgClass);
            sendMsg.invoke(context, msg);
        } catch (Exception e) {
            LOGGER.debug("sendViaContext failed: {}", e.getMessage());
        }
    }

    // ── Formatting Helpers (MiniMessage format) ─────────────────

    public static String formatCoins(double amount) {
        return COIN_FORMAT.format(amount);
    }

    public static String formatMultiplier(double multiplier) {
        if (multiplier == 1.0) return "<gray>×1.0";
        if (multiplier > 1.0) return String.format("<green>×%.2f", multiplier);
        return String.format("<red>×%.2f", multiplier);
    }

    /**
     * Форматирует название источника для отображения.
     * "copper_ore_block" → "Copper Ore Block"
     */
    public static String formatSourceName(String source) {
        if (source == null || source.isEmpty()) return "Unknown";

        String clean = source
                .replace("_", " ")
                .replace("hytale:", "")
                .replace("  ", " ")
                .trim();

        StringBuilder sb = new StringBuilder();
        boolean capitalize = true;
        for (char c : clean.toCharArray()) {
            if (c == ' ') {
                sb.append(' ');
                capitalize = true;
            } else if (capitalize) {
                sb.append(Character.toUpperCase(c));
                capitalize = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /**
     * Форматирует anti-farm множитель.
     */
    public static String formatPercent(double mult) {
        String color = mult >= 0.8 ? "<green>" : (mult >= 0.5 ? "<yellow>" : "<red>");
        return color + String.format("%.0f%%", mult * 100);
    }
}
