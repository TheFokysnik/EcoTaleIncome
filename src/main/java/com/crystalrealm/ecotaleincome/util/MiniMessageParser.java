package com.crystalrealm.ecotaleincome.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts MiniMessage-formatted strings to Hytale JSON rich text format.
 *
 * <p>Hytale's {@code Message.parse()} expects JSON with PascalCase keys and hex colors:
 * <ul>
 *   <li>{@code RawText} — text content</li>
 *   <li>{@code Children} — array of child components (like Minecraft's "extra")</li>
 *   <li>{@code Color} — hex color string, e.g. {@code "#55ff55"}</li>
 *   <li>{@code Bold}, {@code Italic}, {@code Underline}, {@code Monospace} — boolean styles</li>
 * </ul>
 *
 * <p>Example output:
 * {@code {"RawText":"","Children":[{"RawText":"Hello ","Color":"#55ff55"},{"RawText":"World","Color":"#55ff55","Bold":true}]}}
 */
public final class MiniMessageParser {

    /** Named color → hex mapping (Minecraft/MiniMessage names to hex values) */
    private static final Map<String, String> COLOR_HEX = new HashMap<>();
    static {
        COLOR_HEX.put("black",        "#000000");
        COLOR_HEX.put("dark_blue",    "#0000aa");
        COLOR_HEX.put("dark_green",   "#00aa00");
        COLOR_HEX.put("dark_aqua",    "#00aaaa");
        COLOR_HEX.put("dark_red",     "#aa0000");
        COLOR_HEX.put("dark_purple",  "#aa00aa");
        COLOR_HEX.put("gold",         "#ffaa00");
        COLOR_HEX.put("gray",         "#aaaaaa");
        COLOR_HEX.put("dark_gray",    "#555555");
        COLOR_HEX.put("blue",         "#5555ff");
        COLOR_HEX.put("green",        "#55ff55");
        COLOR_HEX.put("aqua",         "#55ffff");
        COLOR_HEX.put("red",          "#ff5555");
        COLOR_HEX.put("light_purple", "#ff55ff");
        COLOR_HEX.put("yellow",       "#ffff55");
        COLOR_HEX.put("white",        "#ffffff");
    }

    private static final Set<String> STYLES = Set.of("bold", "italic", "underlined", "strikethrough", "obfuscated");

    /** Matches: <green>, </bold>, <#ff5555>, </#ff5555> */
    private static final Pattern TAG_PATTERN = Pattern.compile("<(/?)([a-z_]+|#[0-9a-fA-F]{6})>");

    private MiniMessageParser() {}

    /**
     * Converts a MiniMessage string to Hytale JSON rich text for {@code Message.parse()}.
     *
     * @param input MiniMessage-formatted string (e.g. {@code "<green>Hello <bold>World</bold>"})
     * @return JSON string suitable for {@code Message.parse()} (PascalCase keys, hex colors)
     */
    public static String toJson(String input) {
        if (input == null || input.isEmpty()) {
            return "{\"RawText\":\"\"}";
        }

        // If no tags present, return simple JSON text
        if (!input.contains("<")) {
            return "{\"RawText\":\"" + escapeJson(input) + "\"}";
        }

        List<String> components = new ArrayList<>();
        String currentColor = null;  // stores hex color like "#55ff55"
        boolean bold = false;
        boolean italic = false;
        boolean underline = false;

        Matcher matcher = TAG_PATTERN.matcher(input);
        int lastEnd = 0;

        while (matcher.find()) {
            // Capture text between last tag end and this tag start
            if (matcher.start() > lastEnd) {
                String text = input.substring(lastEnd, matcher.start());
                if (!text.isEmpty()) {
                    components.add(buildComponent(text, currentColor, bold, italic, underline));
                }
            }

            boolean isClosing = "/".equals(matcher.group(1));
            String tagName = matcher.group(2);

            if (isClosing) {
                // Closing tags: </bold>, </italic>, </underlined>, </#hexcolor>
                if ("bold".equals(tagName)) bold = false;
                else if ("italic".equals(tagName)) italic = false;
                else if ("underlined".equals(tagName)) underline = false;
                else if (tagName.startsWith("#")) currentColor = null;
            } else {
                // Opening tags: <bold>, <italic>, <green>, <#ff5555>
                if ("bold".equals(tagName)) bold = true;
                else if ("italic".equals(tagName)) italic = true;
                else if ("underlined".equals(tagName)) underline = true;
                else if (tagName.startsWith("#")) currentColor = tagName.toLowerCase();
                else if (COLOR_HEX.containsKey(tagName)) currentColor = COLOR_HEX.get(tagName);
            }

            lastEnd = matcher.end();
        }

        // Remaining text after last tag
        if (lastEnd < input.length()) {
            String text = input.substring(lastEnd);
            if (!text.isEmpty()) {
                components.add(buildComponent(text, currentColor, bold, italic, underline));
            }
        }

        if (components.isEmpty()) {
            return "{\"RawText\":\"\"}";
        }

        if (components.size() == 1) {
            return components.get(0);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"RawText\":\"\",\"Children\":[");
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(components.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * Strips all MiniMessage tags from text, returning plain text.
     * Used as fallback when JSON parsing fails.
     */
    public static String stripTags(String input) {
        if (input == null) return "";
        return TAG_PATTERN.matcher(input).replaceAll("");
    }

    private static String buildComponent(String text, String hexColor, boolean bold, boolean italic, boolean underline) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"RawText\":\"").append(escapeJson(text)).append("\"");
        if (hexColor != null) {
            sb.append(",\"Color\":\"").append(hexColor).append("\"");
        }
        if (bold) {
            sb.append(",\"Bold\":true");
        }
        if (italic) {
            sb.append(",\"Italic\":true");
        }
        if (underline) {
            sb.append(",\"Underline\":true");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:   sb.append(c); break;
            }
        }
        return sb.toString();
    }
}
