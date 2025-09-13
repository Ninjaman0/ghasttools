package com.ghasttools.levelsmanager;

import com.ghasttools.GhastToolsPlugin;
import com.ninja.ghast.ghastLevels.LevelsPlugin;
import com.ninja.ghast.ghastLevels.managers.LevelManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * ENHANCED: Thread-safe levels handler with direct LevelManager access
 */
public class levelshandler {
    private final GhastToolsPlugin plugin;
    private final Map<UUID, Integer> levelCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> levelCacheTimestamps = new ConcurrentHashMap<>();
    private final Set<UUID> trackedPlayers = ConcurrentHashMap.newKeySet();
    private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);

    private BukkitTask refreshTask;
    private final long CACHE_EXPIRY_MS = 60000; // 1 minute cache expiry
    private volatile LevelManager levelManager;
    private volatile LevelsPlugin levelsPlugin;

    private static final int MAX_CACHE_SIZE = 1000;

    public levelshandler(GhastToolsPlugin plugin) {
        this.plugin = plugin;
        initLevelManager();
        startRefreshTask();
    }

    /**
     * ENHANCED: Initialize level manager with proper error handling
     */
    private void initLevelManager() {
        try {
            levelsPlugin = (LevelsPlugin) Bukkit.getPluginManager().getPlugin("GhastLevels");
            if (levelsPlugin != null && levelsPlugin.isEnabled()) {
                this.levelManager = levelsPlugin.getLevelManager();
                if (this.levelManager == null) {
                    plugin.getLogger().severe("GhastLevels plugin found but its LevelManager is null!");
                    return;
                }
                plugin.getLogger().info("Successfully connected to GhastLevels' LevelManager");
            } else {
                plugin.getLogger().warning("GhastLevels plugin not found or not enabled. Level features will be disabled.");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error connecting to GhastLevels: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * ENHANCED: Check if the levels system is available
     */
    public boolean isAvailable() {
        return levelManager != null && levelsPlugin != null && levelsPlugin.isEnabled();
    }

    /**
     * ENHANCED: Get direct access to LevelManager for giving points directly
     */
    public LevelManager getLevelManager() {
        if (!isAvailable()) {
            initLevelManager(); // Try to reconnect
        }
        return levelManager;
    }

    /**
     * Thread-safe cache cleanup with size limits
     */
    public void cleanupCache() {
        cacheLock.writeLock().lock();
        try {
            if (levelCache.size() > MAX_CACHE_SIZE) {
                // Remove oldest entries based on timestamp
                List<UUID> oldestEntries = levelCacheTimestamps.entrySet().stream()
                        .sorted(Map.Entry.comparingByValue())
                        .limit(levelCache.size() - MAX_CACHE_SIZE)
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toList());

                for (UUID uuid : oldestEntries) {
                    levelCache.remove(uuid);
                    levelCacheTimestamps.remove(uuid);
                }
            }
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    /**
     * Thread-safe refresh task with shutdown handling
     */
    private void startRefreshTask() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }

        refreshTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (isShuttingDown.get()) return;

            try {
                for (UUID playerUUID : trackedPlayers) {
                    Player player = Bukkit.getPlayer(playerUUID);
                    if (player != null && player.isOnline()) {
                        clearCache(playerUUID);
                        refreshPlayerLevel(player);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error in level refresh task: " + e.getMessage());
            }
        }, 1200L, 1200L);
    }

    /**
     * Thread-safe async level retrieval
     */
    public CompletableFuture<Integer> getPlayerLevelAsync(Player player) {
        if (player == null) {
            return CompletableFuture.completedFuture(0);
        }

        UUID playerUUID = player.getUniqueId();
        trackedPlayers.add(playerUUID);

        // Check cache first
        cacheLock.readLock().lock();
        try {
            if (levelCache.containsKey(playerUUID)) {
                long lastUpdated = levelCacheTimestamps.getOrDefault(playerUUID, 0L);
                if (System.currentTimeMillis() - lastUpdated <= CACHE_EXPIRY_MS) {
                    return CompletableFuture.completedFuture(levelCache.get(playerUUID));
                }
            }
        } finally {
            cacheLock.readLock().unlock();
        }

        CompletableFuture<Integer> future = new CompletableFuture<>();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                clearCache(playerUUID);
                int level = fetchPlayerLevel(player);

                cacheLock.writeLock().lock();
                try {
                    levelCache.put(playerUUID, level);
                    levelCacheTimestamps.put(playerUUID, System.currentTimeMillis());
                } finally {
                    cacheLock.writeLock().unlock();
                }

                future.complete(level);
            } catch (Exception e) {
                plugin.getLogger().warning("Error fetching player level async: " + e.getMessage());
                future.complete(0);
            }
        });

        return future;
    }

    /**
     * Synchronous level retrieval with proper caching and validation
     */
    public int getPlayerLevel(Player player) {
        if (player == null) {
            return 0;
        }

        UUID playerUUID = player.getUniqueId();
        trackedPlayers.add(playerUUID);

        // Check cache first
        cacheLock.readLock().lock();
        try {
            if (levelCache.containsKey(playerUUID)) {
                long lastUpdated = levelCacheTimestamps.getOrDefault(playerUUID, 0L);
                if (System.currentTimeMillis() - lastUpdated <= CACHE_EXPIRY_MS) {
                    return levelCache.get(playerUUID);
                }
            }
        } finally {
            cacheLock.readLock().unlock();
        }

        // Cache miss or expired, fetch new data
        clearCache(playerUUID);
        return refreshPlayerLevel(player);
    }

    /**
     * Safe level fetching with error handling
     */
    private int fetchPlayerLevel(Player player) {
        if (player == null) {
            return 0;
        }

        try {
            if (levelManager != null) {
                return levelManager.getLevel(player.getUniqueId());
            } else {
                initLevelManager();
                if (levelManager != null) {
                    return levelManager.getLevel(player.getUniqueId());
                } else {
                    return 0;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error fetching player level from GhastLevels: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Thread-safe level refresh with caching
     */
    public int refreshPlayerLevel(Player player) {
        if (player == null) {
            return 0;
        }

        int level = fetchPlayerLevel(player);
        UUID playerUUID = player.getUniqueId();

        cacheLock.writeLock().lock();
        try {
            levelCache.put(playerUUID, level);
            levelCacheTimestamps.put(playerUUID, System.currentTimeMillis());
        } finally {
            cacheLock.writeLock().unlock();
        }

        return level;
    }

    /**
     * Thread-safe cache update
     */
    public void updatePlayerLevel(Player player, int newLevel) {
        if (player == null) {
            return;
        }

        UUID playerUUID = player.getUniqueId();
        trackedPlayers.add(playerUUID);

        clearCache(playerUUID);

        cacheLock.writeLock().lock();
        try {
            levelCache.put(playerUUID, newLevel);
            levelCacheTimestamps.put(playerUUID, System.currentTimeMillis());
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    /**
     * Thread-safe cache clearing
     */
    public void clearCache(UUID playerUUID) {
        cacheLock.writeLock().lock();
        try {
            levelCache.remove(playerUUID);
            levelCacheTimestamps.remove(playerUUID);
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    /**
     * Async level requirement validation with bypass checking
     */
    public CompletableFuture<Boolean> meetsLevelRequirementAsync(Player player, int requiredLevel) {
        if (requiredLevel <= 0 || player == null) {
            return CompletableFuture.completedFuture(true);
        }

        // Check bypass permission first
        if (player.hasPermission("ghasttools.bypass.levelcheck")) {
            return CompletableFuture.completedFuture(true);
        }

        return getPlayerLevelAsync(player).thenApply(playerLevel -> playerLevel >= requiredLevel);
    }

    /**
     * Synchronous level requirement validation with bypass checking
     */
    public boolean meetsLevelRequirement(Player player, int requiredLevel) {
        if (requiredLevel <= 0 || player == null) {
            return true;
        }

        // Check bypass permission first
        if (player.hasPermission("ghasttools.bypass.levelcheck")) {
            return true;
        }

        int playerLevel = getPlayerLevel(player);
        return playerLevel >= requiredLevel;
    }

    /**
     * Validate player can perform action with level requirement
     */
    public boolean canPerformAction(Player player, String actionType, int requiredLevel) {
        if (player == null) {
            return false;
        }

        // Check bypass permission
        if (player.hasPermission("ghasttools.bypass.levelcheck")) {
            return true;
        }

        // Check if GhastLevels is available
        if (levelManager == null) {
            plugin.getLogger().warning("Cannot validate level requirement - GhastLevels not available");
            return true; // Allow if system not available
        }

        boolean meets = meetsLevelRequirement(player, requiredLevel);

        if (!meets) {
            int playerLevel = getPlayerLevel(player);
            player.sendMessage("§cYou need level " + requiredLevel + " to " + actionType + "!");
            player.sendMessage("§7Your current level: " + playerLevel);
        }

        return meets;
    }

    /**
     * Handle player login
     */
    public void handlePlayerLogin(UUID playerUUID) {
        clearCache(playerUUID);
    }

    /**
     * Handle player logout
     */
    public void handlePlayerLogout(UUID playerUUID) {
        trackedPlayers.remove(playerUUID);
        clearCache(playerUUID);
    }

    /**
     * Proper shutdown with thread safety
     */
    public void shutdown() {
        isShuttingDown.set(true);

        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }

        cacheLock.writeLock().lock();
        try {
            levelCache.clear();
            levelCacheTimestamps.clear();
            trackedPlayers.clear();
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
}