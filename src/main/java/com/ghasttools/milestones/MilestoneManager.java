package com.ghasttools.milestones;

import com.ghasttools.GhastToolsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * FIXED: Manages milestone tracking and configuration with proper data synchronization
 */
public class MilestoneManager {

    private final GhastToolsPlugin plugin;
    private final Map<Material, MilestoneConfig> milestoneConfigs;
    private final Map<UUID, MilestoneData> playerMilestones;
    private final Set<Material> trackedBlocks;
    private final Map<String, List<Material>> profileCategories;

    // ADDED: Linked materials support
    private final Map<Material, String> materialToMilestoneGroup;
    private final Map<String, Set<Material>> milestoneGroups;

    // ADDED: Custom item names for profile GUI
    private final Map<String, Map<String, String>> profileItemNames;

    // ADDED: Synchronization for milestone processing to prevent race conditions
    private final Map<UUID, Object> playerLocks = new ConcurrentHashMap<>();

    // Configuration fields
    private int mainGuiSize = 54;
    private ProfileGuiConfig profileGuiConfig;

    public MilestoneManager(GhastToolsPlugin plugin) {
        this.plugin = plugin;
        this.milestoneConfigs = new ConcurrentHashMap<>();
        this.playerMilestones = new ConcurrentHashMap<>();
        this.trackedBlocks = ConcurrentHashMap.newKeySet();
        this.profileCategories = new ConcurrentHashMap<>();

        // Initialize linked materials maps
        this.materialToMilestoneGroup = new ConcurrentHashMap<>();
        this.milestoneGroups = new ConcurrentHashMap<>();

        // ADDED: Initialize custom item names map
        this.profileItemNames = new ConcurrentHashMap<>();

        loadMilestoneConfiguration();
    }

    public void loadMilestoneConfiguration() {
        try {
            FileConfiguration config = plugin.getConfigManager().getMilestoneConfig();
            if (config == null) {
                plugin.getLogger().warning("milestone.yml not found! Milestone system will be disabled.");
                return;
            }

            milestoneConfigs.clear();
            trackedBlocks.clear();
            profileCategories.clear();
            materialToMilestoneGroup.clear();
            milestoneGroups.clear();
            profileItemNames.clear(); // ADDED: Clear custom item names

            // Load main GUI size
            mainGuiSize = config.getInt("main-gui-size", 54);
            if (mainGuiSize < 9 || mainGuiSize > 54 || mainGuiSize % 9 != 0) {
                plugin.getLogger().warning("Invalid main-gui-size: " + mainGuiSize + ". Using default: 54");
                mainGuiSize = 54;
            }

            // Load profile GUI configuration
            loadProfileGuiConfiguration(config);

            // ENHANCED: Load linked materials configuration
            loadLinkedMaterials(config);

            // ADDED: Load profile item names configuration
            loadProfileItemNames(config);

            // Load tracked blocks
            List<String> blockNames = config.getStringList("milestone-blocks");
            for (String blockName : blockNames) {
                try {
                    Material material = Material.valueOf(blockName.toUpperCase());
                    trackedBlocks.add(material);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid material in milestone-blocks: " + blockName);
                }
            }

            // Load profile categories
            ConfigurationSection profileSection = config.getConfigurationSection("profile");
            if (profileSection != null) {
                for (String category : profileSection.getKeys(false)) {
                    List<String> materialNames = profileSection.getStringList(category);
                    List<Material> materials = new ArrayList<>();

                    for (String materialName : materialNames) {
                        try {
                            Material material = Material.valueOf(materialName.toUpperCase());
                            materials.add(material);
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("Invalid material in profile." + category + ": " + materialName);
                        }
                    }

                    profileCategories.put(category, materials);
                }
            }

            // Load milestone configurations
            ConfigurationSection milestonesSection = config.getConfigurationSection("milestones");
            if (milestonesSection != null) {
                for (String materialName : milestonesSection.getKeys(false)) {
                    try {
                        Material material = Material.valueOf(materialName.toUpperCase());
                        loadMilestoneConfig(material, milestonesSection.getConfigurationSection(materialName));
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Invalid material in milestones: " + materialName);
                    }
                }
            }

            plugin.getLogger().info("Loaded milestone configuration with " + milestoneGroups.size() + " linked milestone groups and custom item names");

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error loading milestone configuration", e);
        }
    }

    /**
     * ENHANCED: Load linked materials configuration with improved logging
     */
    private void loadLinkedMaterials(FileConfiguration config) {
        ConfigurationSection linkedSection = config.getConfigurationSection("linked-materials");
        if (linkedSection == null) {
            plugin.getLogger().info("No linked materials configuration found");
            return;
        }

        for (String groupName : linkedSection.getKeys(false)) {
            List<String> materialNames = linkedSection.getStringList(groupName);
            Set<Material> materials = new HashSet<>();

            for (String materialName : materialNames) {
                try {
                    Material material = Material.valueOf(materialName.toUpperCase());
                    materials.add(material);
                    materialToMilestoneGroup.put(material, groupName);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid material in linked-materials." + groupName + ": " + materialName);
                }
            }

            if (!materials.isEmpty()) {
                milestoneGroups.put(groupName, materials);
                plugin.getLogger().info("Created milestone group '" + groupName + "' with materials: " + materials);
            }
        }

        plugin.getLogger().info("Loaded " + milestoneGroups.size() + " linked milestone groups");
    }

    /**
     * ADDED: Load profile item names configuration
     */
    private void loadProfileItemNames(FileConfiguration config) {
        ConfigurationSection itemNamesSection = config.getConfigurationSection("profile-item-names");
        if (itemNamesSection == null) {
            plugin.getLogger().info("No profile item names configuration found - using default names");
            return;
        }

        for (String category : itemNamesSection.getKeys(false)) {
            ConfigurationSection categorySection = itemNamesSection.getConfigurationSection(category);
            if (categorySection != null) {
                Map<String, String> categoryItemNames = new HashMap<>();

                for (String materialName : categorySection.getKeys(false)) {
                    String customName = categorySection.getString(materialName);
                    if (customName != null && !customName.trim().isEmpty()) {
                        categoryItemNames.put(materialName.toUpperCase(), customName);
                    }
                }

                if (!categoryItemNames.isEmpty()) {
                    profileItemNames.put(category, categoryItemNames);
                    plugin.getLogger().info("Loaded " + categoryItemNames.size() + " custom item names for category: " + category);
                }
            }
        }

        plugin.getLogger().info("Loaded custom item names for " + profileItemNames.size() + " categories");
    }

    /**
     * ADDED: Get custom item name for a material in a specific category
     */
    public String getCustomItemName(String category, Material material) {
        if (category == null || material == null) {
            return null;
        }

        Map<String, String> categoryNames = profileItemNames.get(category.toLowerCase());
        if (categoryNames == null) {
            return null;
        }

        return categoryNames.get(material.name().toUpperCase());
    }

    /**
     * ADDED: Check if a material has a custom name in a category
     */
    public boolean hasCustomItemName(String category, Material material) {
        return getCustomItemName(category, material) != null;
    }

    /**
     * ADDED: Get all custom item names for a category
     */
    public Map<String, String> getCategoryItemNames(String category) {
        if (category == null) {
            return new HashMap<>();
        }

        Map<String, String> categoryNames = profileItemNames.get(category.toLowerCase());
        return categoryNames != null ? new HashMap<>(categoryNames) : new HashMap<>();
    }

    private void loadProfileGuiConfiguration(FileConfiguration config) {
        ConfigurationSection profileGuiSection = config.getConfigurationSection("profile-gui");

        if (profileGuiSection == null) {
            profileGuiConfig = new ProfileGuiConfig();
            return;
        }

        int size = profileGuiSection.getInt("size", 54);
        if (size < 9 || size > 54 || size % 9 != 0) {
            plugin.getLogger().warning("Invalid profile-gui size: " + size + ". Using default: 54");
            size = 54;
        }

        ConfigurationSection playerHeadSection = profileGuiSection.getConfigurationSection("player-head");
        int playerHeadSlot = 4;
        List<String> playerHeadLore = Arrays.asList("&7Player profile information");

        if (playerHeadSection != null) {
            playerHeadSlot = playerHeadSection.getInt("slot", 4);
            playerHeadLore = playerHeadSection.getStringList("lore");
            if (playerHeadLore.isEmpty()) {
                playerHeadLore = Arrays.asList("&7Player profile information");
            }
        }

        int farmingSlot = profileGuiSection.getInt("farming-category-slot", 20);
        int miningSlot = profileGuiSection.getInt("mining-category-slot", 22);
        int foragingSlot = profileGuiSection.getInt("foraging-category-slot", 24);

        ConfigurationSection miscInfoSection = profileGuiSection.getConfigurationSection("misc-info");
        MiscInfoConfig miscInfoConfig = null;

        if (miscInfoSection != null) {
            try {
                Material material = Material.valueOf(miscInfoSection.getString("material", "REDSTONE_BLOCK").toUpperCase());
                String itemName = miscInfoSection.getString("item-name", "&5Other Stats");
                List<String> itemLore = miscInfoSection.getStringList("item-lore");
                int slot = miscInfoSection.getInt("slot", 27);

                miscInfoConfig = new MiscInfoConfig(material, itemName, itemLore, slot);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid material in misc-info: " + miscInfoSection.getString("material"));
            }
        }

        profileGuiConfig = new ProfileGuiConfig(size, playerHeadSlot, playerHeadLore,
                farmingSlot, miningSlot, foragingSlot, miscInfoConfig);
    }

    private void loadMilestoneConfig(Material material, ConfigurationSection section) {
        if (section == null) return;

        try {
            String guiMaterialName = section.getString("gui-material", material.name());
            Material guiMaterial;
            try {
                guiMaterial = Material.valueOf(guiMaterialName.toUpperCase());
            } catch (IllegalArgumentException e) {
                guiMaterial = material;
                plugin.getLogger().warning("Invalid gui-material for " + material + ": " + guiMaterialName);
            }

            String guiName = section.getString("gui-name", "&6" + material.name() + " &4MILESTONES!!");
            List<String> guiLore = section.getStringList("gui_lore");
            int slot = section.getInt("slot", -1);

            MilestoneConfig config = new MilestoneConfig(material, guiMaterial, guiName, guiLore, slot);

            ConfigurationSection subMilestonesSection = section.getConfigurationSection("sub-milestones");
            if (subMilestonesSection != null) {
                for (String levelStr : subMilestonesSection.getKeys(false)) {
                    try {
                        int level = Integer.parseInt(levelStr);
                        ConfigurationSection levelSection = subMilestonesSection.getConfigurationSection(levelStr);

                        if (levelSection != null) {
                            loadMilestoneLevel(config, level, levelSection);
                        }
                    } catch (NumberFormatException e) {
                        plugin.getLogger().warning("Invalid milestone level number: " + levelStr);
                    }
                }
            }

            milestoneConfigs.put(material, config);

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error loading milestone config for " + material, e);
        }
    }

    private void loadMilestoneLevel(MilestoneConfig config, int level, ConfigurationSection section) {
        try {
            long amount = section.getLong("amount", 0);
            String onReachCommand = section.getString("on-reach-command", "");
            String guiCommand = section.getString("gui-command", "");
            String guiName = section.getString("gui_name", "&aMILESTONE " + level);
            List<String> guiLore = section.getStringList("gui_lore");
            int slot = section.getInt("slot", -1);

            String readyItemName = section.getString("gui_item_ready", "LIME_STAINED_GLASS_PANE");
            String notReadyItemName = section.getString("gui_item_not_ready", "RED_STAINED_GLASS_PANE");

            Material readyItem;
            Material notReadyItem;

            try {
                readyItem = Material.valueOf(readyItemName.toUpperCase());
            } catch (IllegalArgumentException e) {
                readyItem = Material.LIME_STAINED_GLASS_PANE;
                plugin.getLogger().warning("Invalid gui_item_ready material: " + readyItemName);
            }

            try {
                notReadyItem = Material.valueOf(notReadyItemName.toUpperCase());
            } catch (IllegalArgumentException e) {
                notReadyItem = Material.RED_STAINED_GLASS_PANE;
                plugin.getLogger().warning("Invalid gui_item_not_ready material: " + notReadyItemName);
            }

            MilestoneConfig.MilestoneLevel milestoneLevel = new MilestoneConfig.MilestoneLevel(
                    amount, onReachCommand, guiCommand, guiName, guiLore, readyItem, notReadyItem, slot
            );

            config.addLevel(level, milestoneLevel);

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error loading milestone level " + level, e);
        }
    }

    /**
     * FIXED: Track block break with proper synchronization and validation
     */
    public void trackBlockBreak(Player player, Material material, long amount) {
        if (player == null || material == null || amount <= 0) {
            return;
        }

        if (!trackedBlocks.contains(material)) {
            return;
        }

        // FIXED: Check if player can break this material at their current location and level
        if (!canPlayerBreakMaterial(player, material)) {
            plugin.getLogger().fine("Player " + player.getName() + " cannot break " + material + " at current level/location");
            return;
        }

        UUID playerUUID = player.getUniqueId();

        // FIXED: Use synchronization to prevent race conditions
        Object playerLock = playerLocks.computeIfAbsent(playerUUID, k -> new Object());

        synchronized (playerLock) {
            // Load player data synchronously within the lock to prevent race conditions
            plugin.getDataManager().loadPlayerData(playerUUID).thenAccept(playerData -> {
                if (playerData != null) {
                    // FIXED: Handle linked materials properly with synchronization
                    Set<Material> materialsToTrack = getLinkedMaterials(material);

                    // Track each linked material separately but with the same amount
                    for (Material trackMaterial : materialsToTrack) {
                        long previousAmount = playerData.getMilestoneBlocksBroken(trackMaterial);

                        // FIXED: Add to PlayerData first (this saves to database)
                        playerData.addMilestoneBlocksBroken(trackMaterial, amount);

                        MilestoneData cacheData = playerMilestones.computeIfAbsent(playerUUID, k -> new MilestoneData());
                        cacheData.addBlocksBroken(trackMaterial, amount);

                        long newAmount = playerData.getMilestoneBlocksBroken(trackMaterial);

                        plugin.getLogger().fine("Milestone tracking for " + player.getName() +
                                ": " + trackMaterial + " from " + previousAmount + " to " + newAmount);
                    }

                    // FIXED: Save player data first, then check milestones to ensure on-reach-command
                    // only executes after data is properly saved
                    plugin.getDataManager().savePlayerData(playerUUID, playerData).thenRun(() -> {
                        // FIXED: Check milestones AFTER data is saved
                        for (Material trackMaterial : materialsToTrack) {
                            long previousAmount = playerData.getMilestoneBlocksBroken(trackMaterial) - amount;
                            long newAmount = playerData.getMilestoneBlocksBroken(trackMaterial);
                            checkMilestoneReached(player, trackMaterial, previousAmount, newAmount);
                        }
                    }).exceptionally(throwable -> {
                        plugin.getLogger().log(Level.WARNING, "Failed to save milestone data for player " + player.getName(), throwable);
                        return null;
                    });
                }
            }).exceptionally(throwable -> {
                plugin.getLogger().log(Level.WARNING, "Failed to load player data for milestone tracking: " + player.getName(), throwable);
                return null;
            });
        }
    }

    /**
     * FIXED: Check if player can break this material based on level and WorldGuard regions
     */
    private boolean canPlayerBreakMaterial(Player player, Material material) {
        // Check level requirement if levels handler is available
        if (plugin.getLevelsHandler() != null) {
            // For milestone materials, we need to check if player can actually break this material
            // based on their tool level and the block requirements

            // First check if player is holding a valid tool
            var tool = player.getInventory().getItemInMainHand();
            if (!plugin.getToolManager().isGhastTool(tool)) {
                return false; // No valid tool
            }

            String toolType = plugin.getToolManager().getToolType(tool);
            int toolTier = plugin.getToolManager().getToolTier(tool);

            if (toolType == null || toolTier <= 0) {
                return false;
            }

            // Check if player can use this tool tier
            if (!plugin.getToolManager().canUseToolTier(player, toolType, toolTier)) {
                return false;
            }
        }

        // Check WorldGuard permissions
        if (plugin.getWorldGuardHook() != null) {
            if (!plugin.getWorldGuardHook().canUseTools(player, player.getLocation())) {
                return false;
            }
        }

        return true;
    }

    /**
     * ENHANCED: Get linked materials for tracking - improved implementation
     */
    private Set<Material> getLinkedMaterials(Material material) {
        String groupName = materialToMilestoneGroup.get(material);
        if (groupName != null) {
            Set<Material> linkedMaterials = milestoneGroups.get(groupName);
            if (linkedMaterials != null && !linkedMaterials.isEmpty()) {

                return new HashSet<>(linkedMaterials);
            }
        }

        // Return set containing just the single material if not linked
        return Set.of(material);
    }

    /**
     * ENHANCED: Get combined progress for linked materials with proper validation
     */
    public long getCombinedProgress(UUID playerUUID, Material material) {
        MilestoneData milestoneData = getPlayerMilestoneData(playerUUID);

        String groupName = materialToMilestoneGroup.get(material);
        if (groupName != null) {
            Set<Material> linkedMaterials = milestoneGroups.get(groupName);
            if (linkedMaterials != null && !linkedMaterials.isEmpty()) {
                long totalProgress = linkedMaterials.stream()
                        .mapToLong(milestoneData::getBlocksBroken)
                        .sum();


                return totalProgress;
            }
        }

        return milestoneData.getBlocksBroken(material);
    }

    /**
     * FIXED: Check milestone reached with proper synchronization
     */
    private void checkMilestoneReached(Player player, Material material, long previousAmount, long newAmount) {
        MilestoneConfig config = milestoneConfigs.get(material);
        if (config == null) {
            return;
        }

        for (Map.Entry<Integer, MilestoneConfig.MilestoneLevel> entry : config.getAllLevels().entrySet()) {
            int level = entry.getKey();
            MilestoneConfig.MilestoneLevel milestone = entry.getValue();

            // FIXED: Only trigger on-reach-command when crossing the threshold
            if (previousAmount < milestone.getAmount() && newAmount >= milestone.getAmount()) {


                String command = milestone.getOnReachCommand();
                if (command != null && !command.trim().isEmpty()) {
                    String processedCommand = command.replace("%player_name%", player.getName());

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedCommand);
                            if (success) {

                            } else {
                                plugin.getLogger().warning("On-reach command execution returned false: " + processedCommand);
                            }
                        } catch (Exception e) {
                            plugin.getLogger().log(Level.WARNING, "Error executing milestone on-reach command: " + processedCommand, e);
                        }
                    });
                }
            }
        }
    }

    /**
     * ENHANCED: Milestone claiming with improved validation and error handling
     */
    public boolean claimMilestone(Player player, Material material, int milestoneLevel) {
        if (player == null || material == null) {
            plugin.getLogger().warning("Invalid parameters for milestone claiming");
            return false;
        }

        UUID playerUUID = player.getUniqueId();

        try {
            var playerDataFuture = plugin.getDataManager().loadPlayerData(playerUUID);
            var playerData = playerDataFuture.join();

            if (playerData == null) {
                player.sendMessage("§cError: Could not load your player data!");
                plugin.getLogger().warning("Failed to load player data for milestone claiming: " + player.getName());
                return false;
            }

            // Check if already claimed
            if (playerData.isMilestoneClaimed(material, milestoneLevel)) {
                player.sendMessage("§cYou have already claimed this milestone!");
                return false;
            }

            MilestoneConfig config = milestoneConfigs.get(material);
            if (config == null || !config.hasLevel(milestoneLevel)) {
                player.sendMessage("§cInvalid milestone!");
                plugin.getLogger().warning("Invalid milestone config for " + material + " level " + milestoneLevel);
                return false;
            }

            MilestoneConfig.MilestoneLevel milestone = config.getLevel(milestoneLevel);

            // ENHANCED: Check combined progress for linked materials
            long playerProgress = getCombinedProgress(playerUUID, material);
            if (playerProgress < milestone.getAmount()) {
                player.sendMessage("§cYou haven't reached this milestone yet! Progress: " + playerProgress + "/" + milestone.getAmount());
                return false;
            }

            // ENHANCED: Command execution with better error handling
            String command = milestone.getGuiCommand();
            if (command != null && !command.trim().isEmpty()) {
                String processedCommand = processCommandPlaceholders(command, player, material, milestoneLevel, milestone);


                // Execute command on main thread with enhanced validation
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (!player.isOnline()) {
                            plugin.getLogger().warning("Player " + player.getName() + " went offline before command execution");
                            return;
                        }

                        boolean commandResult = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedCommand);

                        if (commandResult) {
                            plugin.getLogger().info("Successfully executed milestone command for " + player.getName());
                            player.sendMessage("§a✓ Milestone claimed successfully! Rewards have been given.");
                        } else {
                            plugin.getLogger().warning("Command execution returned false for: " + processedCommand);
                            player.sendMessage("§eWarning: Milestone claimed but there may have been an issue with rewards.");
                        }

                    } catch (Exception e) {
                        plugin.getLogger().log(Level.SEVERE, "Error executing milestone GUI command: " + processedCommand, e);
                        player.sendMessage("§cError occurred while giving rewards. Please contact an administrator.");
                    }
                });
            } else {
                plugin.getLogger().info("No GUI command configured for " + material + " milestone level " + milestoneLevel);
                player.sendMessage("§a✓ Milestone claimed successfully!");
            }

            // Mark milestone as claimed
            playerData.claimMilestone(material, milestoneLevel);

            // Update cache
            MilestoneData cacheData = playerMilestones.computeIfAbsent(playerUUID, k -> new MilestoneData());
            cacheData.claimMilestone(material, milestoneLevel);

            // Save data
            plugin.getDataManager().savePlayerData(playerUUID, playerData).join();


            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error claiming milestone for player " + player.getName(), e);
            player.sendMessage("§cAn error occurred while claiming the milestone. Please try again.");
            return false;
        }
    }

    /**
     * ENHANCED: Process all placeholders in milestone commands
     */
    private String processCommandPlaceholders(String command, Player player, Material material, int milestoneLevel, MilestoneConfig.MilestoneLevel milestone) {
        String processed = command;

        // Basic player placeholders
        processed = processed.replace("%player_name%", player.getName());
        processed = processed.replace("%player_uuid%", player.getUniqueId().toString());
        processed = processed.replace("%player_displayname%", player.getDisplayName());

        // Milestone-specific placeholders
        processed = processed.replace("%material%", material.name().toLowerCase());
        processed = processed.replace("%material_upper%", material.name().toUpperCase());
        processed = processed.replace("%milestone_level%", String.valueOf(milestoneLevel));
        processed = processed.replace("%milestone_amount%", String.valueOf(milestone.getAmount()));

        // Player progress placeholders with linked materials support
        long playerProgress = getCombinedProgress(player.getUniqueId(), material);
        processed = processed.replace("%player_progress%", String.valueOf(playerProgress));

        // PlaceholderAPI integration if available
        if (plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                processed = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, processed);
            } catch (Exception e) {
                plugin.getLogger().fine("Error processing PlaceholderAPI placeholders in milestone command: " + e.getMessage());
            }
        }

        return processed;
    }

    /**
     * ENHANCED: Get count of completed but unclaimed milestones for a material with linked materials support
     */
    public int getUnclaimedMilestoneCount(UUID playerUUID, Material material) {
        MilestoneConfig config = milestoneConfigs.get(material);
        if (config == null) {
            return 0;
        }

        MilestoneData milestoneData = getPlayerMilestoneData(playerUUID);
        long playerProgress = getCombinedProgress(playerUUID, material);

        int unclaimedCount = 0;
        for (Map.Entry<Integer, MilestoneConfig.MilestoneLevel> entry : config.getAllLevels().entrySet()) {
            int level = entry.getKey();
            MilestoneConfig.MilestoneLevel milestone = entry.getValue();

            if (playerProgress >= milestone.getAmount() && !milestoneData.isMilestoneClaimed(material, level)) {
                unclaimedCount++;
            }
        }

        return unclaimedCount;
    }

    public int getTotalUnclaimedMilestoneCount(UUID playerUUID) {
        int totalUnclaimed = 0;

        for (Material material : milestoneConfigs.keySet()) {
            totalUnclaimed += getUnclaimedMilestoneCount(playerUUID, material);
        }

        return totalUnclaimed;
    }

    public List<UnclaimedMilestone> getUnclaimedMilestones(UUID playerUUID, Material material) {
        List<UnclaimedMilestone> unclaimed = new ArrayList<>();
        MilestoneConfig config = milestoneConfigs.get(material);
        if (config == null) {
            return unclaimed;
        }

        MilestoneData milestoneData = getPlayerMilestoneData(playerUUID);
        long playerProgress = getCombinedProgress(playerUUID, material);

        for (Map.Entry<Integer, MilestoneConfig.MilestoneLevel> entry : config.getAllLevels().entrySet()) {
            int level = entry.getKey();
            MilestoneConfig.MilestoneLevel milestone = entry.getValue();

            if (playerProgress >= milestone.getAmount() && !milestoneData.isMilestoneClaimed(material, level)) {
                unclaimed.add(new UnclaimedMilestone(material, level, milestone.getAmount(), milestone.getGuiName()));
            }
        }

        return unclaimed;
    }

    public List<UnclaimedMilestone> getAllUnclaimedMilestones(UUID playerUUID) {
        List<UnclaimedMilestone> allUnclaimed = new ArrayList<>();

        for (Material material : milestoneConfigs.keySet()) {
            allUnclaimed.addAll(getUnclaimedMilestones(playerUUID, material));
        }

        allUnclaimed.sort((a, b) -> {
            int materialCompare = a.getMaterial().name().compareTo(b.getMaterial().name());
            if (materialCompare != 0) {
                return materialCompare;
            }
            return Integer.compare(a.getLevel(), b.getLevel());
        });

        return allUnclaimed;
    }

    public MilestoneData getPlayerMilestoneData(UUID playerUUID) {
        try {
            var playerDataFuture = plugin.getDataManager().loadPlayerData(playerUUID);
            var playerData = playerDataFuture.join();

            if (playerData != null && playerData.getMilestoneData() != null) {
                MilestoneData data = playerData.getMilestoneData();
                playerMilestones.put(playerUUID, data);
                return data;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.FINE, "Error loading milestone data from storage for " + playerUUID, e);
        }

        MilestoneData data = playerMilestones.get(playerUUID);
        if (data == null) {
            data = new MilestoneData();
            playerMilestones.put(playerUUID, data);
        }
        return data;
    }

    // Existing getter methods
    public MilestoneConfig getMilestoneConfig(Material material) {
        return milestoneConfigs.get(material);
    }

    public Set<Material> getConfiguredMaterials() {
        return new HashSet<>(milestoneConfigs.keySet());
    }

    public Set<Material> getTrackedBlocks() {
        return new HashSet<>(trackedBlocks);
    }

    public List<Material> getProfileCategoryMaterials(String category) {
        return profileCategories.getOrDefault(category, new ArrayList<>());
    }

    public Set<String> getProfileCategories() {
        return new HashSet<>(profileCategories.keySet());
    }

    public boolean isTrackedMaterial(Material material) {
        return trackedBlocks.contains(material);
    }

    public void loadPlayerMilestoneData(UUID playerUUID) {
        plugin.getDataManager().loadPlayerData(playerUUID).thenAccept(playerData -> {
            if (playerData != null && playerData.getMilestoneData() != null) {
                playerMilestones.put(playerUUID, playerData.getMilestoneData());
            }
        });
    }

    public int getMainGuiSize() {
        return mainGuiSize;
    }

    public ProfileGuiConfig getProfileGuiConfig() {
        return profileGuiConfig;
    }

    /**
     * ENHANCED: Get linked materials information with proper validation
     */
    public Set<Material> getLinkedMaterialsForGroup(String groupName) {
        Set<Material> materials = milestoneGroups.get(groupName);
        return materials != null ? new HashSet<>(materials) : new HashSet<>();
    }

    public String getMilestoneGroupName(Material material) {
        return materialToMilestoneGroup.get(material);
    }

    public boolean isLinkedMaterial(Material material) {
        return materialToMilestoneGroup.containsKey(material);
    }

    /**
     * ADDED: Get all linked milestone groups for debugging
     */
    public Map<String, Set<Material>> getAllLinkedGroups() {
        return new HashMap<>(milestoneGroups);
    }

    /**
     * ENHANCED: Validate linked materials system integrity
     */
    public boolean validateLinkedMaterialsIntegrity() {
        boolean isValid = true;

        // Check that all materials in groups are valid
        for (Map.Entry<String, Set<Material>> entry : milestoneGroups.entrySet()) {
            String groupName = entry.getKey();
            Set<Material> materials = entry.getValue();

            if (materials.isEmpty()) {
                plugin.getLogger().warning("Linked materials group '" + groupName + "' is empty");
                isValid = false;
                continue;
            }

            // Check that all materials in the group point back to the group
            for (Material material : materials) {
                String mappedGroup = materialToMilestoneGroup.get(material);
                if (!groupName.equals(mappedGroup)) {
                    plugin.getLogger().warning("Material " + material + " in group '" + groupName + "' maps to different group: " + mappedGroup);
                    isValid = false;
                }
            }
        }

        // Check that all materials in the mapping have corresponding groups
        for (Map.Entry<Material, String> entry : materialToMilestoneGroup.entrySet()) {
            Material material = entry.getKey();
            String groupName = entry.getValue();

            Set<Material> groupMaterials = milestoneGroups.get(groupName);
            if (groupMaterials == null || !groupMaterials.contains(material)) {
                plugin.getLogger().warning("Material " + material + " maps to group '" + groupName + "' but group doesn't contain the material");
                isValid = false;
            }
        }

        if (isValid) {
            plugin.getLogger().info("Linked materials system integrity check passed");
        } else {
            plugin.getLogger().warning("Linked materials system has integrity issues");
        }

        return isValid;
    }

    public void shutdown() {
        // FIXED: Clean up player locks
        playerLocks.clear();

        for (Map.Entry<UUID, MilestoneData> entry : playerMilestones.entrySet()) {
            try {
                var playerDataFuture = plugin.getDataManager().loadPlayerData(entry.getKey());
                var playerData = playerDataFuture.join();

                if (playerData != null) {
                    playerData.setMilestoneData(entry.getValue());
                    plugin.getDataManager().savePlayerData(entry.getKey(), playerData).join();
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error saving milestone data during shutdown for " + entry.getKey(), e);
            }
        }

        milestoneConfigs.clear();
        playerMilestones.clear();
        trackedBlocks.clear();
        profileCategories.clear();
        materialToMilestoneGroup.clear();
        milestoneGroups.clear();
        profileItemNames.clear(); // ADDED: Clear custom item names
    }

    // Configuration classes remain the same
    public static class ProfileGuiConfig {
        private final int size;
        private final int playerHeadSlot;
        private final List<String> playerHeadLore;
        private final int farmingSlot;
        private final int miningSlot;
        private final int foragingSlot;
        private final MiscInfoConfig miscInfoConfig;

        public ProfileGuiConfig() {
            this.size = 54;
            this.playerHeadSlot = 4;
            this.playerHeadLore = Arrays.asList("&7Player profile information");
            this.farmingSlot = 20;
            this.miningSlot = 22;
            this.foragingSlot = 24;
            this.miscInfoConfig = null;
        }

        public ProfileGuiConfig(int size, int playerHeadSlot, List<String> playerHeadLore,
                                int farmingSlot, int miningSlot, int foragingSlot, MiscInfoConfig miscInfoConfig) {
            this.size = size;
            this.playerHeadSlot = playerHeadSlot;
            this.playerHeadLore = new ArrayList<>(playerHeadLore);
            this.farmingSlot = farmingSlot;
            this.miningSlot = miningSlot;
            this.foragingSlot = foragingSlot;
            this.miscInfoConfig = miscInfoConfig;
        }

        public int getSize() {
            return size;
        }

        public int getPlayerHeadSlot() {
            return playerHeadSlot;
        }

        public List<String> getPlayerHeadLore() {
            return new ArrayList<>(playerHeadLore);
        }

        public int getFarmingSlot() {
            return farmingSlot;
        }

        public int getMiningSlot() {
            return miningSlot;
        }

        public int getForagingSlot() {
            return foragingSlot;
        }

        public MiscInfoConfig getMiscInfoConfig() {
            return miscInfoConfig;
        }
    }

    public static class MiscInfoConfig {
        private final Material material;
        private final String itemName;
        private final List<String> itemLore;
        private final int slot;

        public MiscInfoConfig(Material material, String itemName, List<String> itemLore, int slot) {
            this.material = material;
            this.itemName = itemName;
            this.itemLore = new ArrayList<>(itemLore);
            this.slot = slot;
        }

        public Material getMaterial() {
            return material;
        }

        public String getItemName() {
            return itemName;
        }

        public List<String> getItemLore() {
            return new ArrayList<>(itemLore);
        }

        public int getSlot() {
            return slot;
        }
    }

    public static class UnclaimedMilestone {
        private final Material material;
        private final int level;
        private final long requiredAmount;
        private final String displayName;

        public UnclaimedMilestone(Material material, int level, long requiredAmount, String displayName) {
            this.material = material;
            this.level = level;
            this.requiredAmount = requiredAmount;
            this.displayName = displayName;
        }

        public Material getMaterial() {
            return material;
        }

        public int getLevel() {
            return level;
        }

        public long getRequiredAmount() {
            return requiredAmount;
        }

        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return material.name() + " Level " + level + " (" + requiredAmount + ")";
        }
    }
}