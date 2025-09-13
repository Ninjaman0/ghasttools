package com.ghasttools.config;

import com.ghasttools.GhastToolsPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * ENHANCED: Manages all configuration files for the plugin including milestone.yml
 */
public class ConfigManager {

    private final GhastToolsPlugin plugin;
    private final Map<String, FileConfiguration> configs = new HashMap<>();
    private final Map<String, File> configFiles = new HashMap<>();

    public ConfigManager(GhastToolsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Load all configuration files
     */
    public void loadConfigs() {
        plugin.getLogger().info("Loading configuration files...");

        // Main config
        loadConfig("config.yml");

        // Tool configs
        loadConfig("tools/pickaxe.yml");
        loadConfig("tools/axe.yml");
        loadConfig("tools/hoe.yml");

        // GUI configs
        loadConfig("gui.yml");
        loadConfig("random_win.yml");
        loadConfig("shop.yml");

        // Regeneration config
        loadConfig("regeneration.yml");

        // Milestone config - ADDED
        loadConfig("milestone.yml");

        // Rewards config
        loadConfig("rewards.yml");

        // Messages config
        loadConfig("messages/messages_en.yml");

        plugin.getLogger().info("Configuration files loaded successfully!");
    }

    /**
     * Load a specific configuration file
     */
    private void loadConfig(String fileName) {
        File configFile = new File(plugin.getDataFolder(), fileName);

        // Create directories if they don't exist
        configFile.getParentFile().mkdirs();

        // Copy default config if it doesn't exist
        if (!configFile.exists()) {
            try (InputStream inputStream = plugin.getResource(fileName)) {
                if (inputStream != null) {
                    Files.copy(inputStream, configFile.toPath());
                    plugin.getLogger().info("Created default config: " + fileName);
                } else {
                    plugin.getLogger().warning("Default config not found in resources: " + fileName);
                    // Create empty config
                    try {
                        configFile.createNewFile();
                    } catch (IOException e) {
                        plugin.getLogger().log(Level.SEVERE, "Failed to create config file: " + fileName, e);
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to copy default config: " + fileName, e);
            }
        }

        // Load the configuration
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        configs.put(fileName, config);
        configFiles.put(fileName, configFile);
    }

    /**
     * Get a configuration by file name
     */
    public FileConfiguration getConfig(String fileName) {
        return configs.get(fileName);
    }

    /**
     * Save a configuration file
     */
    public void saveConfig(String fileName) {
        FileConfiguration config = configs.get(fileName);
        File configFile = configFiles.get(fileName);

        if (config != null && configFile != null) {
            try {
                config.save(configFile);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save config: " + fileName, e);
            }
        }
    }

    /**
     * Reload a configuration file
     */
    public void reloadConfig(String fileName) {
        File configFile = configFiles.get(fileName);
        if (configFile != null) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
            configs.put(fileName, config);
        }
    }

    /**
     * Reload all configuration files
     */
    public void reloadAllConfigs() {
        for (String fileName : configs.keySet()) {
            reloadConfig(fileName);
        }
        plugin.getLogger().info("All configuration files reloaded!");
    }

    // Convenience methods for commonly used configs
    public FileConfiguration getMainConfig() {
        return getConfig("config.yml");
    }

    public FileConfiguration getPickaxeConfig() {
        return getConfig("tools/pickaxe.yml");
    }

    public FileConfiguration getAxeConfig() {
        return getConfig("tools/axe.yml");
    }

    public FileConfiguration getHoeConfig() {
        return getConfig("tools/hoe.yml");
    }

    public FileConfiguration getGuiConfig() {
        return getConfig("gui.yml");
    }

    public FileConfiguration getRandomWinConfig() {
        return getConfig("random_win.yml");
    }

    public FileConfiguration getShopConfig() {
        return getConfig("shop.yml");
    }

    public FileConfiguration getRegenerationConfig() {
        return getConfig("regeneration.yml");
    }

    // ADDED: Milestone configuration getter
    public FileConfiguration getMilestoneConfig() {
        return getConfig("milestone.yml");
    }

    public FileConfiguration getRewardsConfig() {
        return getConfig("rewards.yml");
    }

    public FileConfiguration getMessagesConfig() {
        return getConfig("messages/messages_en.yml");
    }
}