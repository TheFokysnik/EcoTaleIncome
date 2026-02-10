package com.crystalrealm.ecotaleincome.commands;

import com.crystalrealm.ecotaleincome.EcoTaleIncomePlugin;
import com.crystalrealm.ecotaleincome.config.IncomeConfig;
import com.crystalrealm.ecotaleincome.economy.EconomyBridge;
import com.crystalrealm.ecotaleincome.lang.LangManager;
import com.crystalrealm.ecotaleincome.protection.AntiFarmManager;
import com.crystalrealm.ecotaleincome.protection.CooldownTracker;
import com.crystalrealm.ecotaleincome.util.MessageUtil;
import com.crystalrealm.ecotaleincome.util.MiniMessageParser;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

import com.crystalrealm.ecotaleincome.util.PluginLogger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Command collection for EcoTaleIncome with full RU/EN localization.
 *
 * <h3>Commands</h3>
 * <ul>
 *   <li>{@code /income} — Shows current status</li>
 *   <li>{@code /income info} — Plugin info and modules</li>
 *   <li>{@code /income stats [player]} — Reward statistics</li>
 *   <li>{@code /income reload} — Reloads configuration (admin)</li>
 *   <li>{@code /income debug} — Toggles debug mode (admin)</li>
 *   <li>{@code /income lang <en|ru>} — Changes player language</li>
 *   <li>{@code /income help} — Help</li>
 * </ul>
 */
public class IncomeCommandCollection extends AbstractCommandCollection {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();

    private final EcoTaleIncomePlugin plugin;

    /** Converts MiniMessage text to a parsed Message (MiniMessage → JSON → Message.parse). */
    private static Message msg(String miniMessage) {
        return Message.parse(MiniMessageParser.toJson(miniMessage));
    }

    public IncomeCommandCollection(EcoTaleIncomePlugin plugin) {
        super("income", "EcoTaleIncome commands — manage income rewards");
        this.plugin = plugin;

        // Register sub-commands via real Hytale API
        addSubCommand(new StatusSubCommand());
        addSubCommand(new InfoSubCommand());
        addSubCommand(new StatsSubCommand());
        addSubCommand(new ReloadSubCommand());
        addSubCommand(new DebugSubCommand());
        addSubCommand(new LangSubCommand());
        addSubCommand(new LangEnSubCommand());
        addSubCommand(new LangRuSubCommand());
        addSubCommand(new HelpSubCommand());
    }

    // ═════════════════════════════════════════════════════════════
    //  SUB-COMMANDS
    // ═════════════════════════════════════════════════════════════

    // ── /income status (default — shows balance, multiplier, rewards) ──

    private class StatusSubCommand extends AbstractAsyncCommand {
        StatusSubCommand() { super("status", "Shows current income status"); }

        @Override
        public CompletableFuture<Void> executeAsync(CommandContext context) {
            if (!context.isPlayer()) return CompletableFuture.completedFuture(null);
            CommandSender sender = context.sender();

            if (!sender.hasPermission("ecotaleincome.command.income")) {
                context.sendMessage(msg(L(sender, "cmd.no_permission")));
                return CompletableFuture.completedFuture(null);
            }

            UUID uuid = sender.getUuid();
            IncomeConfig config = plugin.getConfigManager().getConfig();

            double multiplier = plugin.getMultiplierResolver().resolve(uuid);
            CooldownTracker cooldown = plugin.getCooldownTracker();
            int remaining = cooldown.getRemainingRewards(uuid);
            int total = cooldown.getTotalRewardsInWindow(uuid);

            EconomyBridge eco = plugin.getEconomyBridge();
            double balance = eco.getBalance(uuid);

            context.sendMessage(msg(L(sender, "cmd.income.header")));
            context.sendMessage(msg(L(sender, "cmd.income.balance",
                    "amount", MessageUtil.formatCoins(balance))));
            context.sendMessage(msg(L(sender, "cmd.income.multiplier",
                    "value", MessageUtil.formatMultiplier(multiplier))));
            context.sendMessage(msg(L(sender, "cmd.income.rewards_per_min",
                    "current", String.valueOf(total),
                    "max", String.valueOf(config.getProtection().getMaxRewardsPerMinute()))));
            context.sendMessage(msg(L(sender, "cmd.income.remaining",
                    "count", String.valueOf(remaining))));
            context.sendMessage(msg(L(sender, "cmd.income.footer")));

            return CompletableFuture.completedFuture(null);
        }
    }

    // ── /income info ────────────────────────────────────────────

    private class InfoSubCommand extends AbstractAsyncCommand {
        InfoSubCommand() { super("info", "Plugin info and modules"); }

        @Override
        public CompletableFuture<Void> executeAsync(CommandContext context) {
            if (!context.isPlayer()) return CompletableFuture.completedFuture(null);
            CommandSender sender = context.sender();

            if (!sender.hasPermission("ecotaleincome.command.info")) {
                context.sendMessage(msg(L(sender, "cmd.no_permission")));
                return CompletableFuture.completedFuture(null);
            }

            IncomeConfig config = plugin.getConfigManager().getConfig();
            boolean rpgAvailable = plugin.getRPGBridge() != null && plugin.getRPGBridge().isAvailable();

            context.sendMessage(msg(L(sender, "cmd.info.header")));
            context.sendMessage(msg(L(sender, "cmd.info.version", "version", plugin.getVersion())));
            context.sendMessage(msg(L(sender, "cmd.info.ecotale", "status",
                    plugin.getEconomyBridge().isAvailable()
                            ? L(sender, "status.connected")
                            : L(sender, "status.disconnected"))));
            context.sendMessage(msg(L(sender, "cmd.info.rpg", "status",
                    rpgAvailable
                            ? L(sender, "status.connected")
                            : L(sender, "status.not_installed"))));
            context.sendMessage(msg(""));
            context.sendMessage(msg(L(sender, "cmd.info.modules")));
            context.sendMessage(msg(L(sender, "cmd.info.mob_kills", "status", statusStr(sender, config.getMobKills().isEnabled()))));
            context.sendMessage(msg(L(sender, "cmd.info.mining", "status", statusStr(sender, config.getMining().isEnabled()))));
            context.sendMessage(msg(L(sender, "cmd.info.woodcutting", "status", statusStr(sender, config.getWoodcutting().isEnabled()))));
            context.sendMessage(msg(L(sender, "cmd.info.farming", "status", statusStr(sender, config.getFarming().isEnabled()))));
            context.sendMessage(msg(""));
            context.sendMessage(msg(L(sender, "cmd.info.protection")));
            context.sendMessage(msg(L(sender, "cmd.info.rate_limit",
                    "value", String.valueOf(config.getProtection().getMaxRewardsPerMinute()))));
            context.sendMessage(msg(L(sender, "cmd.info.antifarm", "status",
                    statusStr(sender, config.getProtection().getAntiFarm().isEnabled()))));
            context.sendMessage(msg(L(sender, "cmd.info.debug", "status",
                    statusStr(sender, config.getGeneral().isDebugMode()))));
            context.sendMessage(msg(L(sender, "cmd.info.footer")));

            return CompletableFuture.completedFuture(null);
        }
    }

    // ── /income stats [player] ──────────────────────────────────

    private class StatsSubCommand extends AbstractAsyncCommand {
        StatsSubCommand() {
            super("stats", "Reward statistics");
        }

        @Override
        public CompletableFuture<Void> executeAsync(CommandContext context) {
            if (!context.isPlayer()) return CompletableFuture.completedFuture(null);
            CommandSender sender = context.sender();

            // Parse optional player name from raw input
            String targetName = parseTrailingArg(context);

            if (targetName == null || targetName.isEmpty()) {
                // Self stats
                if (!sender.hasPermission("ecotaleincome.command.stats")) {
                    context.sendMessage(msg(L(sender, "cmd.no_permission")));
                    return CompletableFuture.completedFuture(null);
                }
                showStats(context, sender.getUuid(), sender.getDisplayName());
            } else {
                // Other player stats
                if (!sender.hasPermission("ecotaleincome.command.stats.others")) {
                    context.sendMessage(msg(L(sender, "cmd.no_permission")));
                    return CompletableFuture.completedFuture(null);
                }
                // Player lookup by name is not available in current Hytale API
                context.sendMessage(msg(L(sender, "cmd.player_not_found", "name", targetName)));
            }

            return CompletableFuture.completedFuture(null);
        }
    }

    // ── /income reload ──────────────────────────────────────────

    private class ReloadSubCommand extends AbstractAsyncCommand {
        ReloadSubCommand() { super("reload", "Reloads configuration"); }

        @Override
        public CompletableFuture<Void> executeAsync(CommandContext context) {
            if (!context.isPlayer()) return CompletableFuture.completedFuture(null);
            CommandSender sender = context.sender();

            if (!sender.hasPermission("ecotaleincome.admin.reload")) {
                context.sendMessage(msg(L(sender, "cmd.no_permission")));
                return CompletableFuture.completedFuture(null);
            }

            LOGGER.info("Config reload initiated by {}", sender.getDisplayName());

            boolean success = plugin.getConfigManager().reload();
            if (success) {
                String newLang = plugin.getConfigManager().getConfig().getGeneral().getLanguage();
                plugin.getLangManager().reload(newLang);

                context.sendMessage(msg(L(sender, "cmd.reload.success")));
                LOGGER.info("Configuration reloaded by {}", sender.getDisplayName());
            } else {
                context.sendMessage(msg(L(sender, "cmd.reload.fail")));
                LOGGER.error("Failed to reload configuration (by {})", sender.getDisplayName());
            }

            return CompletableFuture.completedFuture(null);
        }
    }

    // ── /income debug ───────────────────────────────────────────

    private class DebugSubCommand extends AbstractAsyncCommand {
        DebugSubCommand() { super("debug", "Toggles debug mode"); }

        @Override
        public CompletableFuture<Void> executeAsync(CommandContext context) {
            if (!context.isPlayer()) return CompletableFuture.completedFuture(null);
            CommandSender sender = context.sender();

            if (!sender.hasPermission("ecotaleincome.admin.debug")) {
                context.sendMessage(msg(L(sender, "cmd.no_permission")));
                return CompletableFuture.completedFuture(null);
            }

            IncomeConfig config = plugin.getConfigManager().getConfig();
            boolean newState = !config.getGeneral().isDebugMode();
            config.getGeneral().setDebugMode(newState);

            String stateStr = newState ? L(sender, "cmd.debug.on") : L(sender, "cmd.debug.off");
            context.sendMessage(msg(L(sender, "cmd.debug.toggled", "state", stateStr)));

            LOGGER.info("Debug mode {} by {}", newState ? "enabled" : "disabled", sender.getDisplayName());

            return CompletableFuture.completedFuture(null);
        }
    }

    // ── /income lang | /income langen | /income langru ──────────

    private class LangSubCommand extends AbstractAsyncCommand {
        LangSubCommand() { super("lang", "Show language usage"); }

        @Override
        public CompletableFuture<Void> executeAsync(CommandContext context) {
            if (!context.isPlayer()) return CompletableFuture.completedFuture(null);
            CommandSender sender = context.sender();
            context.sendMessage(msg(L(sender, "cmd.lang.usage")));
            return CompletableFuture.completedFuture(null);
        }
    }

    private class LangEnSubCommand extends AbstractAsyncCommand {
        LangEnSubCommand() { super("langen", "Switch to English"); }

        @Override
        public CompletableFuture<Void> executeAsync(CommandContext context) {
            if (!context.isPlayer()) return CompletableFuture.completedFuture(null);
            CommandSender sender = context.sender();
            if (plugin.getLangManager().setPlayerLang(sender.getUuid(), "en")) {
                context.sendMessage(msg(L(sender, "cmd.lang.changed")));
            } else {
                context.sendMessage(msg(L(sender, "cmd.lang.invalid")));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private class LangRuSubCommand extends AbstractAsyncCommand {
        LangRuSubCommand() { super("langru", "Switch to Russian"); }

        @Override
        public CompletableFuture<Void> executeAsync(CommandContext context) {
            if (!context.isPlayer()) return CompletableFuture.completedFuture(null);
            CommandSender sender = context.sender();
            if (plugin.getLangManager().setPlayerLang(sender.getUuid(), "ru")) {
                context.sendMessage(msg(L(sender, "cmd.lang.changed")));
            } else {
                context.sendMessage(msg(L(sender, "cmd.lang.invalid")));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    // ── /income help ────────────────────────────────────────────

    private class HelpSubCommand extends AbstractAsyncCommand {
        HelpSubCommand() { super("help", "Help"); }

        @Override
        public CompletableFuture<Void> executeAsync(CommandContext context) {
            if (!context.isPlayer()) return CompletableFuture.completedFuture(null);
            CommandSender sender = context.sender();

            context.sendMessage(msg(L(sender, "cmd.help.header")));
            context.sendMessage(msg(L(sender, "cmd.help.income")));
            context.sendMessage(msg(L(sender, "cmd.help.info")));
            context.sendMessage(msg(L(sender, "cmd.help.stats")));
            context.sendMessage(msg(L(sender, "cmd.help.stats_other")));
            context.sendMessage(msg(L(sender, "cmd.help.reload")));
            context.sendMessage(msg(L(sender, "cmd.help.debug")));
            context.sendMessage(msg(L(sender, "cmd.help.lang")));
            context.sendMessage(msg(L(sender, "cmd.help.help")));
            context.sendMessage(msg(L(sender, "cmd.help.footer")));

            return CompletableFuture.completedFuture(null);
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  SHARED HELPERS
    // ═════════════════════════════════════════════════════════════

    private LangManager lang() {
        return plugin.getLangManager();
    }

    /** Localized message for sender (by UUID) */
    private String L(CommandSender sender, String key, String... args) {
        return lang().getForPlayer(sender.getUuid(), key, args);
    }

    private String statusStr(CommandSender sender, boolean enabled) {
        return enabled ? L(sender, "status.enabled") : L(sender, "status.disabled");
    }

    /** Keywords that are command/subcommand names (not real arguments). */
    private static final java.util.Set<String> COMMAND_KEYWORDS = java.util.Set.of(
            "income", "status", "info", "stats", "reload", "debug", "lang", "langen", "langru", "help"
    );

    /**
     * Parses the trailing argument from the raw input string.
     * <p>Filters out all known command/subcommand keywords, then returns
     * the last remaining token (the actual user argument).</p>
     * <ul>
     *   <li>{@code "/income stats SomePlayer"} → {@code "SomePlayer"}</li>
     *   <li>{@code "/income lang ru"} → {@code "ru"}</li>
     *   <li>{@code "/income stats"} → {@code null}</li>
     *   <li>{@code "stats"} → {@code null}</li>
     * </ul>
     */
    private String parseTrailingArg(CommandContext context) {
        try {
            String input = context.getInputString();
            if (input == null || input.isBlank()) return null;

            String[] parts = input.trim().split("\\s+");
            // Collect only tokens that are NOT known command keywords
            java.util.List<String> args = new java.util.ArrayList<>();
            for (String part : parts) {
                if (!COMMAND_KEYWORDS.contains(part.toLowerCase())) {
                    args.add(part);
                }
            }
            return args.isEmpty() ? null : args.get(args.size() - 1);
        } catch (Exception e) {
            LOGGER.debug("Failed to parse trailing arg: {}", e.getMessage());
        }
        return null;
    }

    private void showStats(CommandContext ctx, UUID targetUuid, String targetName) {
        CommandSender sender = ctx.sender();
        CooldownTracker cooldown = plugin.getCooldownTracker();
        AntiFarmManager antiFarm = plugin.getAntiFarmManager();
        EconomyBridge eco = plugin.getEconomyBridge();

        double balance = eco.getBalance(targetUuid);
        double multiplier = plugin.getMultiplierResolver().resolve(targetUuid);
        int rewardsThisMinute = cooldown.getTotalRewardsInWindow(targetUuid);
        int remaining = cooldown.getRemainingRewards(targetUuid);

        ctx.sendMessage(msg(L(sender, "cmd.stats.header", "name", targetName)));
        ctx.sendMessage(msg(L(sender, "cmd.stats.balance",
                "amount", MessageUtil.formatCoins(balance))));
        ctx.sendMessage(msg(L(sender, "cmd.stats.multiplier",
                "value", MessageUtil.formatMultiplier(multiplier))));
        ctx.sendMessage(msg(L(sender, "cmd.stats.rewards_min",
                "count", String.valueOf(rewardsThisMinute))));
        ctx.sendMessage(msg(L(sender, "cmd.stats.remaining",
                "count", String.valueOf(remaining))));

        if (plugin.getConfigManager().getConfig().getProtection().getAntiFarm().isEnabled()) {
            ctx.sendMessage(msg(""));
            ctx.sendMessage(msg(L(sender, "cmd.stats.antifarm_header")));
            ctx.sendMessage(msg(L(sender, "cmd.stats.antifarm_mob",
                    "value", MessageUtil.formatPercent(
                            antiFarm.peekMultiplier(targetUuid, "mob", "zombie")))));
            ctx.sendMessage(msg(L(sender, "cmd.stats.antifarm_ore",
                    "value", MessageUtil.formatPercent(
                            antiFarm.peekMultiplier(targetUuid, "ore", "iron")))));
            ctx.sendMessage(msg(L(sender, "cmd.stats.antifarm_wood",
                    "value", MessageUtil.formatPercent(
                            antiFarm.peekMultiplier(targetUuid, "wood", "oak")))));
            ctx.sendMessage(msg(L(sender, "cmd.stats.antifarm_crop",
                    "value", MessageUtil.formatPercent(
                            antiFarm.peekMultiplier(targetUuid, "crop", "wheat")))));
        }

        ctx.sendMessage(msg(L(sender, "cmd.stats.footer")));
    }
}
