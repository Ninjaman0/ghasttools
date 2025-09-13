    package com.ghasttools.data;

    import com.ghasttools.GhastToolsPlugin;
    import com.ghasttools.data.storage.SQLiteStorage;
    import com.ghasttools.data.storage.StorageProvider;
    import com.ghasttools.data.storage.YamlStorage;
    import org.bukkit.configuration.file.FileConfiguration;

    import java.util.UUID;
    import java.util.concurrent.CompletableFuture;
    import java.util.logging.Level;

    /**
     * Manages data storage for the plugin
     */
    public class DataManager {

        private final GhastToolsPlugin plugin;
        private StorageProvider storageProvider;

        public DataManager(GhastToolsPlugin plugin) {
            this.plugin = plugin;
        }

        /**
         * Initialize the data storage system
         */
        public void initialize() {
            FileConfiguration config = plugin.getConfigManager().getMainConfig();
            String storageType = config.getString("storage.type", "sqlite").toLowerCase();

            plugin.getLogger().info("Initializing " + storageType.toUpperCase() + " storage...");

            try {
                switch (storageType) {
                    case "sqlite":
                        storageProvider = new SQLiteStorage(plugin);
                        break;
                    case "yaml":
                        storageProvider = new YamlStorage(plugin);
                        break;
                    default:
                        plugin.getLogger().warning("Unknown storage type: " + storageType + ". Using SQLite as default.");
                        storageProvider = new SQLiteStorage(plugin);
                        break;
                }

                storageProvider.initialize();
                plugin.getLogger().info("Data storage initialized successfully!");

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to initialize data storage!", e);
                throw new RuntimeException("Data storage initialization failed", e);
            }
        }

        /**
         * Save player data asynchronously
         */
        public CompletableFuture<Void> savePlayerData(UUID playerId, PlayerData data) {
            return CompletableFuture.runAsync(() -> {
                try {
                    storageProvider.savePlayerData(playerId, data);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to save player data for " + playerId, e);
                }
            });
        }

        /**
         * Load player data asynchronously
         */
        public CompletableFuture<PlayerData> loadPlayerData(UUID playerId) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return storageProvider.loadPlayerData(playerId);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to load player data for " + playerId, e);
                    return new PlayerData(playerId);
                }
            });
        }

        /**
         * Clean up old player data
         */
        public CompletableFuture<Integer> cleanupOldData(int daysOffline) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return storageProvider.cleanupOldData(daysOffline);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to cleanup old data", e);
                    return 0;
                }
            });
        }

        /**
         * Export all data
         */
        public CompletableFuture<Boolean> exportData(String fileName) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return storageProvider.exportData(fileName);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to export data", e);
                    return false;
                }
            });
        }

        /**
         * Import data from file
         */
        public CompletableFuture<Boolean> importData(String fileName) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return storageProvider.importData(fileName);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to import data", e);
                    return false;
                }
            });
        }

        /**
         * Shutdown the storage system
         */
        public void shutdown() {
            if (storageProvider != null) {
                try {
                    storageProvider.shutdown();
                    plugin.getLogger().info("Data storage shutdown complete.");
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Error during storage shutdown", e);
                }
            }
        }

        public StorageProvider getStorageProvider() {
            return storageProvider;
        }
    }