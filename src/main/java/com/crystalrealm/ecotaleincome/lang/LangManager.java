package com.crystalrealm.ecotaleincome.lang;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import com.crystalrealm.ecotaleincome.util.PluginLogger;

import javax.annotation.Nonnull;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Менеджер локализации с поддержкой RU/EN.
 *
 * <p>Загружает сообщения из JSON-файлов ресурсов ({@code lang/en.json},
 * {@code lang/ru.json}) и поддерживает пользовательские переопределения
 * в папке данных плагина.</p>
 *
 * <h3>Использование</h3>
 * <pre>{@code
 *   LangManager lang = new LangManager(dataDir);
 *   lang.load("ru"); // Устанавливает русский как язык по умолчанию
 *
 *   String msg = lang.get("cmd.reload.success");
 *   // → "§aКонфигурация успешно перезагружена!"
 *
 *   String msg2 = lang.get("reward.mob", "amount", "5.50", "source", "Zombie");
 *   // → "⚔ §a+5.50§7 монет §c(Zombie)"
 * }</pre>
 *
 * <h3>Подстановка переменных</h3>
 * <p>Сообщения поддерживают плейсхолдеры вида {@code {key}}, которые
 * заменяются при вызове {@link #get(String, String...)}.</p>
 *
 * <h3>Per-player языки</h3>
 * <p>Каждому игроку можно назначить индивидуальный язык через
 * {@link #setPlayerLang(UUID, String)}, иначе используется язык сервера.</p>
 */
public class LangManager {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    /** Поддерживаемые языки */
    public static final List<String> SUPPORTED_LANGS = List.of("en", "ru");
    public static final String DEFAULT_LANG = "ru";

    /** Загруженные переводы: langCode → (key → message) */
    private final Map<String, Map<String, String>> translations = new HashMap<>();

    /** Per-player язык: playerUUID → langCode */
    private final Map<UUID, String> playerLangs = new ConcurrentHashMap<>();

    /** Язык сервера по умолчанию */
    private String serverLang;

    /** Папка данных плагина для пользовательских переопределений */
    private final Path dataDirectory;

    public LangManager(@Nonnull Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        this.serverLang = DEFAULT_LANG;
    }

    // ═════════════════════════════════════════════════════════════
    //  LOADING
    // ═════════════════════════════════════════════════════════════

    /**
     * Загружает все языковые файлы и устанавливает язык сервера.
     *
     * @param defaultLang код языка по умолчанию ("en" или "ru")
     */
    public void load(@Nonnull String defaultLang) {
        this.serverLang = SUPPORTED_LANGS.contains(defaultLang) ? defaultLang : DEFAULT_LANG;

        for (String lang : SUPPORTED_LANGS) {
            Map<String, String> messages = loadLangFile(lang);
            if (messages != null && !messages.isEmpty()) {
                translations.put(lang, messages);
                LOGGER.info("Loaded {} messages for locale '{}'.", messages.size(), lang);
            }
        }

        // Загрузить пользовательские переопределения из папки данных
        loadCustomOverrides();

        LOGGER.info("LangManager initialized. Server language: '{}'. Loaded locales: {}",
                serverLang, translations.keySet());
    }

    /**
     * Перезагружает все переводы.
     */
    public void reload(@Nonnull String defaultLang) {
        translations.clear();
        load(defaultLang);
    }

    /**
     * Загружает JSON из ресурсов JAR.
     */
    private Map<String, String> loadLangFile(String langCode) {
        String resourcePath = "lang/" + langCode + ".json";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOGGER.warn("Language resource not found: {}", resourcePath);
                return Collections.emptyMap();
            }
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                return GSON.fromJson(reader, MAP_TYPE);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load language file: " + resourcePath, e);
            return Collections.emptyMap();
        }
    }

    /**
     * Загружает пользовательские переопределения из {@code lang/} внутри dataDirectory.
     * Это позволяет серверу модифицировать сообщения без пересборки мода.
     */
    private void loadCustomOverrides() {
        Path langDir = dataDirectory.resolve("lang");
        if (!Files.isDirectory(langDir)) return;

        for (String lang : SUPPORTED_LANGS) {
            Path customFile = langDir.resolve(lang + ".json");
            if (Files.exists(customFile)) {
                try (Reader reader = Files.newBufferedReader(customFile, StandardCharsets.UTF_8)) {
                    Map<String, String> overrides = GSON.fromJson(reader, MAP_TYPE);
                    if (overrides != null) {
                        translations.computeIfAbsent(lang, k -> new HashMap<>()).putAll(overrides);
                        LOGGER.info("Applied {} custom overrides for locale '{}'.",
                                overrides.size(), lang);
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to load custom language file: " + customFile, e);
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  MESSAGE RETRIEVAL
    // ═════════════════════════════════════════════════════════════

    /**
     * Получает сообщение для языка сервера.
     *
     * @param key  ключ сообщения (напр. "cmd.reload.success")
     * @param args пары key,value для подстановки (напр. "amount", "5.50")
     * @return локализованное сообщение, или ключ если не найдено
     */
    @Nonnull
    public String get(@Nonnull String key, @Nonnull String... args) {
        return getForLang(serverLang, key, args);
    }

    /**
     * Получает сообщение для конкретного языка.
     */
    @Nonnull
    public String getForLang(@Nonnull String langCode, @Nonnull String key, @Nonnull String... args) {
        String message = getRaw(langCode, key);

        // Подстановка переменных: {amount}, {source}, etc.
        if (args.length >= 2) {
            for (int i = 0; i < args.length - 1; i += 2) {
                message = message.replace("{" + args[i] + "}", args[i + 1]);
            }
        }

        // Подстановка иконок: {icon} → значение из icon.<category>
        if (message.contains("{icon}")) {
            String category = extractCategory(key);
            String icon = getRaw(langCode, "icon." + category);
            message = message.replace("{icon}", icon);
        }

        return message;
    }

    /**
     * Получает сообщение для конкретного игрока (учитывает его язык).
     */
    @Nonnull
    public String getForPlayer(@Nonnull UUID playerUuid, @Nonnull String key, @Nonnull String... args) {
        String lang = playerLangs.getOrDefault(playerUuid, serverLang);
        return getForLang(lang, key, args);
    }

    /**
     * Возвращает префикс сообщений для указанного языка.
     */
    @Nonnull
    public String getPrefix(@Nonnull String langCode) {
        return getRaw(langCode, "prefix");
    }

    /**
     * Возвращает префикс для игрока.
     */
    @Nonnull
    public String getPrefixForPlayer(@Nonnull UUID playerUuid) {
        String lang = playerLangs.getOrDefault(playerUuid, serverLang);
        return getPrefix(lang);
    }

    /**
     * Получает сырое сообщение без подстановок. Если не найдено в запрошенном
     * языке — ищет в английском, затем возвращает ключ.
     */
    @Nonnull
    private String getRaw(@Nonnull String langCode, @Nonnull String key) {
        // 1. Поиск в запрошенном языке
        Map<String, String> messages = translations.get(langCode);
        if (messages != null) {
            String value = messages.get(key);
            if (value != null) return value;
        }

        // 2. Fallback на английский
        if (!"en".equals(langCode)) {
            Map<String, String> enMessages = translations.get("en");
            if (enMessages != null) {
                String value = enMessages.get(key);
                if (value != null) return value;
            }
        }

        // 3. Ключ как fallback
        return key;
    }

    // ═════════════════════════════════════════════════════════════
    //  PLAYER LANGUAGE
    // ═════════════════════════════════════════════════════════════

    /**
     * Устанавливает язык для конкретного игрока.
     *
     * @return true если язык валиден и установлен
     */
    public boolean setPlayerLang(@Nonnull UUID playerUuid, @Nonnull String langCode) {
        if (!SUPPORTED_LANGS.contains(langCode.toLowerCase())) return false;
        playerLangs.put(playerUuid, langCode.toLowerCase());
        return true;
    }

    /**
     * Возвращает текущий язык игрока.
     */
    @Nonnull
    public String getPlayerLang(@Nonnull UUID playerUuid) {
        return playerLangs.getOrDefault(playerUuid, serverLang);
    }

    /**
     * Удаляет персональный язык (вернётся к серверному).
     */
    public void resetPlayerLang(@Nonnull UUID playerUuid) {
        playerLangs.remove(playerUuid);
    }

    // ═════════════════════════════════════════════════════════════
    //  UTIL
    // ═════════════════════════════════════════════════════════════

    /**
     * Извлекает категорию из ключа (напр. "reward.mob" → "mob").
     */
    private static String extractCategory(String key) {
        int lastDot = key.lastIndexOf('.');
        return lastDot >= 0 ? key.substring(lastDot + 1) : "default";
    }

    @Nonnull
    public String getServerLang() {
        return serverLang;
    }

    public void setServerLang(@Nonnull String lang) {
        if (SUPPORTED_LANGS.contains(lang)) {
            this.serverLang = lang;
        }
    }

    /**
     * Очистка per-player данных при shutdown.
     */
    public void clearPlayerData() {
        playerLangs.clear();
    }
}
