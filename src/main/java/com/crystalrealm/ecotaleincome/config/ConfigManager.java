package com.crystalrealm.ecotaleincome.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.crystalrealm.ecotaleincome.util.PluginLogger;

import javax.annotation.Nonnull;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Менеджер конфигурации — загрузка, сохранение и hot-reload
 * JSON-конфига из директории данных плагина.
 */
public class ConfigManager {

    private static final PluginLogger LOGGER = PluginLogger.forEnclosingClass();
    private static final String CONFIG_FILENAME = "EcoTaleIncome.json";
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path dataDirectory;
    private IncomeConfig config;

    /**
     * @param dataDirectory директория данных плагина (mods/CrystalRealm_EcoTaleIncome/)
     */
    public ConfigManager(@Nonnull Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    /**
     * Загружает конфиг из файла или создаёт дефолтный.
     */
    public void loadOrCreate() {
        Path configPath = getConfigPath();

        try {
            // Создать директорию если не существует
            Files.createDirectories(dataDirectory);

            if (Files.exists(configPath)) {
                loadFromFile(configPath);
            } else {
                createDefault(configPath);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load config: {}", e.getMessage());
            config = new IncomeConfig(); // fallback to defaults
        }
    }

    /**
     * Перезагружает конфиг из файла (hot-reload).
     *
     * @return true если успешно перезагружен
     */
    public boolean reload() {
        Path configPath = getConfigPath();
        if (!Files.exists(configPath)) {
            LOGGER.warn("Config file not found: {}", configPath);
            return false;
        }

        try {
            loadFromFile(configPath);
            LOGGER.info("Configuration reloaded successfully.");
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to reload config: {}", e.getMessage());
            return false;
        }
    }

    /**
     * @return текущая загруженная конфигурация
     */
    @Nonnull
    public IncomeConfig getConfig() {
        if (config == null) {
            config = new IncomeConfig();
        }
        return config;
    }

    /**
     * @return полный путь к файлу конфигурации
     */
    @Nonnull
    public Path getConfigPath() {
        return dataDirectory.resolve(CONFIG_FILENAME);
    }

    // ─── Private ──────────────────────────────────────────────────

    private void loadFromFile(Path path) throws IOException {
        try (Reader reader = new InputStreamReader(
                Files.newInputStream(path), StandardCharsets.UTF_8)) {
            config = GSON.fromJson(reader, IncomeConfig.class);
        }

        if (config == null) {
            LOGGER.warn("Config parsed as null, using defaults.");
            config = new IncomeConfig();
        }
    }

    private void createDefault(Path path) throws IOException {
        config = new IncomeConfig();

        // Попытка скопировать встроенный шаблон
        try (InputStream defaultStream = getClass().getClassLoader()
                .getResourceAsStream("default-config.json")) {
            if (defaultStream != null) {
                Files.copy(defaultStream, path);
                loadFromFile(path); // загружаем шаблон
                LOGGER.info("Default config created at {}", path);
                return;
            }
        }

        // Если шаблон не найден — сериализуем defaults из POJO
        try (Writer writer = new OutputStreamWriter(
                Files.newOutputStream(path), StandardCharsets.UTF_8)) {
            GSON.toJson(config, writer);
        }
        LOGGER.info("Default config generated at {}", path);
    }
}
