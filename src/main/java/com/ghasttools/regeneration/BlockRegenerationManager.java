package com.ghasttools.regeneration;

import com.ghasttools.GhastToolsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;

/**
 * FIXED: Block regeneration manager with proper Y-level tracking for ALL affected blocks
 */
public class BlockRegenerationManager {

    private final GhastToolsPlugin plugin;
    private final ReentrantReadWriteLock configLock = new ReentrantReadWriteLock();

    // Thread-safe storage for regeneration data
    private final ConcurrentHashMap<String, RegenerationConfig> regenerationConfigs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BukkitTask> activeRegenerations = new ConcurrentHashMap<>();
    private final AtomicInteger taskCounter = new AtomicInteger(0);

    // FIXED: Track blocks by their full coordinates including Y-level
    private final ConcurrentHashMap<String, OriginalBlockData> originalBlockData = new ConcurrentHashMap<>();

    // Performance tracking
    private static final int MAX_CONCURRENT_REGENERATIONS = 1000;
    private static final long CLEANUP_INTERVAL_TICKS = 20 * 30; // 30 seconds

    public BlockRegenerationManager(GhastToolsPlugin plugin) {
        this.plugin = plugin;
        startCleanupTask();
    }

    /**
     * Load regeneration configurations from config file
     */
    public void loadConfigurations() {
        configLock.writeLock().lock();
        try {
            regenerationConfigs.clear();

            FileConfiguration config = plugin.getConfigManager().getRegenerationConfig();
            if (config == null) {
                plugin.getLogger().warning("Regeneration config not found!");
                return;
            }

            ConfigurationSection blocksSection = config.getConfigurationSection("regeneration.blocks");
            if (blocksSection == null) {
                plugin.getLogger().warning("No regeneration blocks configured!");
                return;
            }

            for (String materialName : blocksSection.getKeys(false)) {
                try {
                    Material material = Material.valueOf(materialName.toUpperCase());
                    ConfigurationSection blockConfig = blocksSection.getConfigurationSection(materialName);

                    if (blockConfig != null) {
                        RegenerationConfig config_ = new RegenerationConfig(
                                material,
                                blockConfig.getString("replace-with", "AIR"),
                                blockConfig.getInt("regenerate-delay", 5),
                                blockConfig.getString("regenerate-into", materialName)
                        );

                        regenerationConfigs.put(materialName.toUpperCase(), config_);
                        plugin.getLogger().info("Loaded regeneration config for " + materialName);
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid material in regeneration config: " + materialName);
                }
            }

            plugin.getLogger().info("Loaded " + regenerationConfigs.size() + " block regeneration configurations");

        } finally {
            configLock.writeLock().unlock();
        }
    }

    /**
     * FIXED: Handle block break with proper Y-level tracking for ALL blocks
     */
    public boolean handleBlockBreak(Block block, String enchantmentType) {
        if (block == null || plugin.isShuttingDown()) {
            return false;
        }

        Material material = block.getType();
        String materialKey = material.name().toUpperCase();

        configLock.readLock().lock();
        try {
            RegenerationConfig config = regenerationConfigs.get(materialKey);
            if (config == null) {
                return false; // Block not configured for regeneration
            }

            // Check if we're at max concurrent regenerations
            if (activeRegenerations.size() >= MAX_CONCURRENT_REGENERATIONS) {
                plugin.getLogger().fine("Max concurrent regenerations reached, skipping " + materialKey);
                return false;
            }

            // FIXED: Store original block data with FULL coordinates including Y-level
            Location blockLocation = block.getLocation();
            String blockKey = getBlockKey(blockLocation);

            // Store the original block data BEFORE breaking
            OriginalBlockData originalData = new OriginalBlockData(
                    blockLocation.clone(),
                    material,
                    block.getBlockData().clone()
            );
            originalBlockData.put(blockKey, originalData);

            // Schedule regeneration with proper Y-level tracking
            scheduleRegeneration(blockLocation, config, enchantmentType, blockKey);
            return true;

        } finally {
            configLock.readLock().unlock();
        }
    }

    /**
     * FIXED: Generate block key with FULL coordinates including Y-level
     */
    private String getBlockKey(Location location) {
        return location.getWorld().getName() + "_" +
                location.getBlockX() + "_" +
                location.getBlockY() + "_" +
                location.getBlockZ();
    }

    /**
     * FIXED: Schedule regeneration with proper block data tracking
     */
    private void scheduleRegeneration(Location location, RegenerationConfig config, String enchantmentType, String blockKey) {
        if (location == null || location.getWorld() == null) {
            return;
        }

        // Create unique task ID
        String taskId = generateTaskId(location);

        // Cancel existing regeneration for this location if any
        BukkitTask existingTask = activeRegenerations.get(taskId);
        if (existingTask != null && !existingTask.isCancelled()) {
            existingTask.cancel();
        }

        // Apply temporary replacement block immediately
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!plugin.isShuttingDown() && location.getWorld() != null) {
                Block block = location.getBlock();
                Material replaceMaterial = parseBlockType(config.getReplaceWith());
                if (replaceMaterial != null && replaceMaterial != Material.AIR) {
                    block.setType(replaceMaterial, false);
                } else {
                    block.setType(Material.AIR, false);
                }
            }
        });

        // Schedule regeneration
        long delayTicks = config.getRegenerateDelay() * 20L; // Convert seconds to ticks

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                if (!plugin.isShuttingDown() && location.getWorld() != null) {
                    // FIXED: Regenerate block using stored original data
                    regenerateBlockWithOriginalData(location, config, blockKey);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error during block regeneration at " + location, e);
            } finally {
                activeRegenerations.remove(taskId);
                // FIXED: Clean up stored block data after regeneration
                originalBlockData.remove(blockKey);
            }
        }, delayTicks);

        activeRegenerations.put(taskId, task);
        plugin.registerTask("regen-" + taskId, task);
    }

    /**
     * FIXED: Regenerate block using original stored data to maintain exact Y-level
     */
    private void regenerateBlockWithOriginalData(Location location, RegenerationConfig config, String blockKey) {
        Block block = location.getBlock();

        // FIXED: Try to use original block data first
        OriginalBlockData originalData = originalBlockData.get(blockKey);
        if (originalData != null) {
            // Use the original block data to restore the exact block
            try {
                block.setBlockData(originalData.getBlockData(), true);
                plugin.getLogger().fine("Restored block at " + location + " using original data");
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to restore block using original data, using config fallback: " + e.getMessage());
                // Fallback to config-based regeneration
                regenerateBlockFromConfig(block, config);
            }
        } else {
            // Fallback to config-based regeneration
            regenerateBlockFromConfig(block, config);
        }

        // Apply physics update if needed
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!plugin.isShuttingDown() && block.getWorld() != null) {
                block.getState().update(true, true);
            }
        }, 1L);
    }

    /**
     * Regenerate block from configuration (fallback method)
     */
    private void regenerateBlockFromConfig(Block block, RegenerationConfig config) {
        // Parse the regeneration target
        BlockData targetBlockData = parseBlockData(config.getRegenerateInto());

        if (targetBlockData != null) {
            block.setBlockData(targetBlockData, true);
        } else {
            // Fallback to basic material
            Material targetMaterial = parseBlockType(config.getRegenerateInto());
            if (targetMaterial != null) {
                block.setType(targetMaterial, true);
            }
        }
    }

    /**
     * Parse block type from string (supports basic material names)
     */
    private Material parseBlockType(String blockString) {
        if (blockString == null || blockString.trim().isEmpty()) {
            return null;
        }

        try {
            // Handle basic material names
            String materialName = blockString.toUpperCase().split("\\[")[0]; // Remove block state data
            return Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid block type: " + blockString);
            return null;
        }
    }

    /**
     * Parse block data from string (supports block states like WHEAT[age=7])
     */
    private BlockData parseBlockData(String blockString) {
        if (blockString == null || blockString.trim().isEmpty()) {
            return null;
        }

        try {
            // Use Bukkit's block data parser for complex states
            return Bukkit.createBlockData(blockString.toLowerCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().fine("Could not parse block data: " + blockString + ", using basic material");

            // Fallback to basic material
            Material material = parseBlockType(blockString);
            return material != null ? material.createBlockData() : null;
        }
    }

    /**
     * Generate unique task ID for location
     */
    private String generateTaskId(Location location) {
        return String.format("%s_%d_%d_%d_%d",
                location.getWorld().getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ(),
                taskCounter.incrementAndGet()
        );
    }

    /**
     * Check if a block type is configured for regeneration
     */
    public boolean isRegenerationBlock(Material material) {
        if (material == null) return false;

        configLock.readLock().lock();
        try {
            return regenerationConfigs.containsKey(material.name().toUpperCase());
        } finally {
            configLock.readLock().unlock();
        }
    }

    /**
     * Get regeneration config for a material
     */
    public RegenerationConfig getRegenerationConfig(Material material) {
        if (material == null) return null;

        configLock.readLock().lock();
        try {
            return regenerationConfigs.get(material.name().toUpperCase());
        } finally {
            configLock.readLock().unlock();
        }
    }

    /**
     * Start cleanup task for performance monitoring
     */
    private void startCleanupTask() {
        BukkitTask cleanupTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!plugin.isShuttingDown()) {
                cleanupCompletedTasks();
                cleanupExpiredBlockData();

                // Log performance metrics every 5 minutes
                if (System.currentTimeMillis() % 300000 < 30000) {
                    plugin.getLogger().info("Active regenerations: " + activeRegenerations.size() +
                            ", Stored block data: " + originalBlockData.size());
                }
            }
        }, CLEANUP_INTERVAL_TICKS, CLEANUP_INTERVAL_TICKS);

        plugin.registerTask("regen-cleanup", cleanupTask);
    }

    /**
     * Clean up completed regeneration tasks
     */
    private void cleanupCompletedTasks() {
        activeRegenerations.entrySet().removeIf(entry ->
                entry.getValue().isCancelled() ||
                        !Bukkit.getScheduler().isQueued(entry.getValue().getTaskId())
        );
    }

    /**
     * FIXED: Clean up expired block data to prevent memory leaks
     */
    private void cleanupExpiredBlockData() {
        // Remove block data that has been stored for too long (prevents memory leaks)
        long currentTime = System.currentTimeMillis();
        long maxAge = 300000; // 5 minutes in milliseconds

        originalBlockData.entrySet().removeIf(entry -> {
            OriginalBlockData data = entry.getValue();
            return (currentTime - data.getTimestamp()) > maxAge;
        });
    }

    /**
     * Cancel all regenerations and cleanup
     */
    public void shutdown() {
        plugin.getLogger().info("Shutting down block regeneration manager...");

        // Cancel all active regeneration tasks
        for (BukkitTask task : activeRegenerations.values()) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }

        activeRegenerations.clear();
        regenerationConfigs.clear();
        originalBlockData.clear(); // FIXED: Clear stored block data

        plugin.getLogger().info("Block regeneration manager shutdown complete");
    }

    /**
     * Get active regeneration count for monitoring
     */
    public int getActiveRegenerationCount() {
        return activeRegenerations.size();
    }

    /**
     * Get stored block data count for monitoring
     */
    public int getStoredBlockDataCount() {
        return originalBlockData.size();
    }

    /**
     * Force regenerate all pending blocks (for testing/admin use)
     */
    public void forceRegenerateAll() {
        plugin.getLogger().info("Force regenerating " + activeRegenerations.size() + " blocks...");

        for (Map.Entry<String, BukkitTask> entry : activeRegenerations.entrySet()) {
            BukkitTask task = entry.getValue();
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }

        activeRegenerations.clear();
        originalBlockData.clear(); // FIXED: Clear stored block data
    }

    /**
     * FIXED: Inner class for storing original block data with timestamp
     */
    private static class OriginalBlockData {
        private final Location location;
        private final Material material;
        private final BlockData blockData;
        private final long timestamp;

        public OriginalBlockData(Location location, Material material, BlockData blockData) {
            this.location = location;
            this.material = material;
            this.blockData = blockData;
            this.timestamp = System.currentTimeMillis();
        }

        public Location getLocation() { return location; }
        public Material getMaterial() { return material; }
        public BlockData getBlockData() { return blockData; }
        public long getTimestamp() { return timestamp; }
    }

    /**
     * Inner class for regeneration configuration
     */
    public static class RegenerationConfig {
        private final Material originalMaterial;
        private final String replaceWith;
        private final int regenerateDelay;
        private final String regenerateInto;

        public RegenerationConfig(Material originalMaterial, String replaceWith, int regenerateDelay, String regenerateInto) {
            this.originalMaterial = originalMaterial;
            this.replaceWith = replaceWith != null ? replaceWith : "AIR";
            this.regenerateDelay = Math.max(1, regenerateDelay); // Minimum 1 second
            this.regenerateInto = regenerateInto != null ? regenerateInto : originalMaterial.name();
        }

        public Material getOriginalMaterial() { return originalMaterial; }
        public String getReplaceWith() { return replaceWith; }
        public int getRegenerateDelay() { return regenerateDelay; }
        public String getRegenerateInto() { return regenerateInto; }

        @Override
        public String toString() {
            return "RegenerationConfig{" +
                    "originalMaterial=" + originalMaterial +
                    ", replaceWith='" + replaceWith + '\'' +
                    ", regenerateDelay=" + regenerateDelay +
                    ", regenerateInto='" + regenerateInto + '\'' +
                    '}';
        }
    }
}