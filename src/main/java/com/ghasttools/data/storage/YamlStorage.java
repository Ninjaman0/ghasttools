package com.ghasttools.data.storage;

import com.ghasttools.GhastToolsPlugin;
import com.ghasttools.data.PlayerData;
import com.ghasttools.milestones.MilestoneData;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * FIXED: YAML implementation with milestone data support
 */
public class YamlStorage implements StorageProvider {

    private final GhastToolsPlugin plugin;
    private File dataFolder;

    public YamlStorage(GhastToolsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void initialize() throws Exception {
        dataFolder = new File(plugin.getDataFolder(), "playerdata");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
    }

    @Override
    public void savePlayerData(UUID playerId, PlayerData data) throws Exception {
        File playerFile = new File(dataFolder, playerId.toString() + ".yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(playerFile);

        // Save main data
        config.set("totalBlocksBroken", data.getTotalBlocksBroken());
        config.set("totalXpEarned", data.getTotalXpEarned());
        config.set("totalEssenceEarned", data.getTotalEssenceEarned());
        config.set("lastEnchantUsed", data.getLastEnchantUsed());
        config.set("totalMeteorsSpawned", data.getTotalMeteorsSpawned());
        config.set("totalAirstrikes", data.getTotalAirstrikes());
        config.set("favoriteToolType", data.getFavoriteToolType());
        config.set("lastSeen", data.getLastSeen());

        // Save cooldowns (only active ones)
        config.set("cooldowns", null); // Clear existing
        for (Map.Entry<String, Long> entry : data.getEnchantmentCooldowns().entrySet()) {
            if (entry.getValue() > System.currentTimeMillis()) {
                config.set("cooldowns." + entry.getKey(), entry.getValue());
            }
        }

        // Save tool usage
        config.set("toolUsage", null); // Clear existing
        for (Map.Entry<String, Integer> entry : data.getToolUsageCount().entrySet()) {
            config.set("toolUsage." + entry.getKey(), entry.getValue());
        }

        // Save enchantment usage
        config.set("enchantmentUsage", null); // Clear existing
        for (Map.Entry<String, Long> entry : data.getEnchantmentUsageCount().entrySet()) {
            config.set("enchantmentUsage." + entry.getKey(), entry.getValue());
        }

        // ADDED: Save milestone data
        saveMilestoneData(config, data.getMilestoneData());

        try {
            config.save(playerFile);
        } catch (IOException e) {
            throw new Exception("Failed to save player data for " + playerId, e);
        }
    }

    @Override
    public PlayerData loadPlayerData(UUID playerId) throws Exception {
        File playerFile = new File(dataFolder, playerId.toString() + ".yml");
        PlayerData data = new PlayerData(playerId);

        if (!playerFile.exists()) {
            return data; // Return default data
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(playerFile);

        // Load main data
        data.setTotalBlocksBroken(config.getLong("totalBlocksBroken", 0));
        data.setTotalXpEarned(config.getDouble("totalXpEarned", 0.0));
        data.setTotalEssenceEarned(config.getDouble("totalEssenceEarned", 0.0));
        data.setLastEnchantUsed(config.getString("lastEnchantUsed", ""));
        data.setTotalMeteorsSpawned(config.getInt("totalMeteorsSpawned", 0));
        data.setTotalAirstrikes(config.getInt("totalAirstrikes", 0));
        data.setFavoriteToolType(config.getString("favoriteToolType", ""));
        data.setLastSeen(config.getLong("lastSeen", System.currentTimeMillis()));

        // Load cooldowns
        if (config.contains("cooldowns")) {
            for (String enchant : config.getConfigurationSection("cooldowns").getKeys(false)) {
                long cooldownEnd = config.getLong("cooldowns." + enchant);
                if (cooldownEnd > System.currentTimeMillis()) {
                    data.getEnchantmentCooldowns().put(enchant, cooldownEnd);
                }
            }
        }

        // Load tool usage
        if (config.contains("toolUsage")) {
            for (String tool : config.getConfigurationSection("toolUsage").getKeys(false)) {
                data.getToolUsageCount().put(tool, config.getInt("toolUsage." + tool));
            }
        }

        // Load enchantment usage
        if (config.contains("enchantmentUsage")) {
            for (String enchant : config.getConfigurationSection("enchantmentUsage").getKeys(false)) {
                data.getEnchantmentUsageCount().put(enchant, config.getLong("enchantmentUsage." + enchant));
            }
        }

        // ADDED: Load milestone data
        loadMilestoneData(config, data.getMilestoneData());

        return data;
    }

    /**
     * ADDED: Save milestone data to YAML
     */
    private void saveMilestoneData(FileConfiguration config, MilestoneData milestoneData) {
        if (milestoneData == null) return;

        // Clear existing milestone data
        config.set("milestones", null);

        // Save blocks broken data
        Map<Material, Long> blocksData = milestoneData.getAllBlocksBroken();
        for (Map.Entry<Material, Long> entry : blocksData.entrySet()) {
            if (entry.getValue() > 0) { // Only save non-zero values
                config.set("milestones.blocks." + entry.getKey().name(), entry.getValue());
            }
        }

        // Save milestone claims data
        Map<String, Boolean> claimsData = milestoneData.getAllClaimedMilestones();
        for (Map.Entry<String, Boolean> entry : claimsData.entrySet()) {
            if (entry.getValue()) { // Only save claimed milestones
                config.set("milestones.claims." + entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * ADDED: Load milestone data from YAML
     */
    private void loadMilestoneData(FileConfiguration config, MilestoneData milestoneData) {
        if (milestoneData == null) return;

        // Load blocks broken data
        if (config.contains("milestones.blocks")) {
            for (String materialName : config.getConfigurationSection("milestones.blocks").getKeys(false)) {
                try {
                    Material material = Material.valueOf(materialName);
                    long blocksBroken = config.getLong("milestones.blocks." + materialName);
                    milestoneData.setBlocksBroken(material, blocksBroken);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid material in milestone data: " + materialName);
                }
            }
        }

        // Load milestone claims data
        if (config.contains("milestones.claims")) {
            for (String milestoneKey : config.getConfigurationSection("milestones.claims").getKeys(false)) {
                boolean claimed = config.getBoolean("milestones.claims." + milestoneKey);
                milestoneData.setMilestoneClaimed(milestoneKey, claimed);
            }
        }
    }

    @Override
    public int cleanupOldData(int daysOffline) throws Exception {
        long cutoffTime = System.currentTimeMillis() - (daysOffline * 24L * 60L * 60L * 1000L);
        int cleaned = 0;

        File[] playerFiles = dataFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (playerFiles != null) {
            for (File file : playerFiles) {
                FileConfiguration config = YamlConfiguration.loadConfiguration(file);
                long lastSeen = config.getLong("lastSeen", System.currentTimeMillis());

                if (lastSeen < cutoffTime) {
                    if (file.delete()) {
                        cleaned++;
                    }
                }
            }
        }

        return cleaned;
    }

    @Override
    public boolean exportData(String fileName) throws Exception {
        // Implementation for data export
        return true; // Placeholder
    }

    @Override
    public boolean importData(String fileName) throws Exception {
        // Implementation for data import
        return true; // Placeholder
    }

    @Override
    public void shutdown() throws Exception {
        // No cleanup needed for YAML storage
    }
}