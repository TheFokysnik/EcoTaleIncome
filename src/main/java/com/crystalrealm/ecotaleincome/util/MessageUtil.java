package com.crystalrealm.ecotaleincome.util;

import com.crystalrealm.ecotaleincome.EcoTaleIncomePlugin;
import com.crystalrealm.ecotaleincome.lang.LangManager;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

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
 * <p>Использует нативную систему HUD-уведомлений Hytale через
 * {@code NotificationUtil.sendNotification()} — красивые всплывающие
 * уведомления справа на экране (как в NewItemIndicator).</p>
 *
 * <p>Цепочка доставки:</p>
 * <ol>
 *   <li>{@code NotificationUtil.sendNotification()} — нативный HUD popup (приоритет)</li>
 *   <li>{@code Player.sendActionBar(String)} — fallback над хотбаром</li>
 *   <li>{@code Player.sendMessage(Message)} — fallback в чат</li>
 * </ol>
 */
public final class MessageUtil {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    private static final DecimalFormat COIN_FORMAT;

    /** Кеш PlayerRef объектов по UUID для доступа к getPacketHandler() */
    private static final Map<UUID, Object> PLAYER_REF_CACHE = new ConcurrentHashMap<>();

    /** Кеш Player объектов по UUID — заполняется через PlayerReadyEvent */
    private static final Map<UUID, Player> PLAYER_CACHE = new ConcurrentHashMap<>();

    // ── Reflection cache for NotificationUtil ───────────────────
    private static volatile boolean notificationApiChecked = false;
    private static volatile boolean notificationApiAvailable = false;
    private static Method notificationSendMethod;
    private static Method messageRawMethod;
    private static Method messageColorMethod;
    private static Class<?> notificationStyleClass;
    private static Object notificationStyleDefault;
    private static Method getPacketHandlerMethod;

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

    /**
     * Кеширует Player-объект для fallback-уведомлений.
     * Вызывается при PlayerReadyEvent.
     */
    public static void cachePlayer(UUID uuid, Player player) {
        if (uuid != null && player != null) {
            PLAYER_CACHE.put(uuid, player);
            LOGGER.debug("Cached Player for notifications: {}", uuid);
        }
    }

    /** Очищает кеш (вызывается при shutdown). */
    public static void clearCache() {
        PLAYER_REF_CACHE.clear();
        PLAYER_CACHE.clear();
    }

    /** Returns a cached Player for the given UUID (used by RewardAggregator). */
    @javax.annotation.Nullable
    public static Player getCachedPlayer(UUID uuid) {
        return PLAYER_CACHE.get(uuid);
    }

    /** Returns a cached PlayerRef for the given UUID (used by RewardAggregator). */
    @javax.annotation.Nullable
    public static Object getCachedPlayerRef(UUID uuid) {
        return PLAYER_REF_CACHE.get(uuid);
    }

    // ── NotificationUtil API Detection ──────────────────────────

    /**
     * Проверяет доступность NotificationUtil.sendNotification() через reflection.
     * Кеширует результат — вызывается один раз.
     */
    private static synchronized void checkNotificationApi() {
        if (notificationApiChecked) return;
        notificationApiChecked = true;

        try {
            // Load NotificationUtil
            Class<?> notifUtilClass = Class.forName("com.hypixel.hytale.server.core.util.NotificationUtil");

            // Load required types
            Class<?> messageClass = Class.forName("com.hypixel.hytale.server.core.Message");
            Class<?> packetHandlerClass = Class.forName("com.hypixel.hytale.server.core.io.PacketHandler");
            notificationStyleClass = Class.forName("com.hypixel.hytale.protocol.packets.interface_.NotificationStyle");
            Class<?> itemMetaClass = Class.forName("com.hypixel.hytale.protocol.ItemWithAllMetadata");

            // Get NotificationStyle.Default
            notificationStyleDefault = notificationStyleClass.getField("Default").get(null);

            // Get sendNotification method
            notificationSendMethod = notifUtilClass.getMethod("sendNotification",
                    packetHandlerClass, messageClass, messageClass,
                    String.class, itemMetaClass, notificationStyleClass);

            // Get Message.raw(String) and Message.color(String)
            messageRawMethod = messageClass.getMethod("raw", String.class);
            messageColorMethod = messageClass.getMethod("color", String.class);

            // Get PlayerRef.getPacketHandler()
            Class<?> playerRefClass = Class.forName("com.hypixel.hytale.server.core.universe.PlayerRef");
            getPacketHandlerMethod = playerRefClass.getMethod("getPacketHandler");

            notificationApiAvailable = true;
            LOGGER.info("Native HUD notification API (NotificationUtil) detected and ready.");

        } catch (ClassNotFoundException e) {
            LOGGER.info("NotificationUtil not available (class not found: {}). Will use fallback delivery.", e.getMessage());
        } catch (NoSuchMethodException e) {
            LOGGER.info("NotificationUtil API signature mismatch: {}. Will use fallback delivery.", e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("Failed to initialize NotificationUtil API: {}. Will use fallback delivery.", e.getMessage());
        }
    }

    // ── Reward Notifications ────────────────────────────────────

    /**
     * Отправляет уведомление о награде игроку.
     *
     * <p>Всегда пытается использовать нативный HUD popup (NotificationUtil).</p>
     * <p>При режиме "popup" — агрегирует через RewardAggregator, затем отправляет HUD.</p>
     */
    public static void sendRewardNotification(EcoTaleIncomePlugin plugin,
                                              UUID playerUuid,
                                              double amount,
                                              String source,
                                              String category) {
        try {
            String mode = plugin.getConfigManager().getConfig().getGeneral().getNotifyMode();

            // "popup" mode — delegate to RewardAggregator (batched HUD notifications)
            if ("popup".equalsIgnoreCase(mode)) {
                RewardAggregator aggregator = plugin.getRewardAggregator();
                if (aggregator != null) {
                    aggregator.submit(playerUuid, amount, source, category);
                    return;
                }
                // Fallback if aggregator not initialized
                mode = "notification";
            }

            // All modes now use the HUD notification system
            sendHudNotification(plugin, playerUuid, amount, source, category);

        } catch (Throwable e) {
            LOGGER.debug("Notification send failed for {}: {}", playerUuid, e.getMessage());
        }
    }

    // ── HUD Notification (Native) ───────────────────────────────

    /**
     * Отправляет красивое HUD-уведомление справа на экране через
     * {@code NotificationUtil.sendNotification()} — как в NewItemIndicator.
     *
     * <p>Формат: Title = "+12.50" (зелёный), Description = "Iron Ore" (серый).</p>
     * <p>Иконка зависит от категории (моб, руда, дерево, урожай).</p>
     */
    public static boolean sendHudNotification(EcoTaleIncomePlugin plugin,
                                              UUID playerUuid,
                                              double amount,
                                              String source,
                                              String category) {
        checkNotificationApi();

        if (notificationApiAvailable) {
            boolean sent = trySendNativeNotification(plugin, playerUuid, amount, source, category);
            if (sent) return true;
        }

        // Fallback: actionbar → chat
        return sendFallbackNotification(plugin, playerUuid, amount, source, category);
    }

    /**
     * Sends a native HUD notification via NotificationUtil.sendNotification().
     */
    private static boolean trySendNativeNotification(EcoTaleIncomePlugin plugin,
                                                     UUID playerUuid,
                                                     double amount,
                                                     String source,
                                                     String category) {
        try {
            Object playerRef = PLAYER_REF_CACHE.get(playerUuid);
            if (playerRef == null) {
                LOGGER.debug("No cached PlayerRef for {} — cannot send native notification", playerUuid);
                return false;
            }

            // Get PacketHandler from PlayerRef
            Object packetHandler = getPacketHandlerMethod.invoke(playerRef);
            if (packetHandler == null) {
                LOGGER.debug("PacketHandler is null for {}", playerUuid);
                return false;
            }

            // Build title: "+12.50" in gold/green
            String amountStr = "+" + formatCoins(amount);
            String titleColor = getCategoryHexColor(category);
            Object titleMsg = messageRawMethod.invoke(null, amountStr);
            titleMsg = messageColorMethod.invoke(titleMsg, titleColor);

            // Build description: source name in light gray
            String formattedSource = formatSourceName(source);
            Object descMsg = messageRawMethod.invoke(null, formattedSource);
            descMsg = messageColorMethod.invoke(descMsg, "#B0B0B0");

            // Icon path based on category
            String iconPath = getCategoryIconPath(category);

            // Send notification
            notificationSendMethod.invoke(null,
                    packetHandler, titleMsg, descMsg,
                    iconPath, null, notificationStyleDefault);

            LOGGER.debug("HUD notification sent to {}: {} ({})", playerUuid, amountStr, formattedSource);
            return true;

        } catch (Throwable e) {
            LOGGER.debug("Native notification failed for {}: {}", playerUuid, e.getMessage());
            return false;
        }
    }

    /**
     * Fallback: отправляет уведомление через sendActionBar или sendMessage.
     */
    private static boolean sendFallbackNotification(EcoTaleIncomePlugin plugin,
                                                    UUID playerUuid,
                                                    double amount,
                                                    String source,
                                                    String category) {
        String text = buildNotificationText(plugin, playerUuid, amount, source, category);
        String plainText = MiniMessageParser.stripTags(text);

        // Try actionbar first
        Player player = PLAYER_CACHE.get(playerUuid);
        if (player != null) {
            try {
                player.sendActionBar(plainText);
                LOGGER.debug("Fallback ActionBar sent to {}: {}", playerUuid, plainText);
                return true;
            } catch (Exception e) {
                LOGGER.debug("sendActionBar failed: {}", e.getMessage());
            }
        }

        // Try chat via PlayerRef
        Object playerRef = PLAYER_REF_CACHE.get(playerUuid);
        if (playerRef != null) {
            boolean sent = trySendViaPlayerRef(playerRef, text);
            if (sent) return true;
        }

        // Last resort: plain sendMessage
        if (player != null) {
            try {
                player.sendMessage(plainText);
                return true;
            } catch (Exception ignored) {}
        }

        LOGGER.debug("All notification fallbacks failed for {}", playerUuid);
        return false;
    }

    // ── Public formatted delivery (used by RewardAggregator) ────

    /**
     * Sends an aggregated reward notification via native HUD popup.
     * Called by RewardAggregator after batching rewards.
     *
     * @param playerUuid  player to notify
     * @param totalAmount total aggregated coin amount
     * @param sourceList  formatted source list (e.g. "Iron Ore ×3, Copper Ore ×2")
     * @param category    dominant reward category
     * @return true if notification was sent
     */
    public static boolean sendAggregatedHudNotification(UUID playerUuid,
                                                        double totalAmount,
                                                        String sourceList,
                                                        String category) {
        checkNotificationApi();

        if (notificationApiAvailable) {
            try {
                Object playerRef = PLAYER_REF_CACHE.get(playerUuid);
                if (playerRef == null) return false;

                Object packetHandler = getPacketHandlerMethod.invoke(playerRef);
                if (packetHandler == null) return false;

                // Title: "+12.50" in category color
                String amountStr = "+" + formatCoins(totalAmount);
                String titleColor = getCategoryHexColor(category);
                Object titleMsg = messageRawMethod.invoke(null, amountStr);
                titleMsg = messageColorMethod.invoke(titleMsg, titleColor);

                // Description: source list in light gray
                Object descMsg = messageRawMethod.invoke(null, sourceList);
                descMsg = messageColorMethod.invoke(descMsg, "#B0B0B0");

                // Icon
                String iconPath = getCategoryIconPath(category);

                notificationSendMethod.invoke(null,
                        packetHandler, titleMsg, descMsg,
                        iconPath, null, notificationStyleDefault);

                LOGGER.debug("Aggregated HUD notification sent to {}: {} ({})",
                        playerUuid, amountStr, sourceList);
                return true;

            } catch (Throwable e) {
                LOGGER.debug("Aggregated native notification failed: {}", e.getMessage());
            }
        }

        // Fallback: actionbar
        Player player = PLAYER_CACHE.get(playerUuid);
        if (player != null) {
            try {
                String plain = "+" + formatCoins(totalAmount) + " | " + sourceList;
                player.sendActionBar(plain);
                return true;
            } catch (Exception ignored) {}
        }

        // Fallback: chat via PlayerRef
        Object playerRef = PLAYER_REF_CACHE.get(playerUuid);
        if (playerRef != null) {
            String miniMessage = getCategoryMiniColor(category) + "+" + formatCoins(totalAmount)
                    + " <dark_gray>| <gray>" + sourceList;
            return trySendViaPlayerRef(playerRef, miniMessage);
        }

        return false;
    }

    /**
     * Legacy formatted chat delivery — kept for backward compatibility.
     * Now also tries HUD notification first.
     */
    public static boolean sendFormattedChat(UUID playerUuid, String miniMessageText) {
        // Try HUD notification first (extract amount from text if possible)
        // For aggregated messages, the caller should use sendAggregatedHudNotification() instead

        // Fallback to actionbar → chat
        try {
            Player player = PLAYER_CACHE.get(playerUuid);
            if (player != null) {
                String plain = MiniMessageParser.stripTags(miniMessageText);
                try {
                    player.sendActionBar(plain);
                    return true;
                } catch (Exception e) {
                    LOGGER.debug("sendActionBar failed for {}: {}", playerUuid, e.getMessage());
                }
            }

            Object playerRef = PLAYER_REF_CACHE.get(playerUuid);
            if (playerRef != null) {
                return trySendViaPlayerRef(playerRef, miniMessageText);
            }

            if (player != null) {
                String plain = MiniMessageParser.stripTags(miniMessageText);
                player.sendMessage(plain);
                return true;
            }

            return false;
        } catch (Throwable e) {
            LOGGER.warn("sendFormattedChat failed for {}: {}", playerUuid, e.getMessage());
            return false;
        }
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

    // ── Category Mappings ───────────────────────────────────────

    /** Returns hex color for notification title based on reward category. */
    private static String getCategoryHexColor(String category) {
        if (category == null) return "#4ADE80"; // green
        return switch (category.toLowerCase()) {
            case "mob"  -> "#FBBF24"; // amber/gold
            case "ore"  -> "#F59E0B"; // yellow/amber
            case "wood" -> "#D97706"; // dark gold
            case "crop" -> "#22C55E"; // green
            default     -> "#4ADE80"; // light green
        };
    }

    /** Returns MiniMessage color tag for category (fallback messages). */
    private static String getCategoryMiniColor(String category) {
        if (category == null) return "<green>";
        return switch (category.toLowerCase()) {
            case "mob"  -> "<yellow>";
            case "ore"  -> "<yellow>";
            case "wood" -> "<gold>";
            case "crop" -> "<dark_green>";
            default     -> "<green>";
        };
    }

    /**
     * Returns icon texture path for the notification popup.
     * Uses standard Hytale item icons from Common/Icons/ItemsGenerated/.
     * Each income category gets its own thematic icon.
     */
    private static String getCategoryIconPath(String category) {
        if (category == null) return "Icons/ItemsGenerated/Ingredient_Bar_Iron.png";
        return switch (category.toLowerCase()) {
            case "mob"  -> "Icons/ItemsGenerated/Weapon_Sword_Iron.png";
            case "ore"  -> "Icons/ItemsGenerated/Tool_Pickaxe_Iron.png";
            case "wood" -> "Icons/ItemsGenerated/Tool_Hatchet_Iron.png";
            case "crop" -> "Icons/ItemsGenerated/Plant_Fruit_Apple.png";
            default     -> "Icons/ItemsGenerated/Ingredient_Bar_Iron.png";
        };
    }

    // ── Internal: PlayerRef sendMessage ──────────────────────────

    /**
     * Отправляет сообщение через PlayerRef → getPlayer() → sendMessage(Message).
     * Полностью через reflection для совместимости.
     */
    private static boolean trySendViaPlayerRef(Object playerRef, String text) {
        try {
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

            // Step 1: PlayerRef.getPlayer() → Player
            Object player = null;
            try {
                Method getPlayer = playerRef.getClass().getMethod("getPlayer");
                player = getPlayer.invoke(playerRef);
            } catch (NoSuchMethodException ignored) {}

            if (player != null) {
                // Try sendMessage(Message) — colored text
                if (parsedMsg != null && msgClass != null) {
                    try {
                        Method sendMsg = player.getClass().getMethod("sendMessage", msgClass);
                        sendMsg.invoke(player, parsedMsg);
                        return true;
                    } catch (NoSuchMethodException ignored) {}
                }

                // Try sendActionBar(String)
                try {
                    Method sendActionBar = player.getClass().getMethod("sendActionBar", String.class);
                    sendActionBar.invoke(player, plainText);
                    return true;
                } catch (NoSuchMethodException ignored) {}

                // Try sendMessage(String)
                try {
                    Method sendMsg = player.getClass().getMethod("sendMessage", String.class);
                    sendMsg.invoke(player, plainText);
                    return true;
                } catch (NoSuchMethodException ignored) {}
            }

            // Direct on PlayerRef: sendMessage(Message)
            if (parsedMsg != null && msgClass != null) {
                try {
                    Method sendMsg = playerRef.getClass().getMethod("sendMessage", msgClass);
                    sendMsg.invoke(playerRef, parsedMsg);
                    return true;
                } catch (NoSuchMethodException ignored) {}

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

    // ── Formatting Helpers ──────────────────────────────────────

    /**
     * Формирует текст уведомления из lang-шаблона (для fallback).
     */
    private static String buildNotificationText(EcoTaleIncomePlugin plugin,
                                                UUID playerUuid,
                                                double amount,
                                                String source,
                                                String category) {
        LangManager lang = plugin.getLangManager();
        String langCode = lang.getPlayerLang(playerUuid);
        String formatted = formatSourceName(source);

        String key = "reward." + category;
        return lang.getForLang(langCode, key,
                "amount", formatCoins(amount),
                "source", formatted);
    }

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

    public static String formatPercent(double mult) {
        String color = mult >= 0.8 ? "<green>" : (mult >= 0.5 ? "<yellow>" : "<red>");
        return color + String.format("%.0f%%", mult * 100);
    }
}
