package com.crystalrealm.ecotaleincome.util;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lightweight SLF4J-style logger wrapper over java.util.logging.
 * Supports {} placeholders and {:.2f}-style format specs.
 */
public final class PluginLogger {

    private final Logger logger;

    private PluginLogger(String name) {
        this.logger = Logger.getLogger(name);
    }

    public static PluginLogger forEnclosingClass() {
        StackTraceElement caller = new Throwable().getStackTrace()[1];
        return new PluginLogger(caller.getClassName());
    }

    // ── Public API ──────────────────────────────────────────────

    public void info(String msg, Object... args) {
        if (logger.isLoggable(Level.INFO)) {
            logger.info(format(msg, args));
        }
    }

    public void warn(String msg, Object... args) {
        if (logger.isLoggable(Level.WARNING)) {
            FormattedMessage fm = formatWithThrowable(msg, args);
            if (fm.throwable != null) {
                logger.log(Level.WARNING, fm.message, fm.throwable);
            } else {
                logger.warning(fm.message);
            }
        }
    }

    public void error(String msg, Object... args) {
        if (logger.isLoggable(Level.SEVERE)) {
            FormattedMessage fm = formatWithThrowable(msg, args);
            if (fm.throwable != null) {
                logger.log(Level.SEVERE, fm.message, fm.throwable);
            } else {
                logger.severe(fm.message);
            }
        }
    }

    public void debug(String msg, Object... args) {
        if (logger.isLoggable(Level.FINE)) {
            logger.fine(format(msg, args));
        }
    }

    // ── Formatting helpers ──────────────────────────────────────

    private String format(String pattern, Object... args) {
        if (args == null || args.length == 0) return pattern;
        StringBuilder sb = new StringBuilder();
        int argIdx = 0;
        int i = 0;
        while (i < pattern.length()) {
            if (pattern.charAt(i) == '{') {
                int end = pattern.indexOf('}', i);
                if (end != -1) {
                    String spec = pattern.substring(i + 1, end);
                    if (argIdx < args.length) {
                        if (spec.isEmpty()) {
                            sb.append(args[argIdx++]);
                        } else if (spec.startsWith(":")) {
                            String fmt = "%" + spec.substring(1);
                            try {
                                sb.append(String.format(fmt, args[argIdx++]));
                            } catch (Exception e) {
                                sb.append(args[argIdx - 1]);
                            }
                        } else {
                            sb.append(args[argIdx++]);
                        }
                    } else {
                        sb.append(pattern, i, end + 1);
                    }
                    i = end + 1;
                } else {
                    sb.append(pattern.charAt(i));
                    i++;
                }
            } else {
                sb.append(pattern.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * If the last argument is a Throwable and there is no {} placeholder for it,
     * extract it so it can be logged with the stack-trace.
     */
    private FormattedMessage formatWithThrowable(String pattern, Object... args) {
        if (args != null && args.length > 0 && args[args.length - 1] instanceof Throwable) {
            long placeholders = countPlaceholders(pattern);
            if (placeholders < args.length) {
                Throwable t = (Throwable) args[args.length - 1];
                Object[] trimmed = new Object[args.length - 1];
                System.arraycopy(args, 0, trimmed, 0, args.length - 1);
                return new FormattedMessage(format(pattern, trimmed), t);
            }
        }
        return new FormattedMessage(format(pattern, args), null);
    }

    private long countPlaceholders(String pattern) {
        long count = 0;
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.charAt(i) == '{') {
                int end = pattern.indexOf('}', i);
                if (end != -1) {
                    count++;
                    i = end;
                }
            }
        }
        return count;
    }

    private static final class FormattedMessage {
        final String message;
        final Throwable throwable;

        FormattedMessage(String message, Throwable throwable) {
            this.message = message;
            this.throwable = throwable;
        }
    }
}
