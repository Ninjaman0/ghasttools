package com.ghasttools.hooks;

import com.ghasttools.GhastToolsPlugin;
import com.ghasttools.data.PlayerData;
import com.ghasttools.milestones.MilestoneData;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ENHANCED: PlaceholderAPI integration with milestone placeholder support
 */
public class PlaceholderAPIHook extends PlaceholderExpansion {

    private final GhastToolsPlugin plugin;
    private final AtomicBoolean isRegistered = new AtomicBoolean(false);

    // FIXED: Enhanced caching to prevent memory leaks
    private final ConcurrentHashMap<String, String> placeholderCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> cacheTimestamps = new ConcurrentHashMap<>();

    // Configuration constants to avoid magic numbers
    private static final long CACHE_DURATION_MS = 5000; // 5 seconds
    private static final int MAX_CACHE_SIZE = 1000;
    private static final long NEXT_EVENT_CHECK_INTERVAL = 60000; // 1 minute

    public PlaceholderAPIHook(GhastToolsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "ghasttools";
    }

    @Override
    public String getAuthor() {
        return plugin.getDescription().getAuthors().toString();
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // This is required for the expansion to persist through reloads
    }

    @Override
    public boolean canRegister() {
        return plugin != null && plugin.isEnabled();
    }

    /**
     * Enhanced registration with proper error handling
     */
    @Override
    public boolean register() {
        if (isRegistered.get()) {
            return true;
        }

        try {
            boolean result = super.register();
            isRegistered.set(result);

            if (result) {
                plugin.getLogger().info("PlaceholderAPI expansion registered successfully");
                startCacheCleanupTask();
            }

            return result;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to register PlaceholderAPI expansion: " + e.getMessage());
            return false;
        }
    }


    @Override
    public String onRequest(OfflinePlayer player, String identifier) {
        if (player == null || identifier == null) {
            return "";
        }

        // FIXED: Check if plugin is shutting down
        if (plugin.isShuttingDown()) {
            return "";
        }

        UUID playerUUID = player.getUniqueId();
        String cacheKey = playerUUID + "_" + identifier;

        // Check cache first
        String cached = getCachedPlaceholder(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            String result = processPlaceholder(player, identifier, playerUUID);

            // Cache the result
            if (result != null && !result.isEmpty()) {
                cachePlaceholder(cacheKey, result);
            }

            return result != null ? result : "";

        } catch (Exception e) {
            plugin.getLogger().fine("Error processing placeholder '" + identifier + "': " + e.getMessage());
            return "";
        }
    }

    /**
     * ENHANCED: Process placeholders with milestone support
     */
    private String processPlaceholder(OfflinePlayer player, String identifier, UUID playerUUID) {
        // Tool-related placeholders
        if (identifier.startsWith("tool_")) {
            return processToolPlaceholders(player, identifier);
        }

        // Enchantment-related placeholders
        if (identifier.startsWith("enchant_")) {
            return processEnchantmentPlaceholders(player, identifier);
        }

        // FIXED: Milestone-related placeholders
        if (identifier.startsWith("milestone_") || identifier.contains("_milestone")) {
            return processMilestonePlaceholders(playerUUID, identifier);
        }

        // ADDED: Unclaimed milestone placeholders
        if (identifier.startsWith("unclaimed_")) {
            return processUnclaimedMilestonePlaceholders(playerUUID, identifier);
        }

        // Statistics placeholders
        if (isStatisticPlaceholder(identifier)) {
            return processStatisticPlaceholders(playerUUID, identifier);
        }

        // System placeholders
        return processSystemPlaceholders(identifier);
    }

    /**
     * FIXED: Process milestone-related placeholders
     */
    private String processMilestonePlaceholders(UUID playerUUID, String identifier) {
        if (plugin.getMilestoneManager() == null) {
            return "0";
        }

        try {
            MilestoneData milestoneData = plugin.getMilestoneManager().getPlayerMilestoneData(playerUUID);

            // FIXED: Handle material-specific milestone placeholders
            // Format: <material>_milestone (e.g., stone_milestone, dirt_milestone)
            if (identifier.endsWith("_milestone")) {
                String materialName = identifier.substring(0, identifier.length() - 10); // Remove "_milestone"
                try {
                    Material material = Material.valueOf(materialName.toUpperCase());
                    return String.valueOf(milestoneData.getBlocksBroken(material));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().fine("Invalid material in milestone placeholder: " + materialName);
                    return "0";
                }
            }

            // Handle milestone_<material> format
            if (identifier.startsWith("milestone_")) {
                String materialName = identifier.substring(10); // Remove "milestone_"
                try {
                    Material material = Material.valueOf(materialName.toUpperCase());
                    return String.valueOf(milestoneData.getBlocksBroken(material));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().fine("Invalid material in milestone placeholder: " + materialName);
                    return "0";
                }
            }

            // Handle specific milestone queries
            // Format: <material>_milestone_<level>_claimed
            if (identifier.matches("\\w+_milestone_\\d+_claimed")) {
                String[] parts = identifier.split("_");
                if (parts.length >= 4) {
                    String materialName = parts[0];
                    int milestoneLevel;
                    try {
                        milestoneLevel = Integer.parseInt(parts[2]);
                        Material material = Material.valueOf(materialName.toUpperCase());
                        return String.valueOf(milestoneData.isMilestoneClaimed(material, milestoneLevel));
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().fine("Invalid milestone claimed placeholder: " + identifier);
                        return "false";
                    }
                }
            }

            // Handle milestone progress queries
            // Format: <material>_milestone_<level>_progress
            if (identifier.matches("\\w+_milestone_\\d+_progress")) {
                String[] parts = identifier.split("_");
                if (parts.length >= 4) {
                    String materialName = parts[0];
                    int milestoneLevel;
                    try {
                        milestoneLevel = Integer.parseInt(parts[2]);
                        Material material = Material.valueOf(materialName.toUpperCase());

                        var config = plugin.getMilestoneManager().getMilestoneConfig(material);
                        if (config != null && config.hasLevel(milestoneLevel)) {
                            long required = config.getLevel(milestoneLevel).getAmount();
                            long current = milestoneData.getBlocksBroken(material);
                            double percentage = Math.min(100.0, (double) current / required * 100.0);
                            return String.format("%.1f", percentage);
                        }
                        return "0.0";
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().fine("Invalid milestone progress placeholder: " + identifier);
                        return "0.0";
                    }
                }
            }

            // Handle milestone requirement queries
            // Format: <material>_milestone_<level>_required
            if (identifier.matches("\\w+_milestone_\\d+_required")) {
                String[] parts = identifier.split("_");
                if (parts.length >= 4) {
                    String materialName = parts[0];
                    int milestoneLevel;
                    try {
                        milestoneLevel = Integer.parseInt(parts[2]);
                        Material material = Material.valueOf(materialName.toUpperCase());

                        var config = plugin.getMilestoneManager().getMilestoneConfig(material);
                        if (config != null && config.hasLevel(milestoneLevel)) {
                            long required = config.getLevel(milestoneLevel).getAmount();
                            return String.valueOf(required);
                        }
                        return "0";
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().fine("Invalid milestone required placeholder: " + identifier);
                        return "0";
                    }
                }
            }

            // Handle milestone remaining queries
            // Format: <material>_milestone_<level>_remaining
            if (identifier.matches("\\w+_milestone_\\d+_remaining")) {
                String[] parts = identifier.split("_");
                if (parts.length >= 4) {
                    String materialName = parts[0];
                    int milestoneLevel;
                    try {
                        milestoneLevel = Integer.parseInt(parts[2]);
                        Material material = Material.valueOf(materialName.toUpperCase());

                        var config = plugin.getMilestoneManager().getMilestoneConfig(material);
                        if (config != null && config.hasLevel(milestoneLevel)) {
                            long required = config.getLevel(milestoneLevel).getAmount();
                            long current = milestoneData.getBlocksBroken(material);
                            long remaining = Math.max(0, required - current);
                            return String.valueOf(remaining);
                        }
                        return "0";
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().fine("Invalid milestone remaining placeholder: " + identifier);
                        return "0";
                    }
                }
            }

            // Handle total milestone count for a material
            // Format: <material>_milestone_total
            if (identifier.matches("\\w+_milestone_total")) {
                String materialName = identifier.substring(0, identifier.length() - 15); // Remove "_milestone_total"
                try {
                    Material material = Material.valueOf(materialName.toUpperCase());
                    var config = plugin.getMilestoneManager().getMilestoneConfig(material);
                    if (config != null) {
                        return String.valueOf(config.getAllLevels().size());
                    }
                    return "0";
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().fine("Invalid material in milestone total placeholder: " + materialName);
                    return "0";
                }
            }

            // Handle completed milestone count for a material
            // Format: <material>_milestone_completed
            if (identifier.matches("\\w+_milestone_completed")) {
                String materialName = identifier.substring(0, identifier.length() - 19); // Remove "_milestone_completed"
                try {
                    Material material = Material.valueOf(materialName.toUpperCase());
                    var config = plugin.getMilestoneManager().getMilestoneConfig(material);
                    if (config != null) {
                        int completed = 0;
                        for (Map.Entry<Integer, ?> entry : config.getAllLevels().entrySet()) {
                            if (milestoneData.isMilestoneClaimed(material, entry.getKey())) {
                                completed++;
                            }
                        }
                        return String.valueOf(completed);
                    }
                    return "0";
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().fine("Invalid material in milestone completed placeholder: " + materialName);
                    return "0";
                }
            }

            return "0";

        } catch (Exception e) {
            plugin.getLogger().fine("Error processing milestone placeholder: " + e.getMessage());
            return "0";
        }
    }

    /**
     * ADDED: Process unclaimed milestone placeholders
     */
    private String processUnclaimedMilestonePlaceholders(UUID playerUUID, String identifier) {
        if (plugin.getMilestoneManager() == null) {
            return "0";
        }

        try {
            // Total unclaimed milestones across all materials
            if (identifier.equals("unclaimed_total")) {
                return String.valueOf(plugin.getMilestoneManager().getTotalUnclaimedMilestoneCount(playerUUID));
            }

            // Unclaimed milestones for a specific material
            // Format: unclaimed_<material> (e.g., unclaimed_wheat, unclaimed_diamond_ore)
            if (identifier.startsWith("unclaimed_") && !identifier.equals("unclaimed_total")) {
                String materialName = identifier.substring(10); // Remove "unclaimed_"
                try {
                    Material material = Material.valueOf(materialName.toUpperCase());
                    return String.valueOf(plugin.getMilestoneManager().getUnclaimedMilestoneCount(playerUUID, material));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().fine("Invalid material in unclaimed milestone placeholder: " + materialName);
                    return "0";
                }
            }

            // Unclaimed milestones list for a specific material (formatted)
            // Format: unclaimed_<material>_list
            if (identifier.matches("unclaimed_\\w+_list")) {
                String materialName = identifier.substring(10, identifier.length() - 5); // Remove "unclaimed_" and "_list"
                try {
                    Material material = Material.valueOf(materialName.toUpperCase());
                    var unclaimedList = plugin.getMilestoneManager().getUnclaimedMilestones(playerUUID, material);

                    if (unclaimedList.isEmpty()) {
                        return "None";
                    }

                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < unclaimedList.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append("Level ").append(unclaimedList.get(i).getLevel());
                    }
                    return sb.toString();

                } catch (IllegalArgumentException e) {
                    plugin.getLogger().fine("Invalid material in unclaimed milestone list placeholder: " + materialName);
                    return "None";
                }
            }

            // Unclaimed milestones count by category
            if (identifier.matches("unclaimed_\\w+_category")) {
                String categoryName = identifier.substring(10, identifier.length() - 9); // Remove "unclaimed_" and "_category"
                var categoryMaterials = plugin.getMilestoneManager().getProfileCategoryMaterials(categoryName);

                int totalUnclaimed = 0;
                for (Material material : categoryMaterials) {
                    totalUnclaimed += plugin.getMilestoneManager().getUnclaimedMilestoneCount(playerUUID, material);
                }

                return String.valueOf(totalUnclaimed);
            }

            // Next unclaimed milestone for a material
            // Format: unclaimed_<material>_next_level
            if (identifier.matches("unclaimed_\\w+_next_level")) {
                String materialName = identifier.substring(10, identifier.length() - 11); // Remove "unclaimed_" and "_next_level"
                try {
                    Material material = Material.valueOf(materialName.toUpperCase());
                    var unclaimedList = plugin.getMilestoneManager().getUnclaimedMilestones(playerUUID, material);

                    if (unclaimedList.isEmpty()) {
                        return "0";
                    }

                    // Return the lowest level unclaimed milestone
                    return String.valueOf(unclaimedList.get(0).getLevel());

                } catch (IllegalArgumentException e) {
                    plugin.getLogger().fine("Invalid material in unclaimed milestone next level placeholder: " + materialName);
                    return "0";
                }
            }

            // Highest unclaimed milestone for a material
            // Format: unclaimed_<material>_highest_level
            if (identifier.matches("unclaimed_\\w+_highest_level")) {
                String materialName = identifier.substring(10, identifier.length() - 14); // Remove "unclaimed_" and "_highest_level"
                try {
                    Material material = Material.valueOf(materialName.toUpperCase());
                    var unclaimedList = plugin.getMilestoneManager().getUnclaimedMilestones(playerUUID, material);

                    if (unclaimedList.isEmpty()) {
                        return "0";
                    }

                    // Return the highest level unclaimed milestone
                    return String.valueOf(unclaimedList.get(unclaimedList.size() - 1).getLevel());

                } catch (IllegalArgumentException e) {
                    plugin.getLogger().fine("Invalid material in unclaimed milestone highest level placeholder: " + materialName);
                    return "0";
                }
            }

            return "0";

        } catch (Exception e) {
            plugin.getLogger().fine("Error processing unclaimed milestone placeholder: " + e.getMessage());
            return "0";
        }
    }

    /**
     * Process tool-related placeholders
     */
    private String processToolPlaceholders(OfflinePlayer player, String identifier) {
        Player onlinePlayer = player.getPlayer();
        if (onlinePlayer == null || !onlinePlayer.isOnline()) {
            return getDefaultToolValue(identifier);
        }

        ItemStack heldTool = getHeldGhastTool(onlinePlayer);

        switch (identifier) {
            case "tool_type":
                return heldTool != null ?
                        plugin.getToolManager().getToolType(heldTool) : "none";

            case "tool_tier":
                return heldTool != null ?
                        String.valueOf(plugin.getToolManager().getToolTier(heldTool)) : "0";

            case "tool_id":
                return heldTool != null ?
                        plugin.getToolManager().getToolId(heldTool) : "none";

            case "tool_enchant_count":
                if (heldTool != null) {
                    Map<String, Integer> enchantments = plugin.getToolManager().getToolEnchantments(heldTool);
                    return String.valueOf(enchantments.size());
                }
                return "0";

            case "tool_name":
                if (heldTool != null && heldTool.hasItemMeta() && heldTool.getItemMeta().hasDisplayName()) {
                    return heldTool.getItemMeta().getDisplayName();
                }
                return "none";

            case "tool_material":
                return heldTool != null ? heldTool.getType().name().toLowerCase() : "none";

            case "tool_unbreakable":
                if (heldTool != null && heldTool.hasItemMeta()) {
                    return String.valueOf(heldTool.getItemMeta().isUnbreakable());
                }
                return "false";

            default:
                return "";
        }
    }

    /**
     * Process enchantment-related placeholders
     */
    private String processEnchantmentPlaceholders(OfflinePlayer player, String identifier) {
        Player onlinePlayer = player.getPlayer();
        if (onlinePlayer == null || !onlinePlayer.isOnline()) {
            return getDefaultEnchantmentValue(identifier);
        }

        // Handle specific enchantment level queries
        if (identifier.matches("enchant_\\w+_level")) {
            String enchantName = identifier.substring(8, identifier.length() - 6); // Remove "enchant_" and "_level"
            ItemStack heldTool = getHeldGhastTool(onlinePlayer);
            if (heldTool != null) {
                Map<String, Integer> enchantments = plugin.getToolManager().getToolEnchantments(heldTool);
                return String.valueOf(enchantments.getOrDefault(enchantName, 0));
            }
            return "0";
        }

        // Handle enchantment max level queries
        if (identifier.matches("enchant_\\w+_max_level")) {
            String enchantName = identifier.substring(8, identifier.length() - 10); // Remove "enchant_" and "_max_level"
            var enchantConfig = plugin.getEnchantmentManager().getEnchantmentConfig(enchantName);
            return enchantConfig != null ? String.valueOf(enchantConfig.getMaxLevel()) : "0";
        }

        // Handle enchantment cooldown queries
        if (identifier.matches("enchant_\\w+_cooldown")) {
            String enchantName = identifier.substring(8, identifier.length() - 9); // Remove "enchant_" and "_cooldown"
            return getPlayerCooldown(player.getUniqueId(), enchantName);
        }

        // Handle enchantment enabled status
        if (identifier.matches("enchant_\\w+_enabled")) {
            String enchantName = identifier.substring(8, identifier.length() - 8); // Remove "enchant_" and "_enabled"
            var enchantConfig = plugin.getEnchantmentManager().getEnchantmentConfig(enchantName);
            return enchantConfig != null ? String.valueOf(enchantConfig.isEnabled()) : "false";
        }

        return "";
    }

    /**
     * Process player statistics placeholders
     */
    private String processStatisticPlaceholders(UUID playerUUID, String identifier) {
        try {
            // Try to get data from cache or load asynchronously
            var future = plugin.getDataManager().loadPlayerData(playerUUID);

            if (future.isDone()) {
                PlayerData playerData = future.join();
                return getStatisticValue(playerData, identifier);
            } else {
                // Return default and cache result when ready
                future.thenAccept(playerData -> {
                    String cacheKey = playerUUID + "_" + identifier;
                    String value = getStatisticValue(playerData, identifier);
                    cachePlaceholder(cacheKey, value);
                });
                return getDefaultStatisticValue(identifier);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Error loading player statistics: " + e.getMessage());
            return getDefaultStatisticValue(identifier);
        }
    }

    /**
     * Process system placeholders (like next event timer)
     */
    private String processSystemPlaceholders(String identifier) {
        switch (identifier) {
            case "nextevent":
                return calculateNextEventTime();

            case "plugin_version":
                return plugin.getDescription().getVersion();

            case "online_players":
                return String.valueOf(plugin.getServer().getOnlinePlayers().size());

            case "max_players":
                return String.valueOf(plugin.getServer().getMaxPlayers());

            case "server_tps":
                return getServerTPS();

            default:
                return "";
        }
    }

    /**
     * Calculate next event time (following the example structure)
     */
    private String calculateNextEventTime() {
        try {
            // Get event interval from config - using default if not found
            int minutes = 60; // Default value
            if (plugin.getConfigManager() != null && plugin.getConfigManager().getMainConfig() != null) {
                minutes = plugin.getConfigManager().getMainConfig().getInt("events.start-event-every-minutes", 60);
            }

            // Calculate time until next event
            long serverStartTime = ManagementFactory.getRuntimeMXBean().getStartTime();
            long serverUptime = System.currentTimeMillis() - serverStartTime;
            long uptimeMinutes = serverUptime / (1000 * 60);
            long nextEventMinutes = minutes - (uptimeMinutes % minutes);

            if (nextEventMinutes <= 0) {
                nextEventMinutes = minutes;
            }

            return formatTime((int) (nextEventMinutes * 60));
        } catch (Exception e) {
            plugin.getLogger().fine("Error calculating next event time: " + e.getMessage());
            return "Unknown";
        }
    }

    /**
     * Get held GhastTool from either hand
     */
    private ItemStack getHeldGhastTool(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (plugin.getToolManager().isGhastTool(mainHand)) {
            return mainHand;
        }

        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (plugin.getToolManager().isGhastTool(offHand)) {
            return offHand;
        }

        return null;
    }

    /**
     * Get player cooldown safely
     */
    private String getPlayerCooldown(UUID playerUUID, String enchantName) {
        try {
            var future = plugin.getDataManager().loadPlayerData(playerUUID);
            if (future.isDone()) {
                PlayerData playerData = future.join();
                long cooldownMs = playerData.getRemainingCooldown(enchantName);
                return String.valueOf(Math.max(0, cooldownMs / 1000));
            }
            return "0";
        } catch (Exception e) {
            plugin.getLogger().fine("Error getting cooldown for " + enchantName + ": " + e.getMessage());
            return "0";
        }
    }

    /**
     * Get statistic value from player data
     */
    private String getStatisticValue(PlayerData playerData, String identifier) {
        switch (identifier) {
            case "blocks_broken":
                return formatNumber(playerData.getTotalBlocksBroken());
            case "xp_earned":
                return formatNumber(playerData.getTotalXpEarned());
            case "essence_earned":
                return formatNumber(playerData.getTotalEssenceEarned());
            case "last_enchant_used":
                return playerData.getLastEnchantUsed().isEmpty() ? "none" : playerData.getLastEnchantUsed();
            case "total_meteors":
                return String.valueOf(playerData.getTotalMeteorsSpawned());
            case "total_airstrikes":
                return String.valueOf(playerData.getTotalAirstrikes());
            case "favorite_tool":
                return playerData.getFavoriteToolType().isEmpty() ? "none" : playerData.getFavoriteToolType();
            case "total_tool_usage":
                return String.valueOf(playerData.getToolUsageCount().values().stream().mapToInt(Integer::intValue).sum());
            case "total_enchant_usage":
                return String.valueOf(playerData.getEnchantmentUsageCount().values().stream().mapToLong(Long::longValue).sum());
            default:
                return "";
        }
    }

    /**
     * Check if identifier is a statistic placeholder
     */
    private boolean isStatisticPlaceholder(String identifier) {
        return identifier.equals("blocks_broken") || identifier.equals("xp_earned") ||
                identifier.equals("essence_earned") || identifier.equals("last_enchant_used") ||
                identifier.equals("total_meteors") || identifier.equals("total_airstrikes") ||
                identifier.equals("favorite_tool") || identifier.equals("total_tool_usage") ||
                identifier.equals("total_enchant_usage");
    }

    /**
     * FIXED: Enhanced caching with proper memory management
     */
    private String getCachedPlaceholder(String cacheKey) {
        Long timestamp = cacheTimestamps.get(cacheKey);
        if (timestamp != null && System.currentTimeMillis() - timestamp < CACHE_DURATION_MS) {
            return placeholderCache.get(cacheKey);
        }

        // Remove expired entry
        placeholderCache.remove(cacheKey);
        cacheTimestamps.remove(cacheKey);
        return null;
    }

    /**
     * Cache placeholder with size limits
     */
    private void cachePlaceholder(String cacheKey, String value) {
        if (placeholderCache.size() >= MAX_CACHE_SIZE) {
            cleanExpiredCache();
        }

        if (placeholderCache.size() < MAX_CACHE_SIZE) {
            placeholderCache.put(cacheKey, value);
            cacheTimestamps.put(cacheKey, System.currentTimeMillis());
        }
    }

    /**
     * Clean expired cache entries
     */
    private void cleanExpiredCache() {
        long currentTime = System.currentTimeMillis();
        cacheTimestamps.entrySet().removeIf(entry -> {
            if (currentTime - entry.getValue() > CACHE_DURATION_MS) {
                placeholderCache.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * Start cache cleanup task
     */
    private void startCacheCleanupTask() {
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!plugin.isShuttingDown()) {
                cleanExpiredCache();
            }
        }, 20L * 30, 20L * 30); // Every 30 seconds
    }

    /**
     * Clear all cached data
     */
    public void clearCache() {
        placeholderCache.clear();
        cacheTimestamps.clear();
    }

    /**
     * Utility methods for formatting and defaults
     */
    private String formatTime(int seconds) {
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, secs);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, secs);
        } else {
            return String.format("%ds", secs);
        }
    }

    private String formatNumber(double number) {
        if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000.0);
        } else if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000.0);
        } else {
            return String.format("%.1f", number);
        }
    }

    private String getServerTPS() {
        try {
            // This is a simplified TPS calculation
            return "20.0"; // Placeholder - actual TPS calculation would require reflection
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private String getDefaultToolValue(String identifier) {
        switch (identifier) {
            case "tool_type":
            case "tool_id":
            case "tool_name":
            case "tool_material":
                return "none";
            case "tool_tier":
            case "tool_enchant_count":
                return "0";
            case "tool_unbreakable":
                return "false";
            default:
                return "";
        }
    }

    private String getDefaultEnchantmentValue(String identifier) {
        if (identifier.endsWith("_level") || identifier.endsWith("_max_level") || identifier.endsWith("_cooldown")) {
            return "0";
        } else if (identifier.endsWith("_enabled")) {
            return "false";
        }
        return "";
    }

    private String getDefaultStatisticValue(String identifier) {
        switch (identifier) {
            case "last_enchant_used":
            case "favorite_tool":
                return "none";
            case "blocks_broken":
            case "total_meteors":
            case "total_airstrikes":
            case "total_tool_usage":
            case "total_enchant_usage":
                return "0";
            case "xp_earned":
            case "essence_earned":
                return "0.0";
            default:
                return "";
        }
    }


    /**
     * Get cache statistics for monitoring
     */
    public Map<String, Integer> getCacheStats() {
        return Map.of(
                "size", placeholderCache.size(),
                "capacity", MAX_CACHE_SIZE
        );
    }
}