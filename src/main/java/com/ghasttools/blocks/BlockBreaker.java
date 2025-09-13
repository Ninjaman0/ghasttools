package com.ghasttools.blocks;

import com.ghasttools.GhastToolsPlugin;
import com.ghasttools.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * FIXED: Block breaking with proper validation and milestone tracking that saves to database
 */
public class BlockBreaker {

    private final GhastToolsPlugin plugin;

    // Configuration constants to avoid magic numbers
    private static final int DEFAULT_MAX_BLOCKS = 500;
    private static final int DEFAULT_EXPLOSION_RADIUS = 5;
    private static final int MINECRAFT_MAX_STACK_SIZE = 64;

    public BlockBreaker(GhastToolsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * FIXED: Block breaking with comprehensive validation and proper milestone tracking
     */
    public CompletableFuture<Void> breakBlocksInRadius(Player player, Location center, int radius, String enchantmentType) {
        return CompletableFuture.runAsync(() -> {
            // Check if plugin is shutting down
            if (plugin.isShuttingDown()) {
                return;
            }

            // Validate input parameters
            if (player == null || center == null || center.getWorld() == null || radius <= 0) {
                plugin.getLogger().warning("Invalid parameters for block breaking");
                return;
            }

            // FIXED: Comprehensive validation before processing
            if (!validateAllBlockBreakingConditions(player, center, radius)) {
                return;
            }

            // FIXED: Use PERFECT SPHERE calculation for all explosion enchantments
            Set<Block> blocksToBreak = getBlocksInPerfectSphere(center, radius);

            // Apply max block limit
            int maxBlocks = getMaxBlocks();
            if (blocksToBreak.size() > maxBlocks) {
                blocksToBreak = limitBlocks(blocksToBreak, maxBlocks);
            }

            // FIXED: Filter blocks with comprehensive validation
            final Set<Block> finalBlocksToBreak = filterBlocksWithComprehensiveValidation(blocksToBreak, player);

            if (finalBlocksToBreak.isEmpty()) {
                plugin.getLogger().fine("No valid blocks to break for " + player.getName());
                return;
            }

            // Check if this is an explosion enchantment
            final boolean isExplosionEnchantment = enchantmentType != null && (
                    enchantmentType.equals("explosive") ||
                            enchantmentType.equals("meteor") ||
                            enchantmentType.equals("airstrike")
            );

            // Load player data
            PlayerData playerData;
            try {
                playerData = plugin.getDataManager().loadPlayerData(player.getUniqueId()).join();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load player data for " + player.getName(), e);
                return;
            }

            // Variables for tracking rewards and milestones
            final double[] totalXp = {0};
            final double[] totalEssence = {0};
            final int[] blocksDestroyed = {0};
            final Map<Material, Integer> brokenBlocks = new HashMap<>();
            final Map<Material, Long> milestoneBlocks = new HashMap<>(); // ADDED: Track milestone blocks

            // Get tool enchantments to check for boost enchantments
            ItemStack tool = getCurrentHeldTool(player);
            Map<String, Integer> enchantments = tool != null ?
                    plugin.getToolManager().getToolEnchantments(tool) : new HashMap<>();

            // Break blocks on main thread with proper error handling
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    // FIXED: Store original blocks with their Y-coordinates for proper regeneration
                    Map<String, BlockData> originalBlocks = new HashMap<>();

                    for (Block block : finalBlocksToBreak) {
                        if (plugin.isShuttingDown()) break;

                        // FIXED: Validate each block individually with level and region checks
                        if (canBreakBlockWithAllValidation(player, block)) {
                            Material originalType = block.getType();

                            // FIXED: Store block data with full coordinates (including Y level)
                            String blockKey = block.getX() + "," + block.getY() + "," + block.getZ();
                            originalBlocks.put(blockKey, new BlockData(block.getLocation().clone(), originalType, block.getBlockData().clone()));

                            // Calculate rewards BEFORE breaking the block
                            double xp = getXpReward(originalType);
                            double essence = getEssenceReward(originalType);

                            // Apply boost multipliers if tool has boost enchantments
                            int xpBoostLevel = enchantments.getOrDefault("xpboost", 0);
                            if (xpBoostLevel > 0 && xp > 0) {
                                var xpConfig = plugin.getEnchantmentManager().getEnchantmentConfig("xpboost");
                                if (xpConfig != null && xpConfig.getMultiplier() != null && xpConfig.getMultiplier().size() >= xpBoostLevel) {
                                    double multiplier = xpConfig.getMultiplier().get(xpBoostLevel - 1);
                                    xp *= multiplier;
                                }
                            }

                            int essenceBoostLevel = enchantments.getOrDefault("essenceboost", 0);
                            if (essenceBoostLevel > 0 && essence > 0) {
                                var essenceConfig = plugin.getEnchantmentManager().getEnchantmentConfig("essenceboost");
                                if (essenceConfig != null && essenceConfig.getMultiplier() != null && essenceConfig.getMultiplier().size() >= essenceBoostLevel) {
                                    double multiplier = essenceConfig.getMultiplier().get(essenceBoostLevel - 1);
                                    essence *= multiplier;
                                }
                            }

                            // Track broken blocks for explosion rewards BEFORE breaking
                            brokenBlocks.merge(originalType, 1, Integer::sum);

                            // FIXED: Track milestone blocks if material is tracked AND player can break it
                            if (plugin.getMilestoneManager() != null &&
                                    plugin.getMilestoneManager().isTrackedMaterial(originalType)) {
                                milestoneBlocks.merge(originalType, 1L, Long::sum);
                            }

                            // FIXED: Check if block should regenerate
                            boolean willRegenerate = plugin.getBlockRegenerationManager() != null &&
                                    plugin.getBlockRegenerationManager().isRegenerationBlock(originalType);

                            if (willRegenerate) {
                                // Let regeneration manager handle the block breaking and regeneration
                                plugin.getBlockRegenerationManager().handleBlockBreak(block, enchantmentType);
                            } else {
                                // Normal block breaking without regeneration
                                breakBlockSafely(block);
                            }

                            // Update totals
                            totalXp[0] += xp;
                            totalEssence[0] += essence;
                            blocksDestroyed[0]++;
                        }
                    }

                    // FIXED: Properly save milestone blocks to database through PlayerData
                    if (!milestoneBlocks.isEmpty() && plugin.getMilestoneManager() != null) {
                        for (Map.Entry<Material, Long> entry : milestoneBlocks.entrySet()) {
                            Material material = entry.getKey();
                            Long amount = entry.getValue();

                            // FIXED: Add to PlayerData first (this saves to database)
                            playerData.addMilestoneBlocksBroken(material, amount);

                            plugin.getLogger().fine("Enchantment added " + amount + " " + material.name() + " to PlayerData for " + player.getName());
                        }

                        // FIXED: Save player data first, then trigger milestone manager
                        plugin.getDataManager().savePlayerData(player.getUniqueId(), playerData).thenRun(() -> {
                            // FIXED: Track with milestone manager AFTER data is saved
                            for (Map.Entry<Material, Long> entry : milestoneBlocks.entrySet()) {
                                Material material = entry.getKey();
                                Long amount = entry.getValue();
                                plugin.getMilestoneManager().trackBlockBreak(player, material, amount);
                            }
                        });
                    }

                    // Always give rewards and explosion blocks for explosion enchantments
                    if (blocksDestroyed[0] > 0) {
                        // Give XP and Essence rewards for explosion enchantments OR boost enchantments using COMMANDS
                        boolean hasXpBoost = enchantments.getOrDefault("xpboost", 0) > 0;
                        boolean hasEssenceBoost = enchantments.getOrDefault("essenceboost", 0) > 0;

                        if (isExplosionEnchantment || hasXpBoost || hasEssenceBoost) {
                            updatePlayerRewardsWithCommands(player, playerData, totalXp[0], totalEssence[0],
                                    blocksDestroyed[0], enchantmentType, enchantments, isExplosionEnchantment);
                        }

                        // Check inventory space BEFORE giving explosion block rewards
                        if (isExplosionEnchantment && !brokenBlocks.isEmpty()) {
                            boolean rewardsGiven = giveExplosionBlockRewards(player, brokenBlocks);
                            if (rewardsGiven) {
                                int totalBlocksGiven = brokenBlocks.values().stream().mapToInt(Integer::intValue).sum();
                            }
                        }
                    }

                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Error during block breaking", e);
                }
            });

        }, plugin.getAsyncExecutor());
    }

    /**
     * FIXED: Comprehensive validation for all block breaking conditions
     */
    private boolean validateAllBlockBreakingConditions(Player player, Location center, int radius) {
        // 1. Check if player is holding a GhastTool
        ItemStack tool = getCurrentHeldTool(player);
        if (tool == null || !plugin.getToolManager().isGhastTool(tool)) {
            return false;
        }

        // 2. Check level requirement for the tool
        String toolType = plugin.getToolManager().getToolType(tool);
        int toolTier = plugin.getToolManager().getToolTier(tool);

        if (toolType == null || toolTier <= 0) {

            return false;
        }

        // Check level requirement
        if (!player.hasPermission("ghasttools.bypass.levelcheck")) {
            if (plugin.getLevelsHandler() != null) {
                int requiredLevel = getRequiredLevelForTool(toolType, toolTier);
                if (!plugin.getLevelsHandler().meetsLevelRequirement(player, requiredLevel)) {
                    return false;
                }
            }
        }

        // 3. Check if player is in allowed region (WorldGuard) for the CENTER and RADIUS
        if (!canBreakInArea(player, center, radius)) {
            return false;
        }

        return true;
    }

    /**
     * FIXED: Filter blocks with comprehensive validation including level and region checks
     */
    private Set<Block> filterBlocksWithComprehensiveValidation(Set<Block> blocks, Player player) {
        Set<Block> filtered = new HashSet<>();

        // Get player's tool info for validation
        ItemStack tool = getCurrentHeldTool(player);
        String toolType = plugin.getToolManager().getToolType(tool);
        int toolTier = plugin.getToolManager().getToolTier(tool);

        for (Block block : blocks) {
            // Add EARLY RETURN if block is air to optimize
            if (block.getType() == Material.AIR) continue;

            // FIXED: Comprehensive validation for each block
            if (canBreakBlockWithAllValidation(player, block)) {
                filtered.add(block);
            }
        }

        return filtered;
    }

    /**
     * FIXED: Comprehensive block validation with ALL checks
     */
    private boolean canBreakBlockWithAllValidation(Player player, Block block) {
        if (block == null || block.getType() == Material.AIR) {
            return false;
        }

        // 1. Check whitelist FIRST
        if (!isBlockAllowedByWhitelist(block.getType())) {
            return false;
        }

        // 2. Check if block is in blacklist
        if (isBlockBlacklisted(block.getType())) {
            return false;
        }

        // 3. Check block level requirements
        if (!meetsBlockLevelRequirement(player, block.getType())) {
            return false;
        }

        // 4. Check WorldGuard for specific block location
        if (plugin.getWorldGuardHook() != null) {
            if (!plugin.getWorldGuardHook().canUseTools(player, block.getLocation())) {
                return false;
            }
        }

        // 5. Check if player can break this block type based on tool level
        if (!canPlayerBreakBlockType(player, block.getType())) {
            return false;
        }

        // 6. Check if block can be broken by player (vanilla permissions)
        return !block.getType().isAir() && block.getType().getHardness() >= 0;
    }

    /**
     * Check if player meets block level requirement
     */
    private boolean meetsBlockLevelRequirement(Player player, Material blockType) {
        try {
            // Check if player has bypass permission
            if (player.hasPermission("ghasttools.bypass.levelcheck")) {
                return true;
            }

            FileConfiguration config = plugin.getConfigManager().getMainConfig();
            if (config == null) {
                return true; // Allow if config not available
            }

            ConfigurationSection blockLevelSection = config.getConfigurationSection("block-level-requirements");
            if (blockLevelSection == null) {
                return true; // No block level requirements configured
            }

            String blockKey = blockType.name().toLowerCase();
            if (!blockLevelSection.contains(blockKey)) {
                return true; // Block not in requirements list
            }

            int requiredLevel = blockLevelSection.getInt(blockKey, 0);
            if (requiredLevel <= 0) {
                return true; // No level requirement
            }

            // Check player level using levels handler
            if (plugin.getLevelsHandler() == null) {
                plugin.getLogger().warning("Levels handler not available for block level check");
                return true; // Allow if levels handler not available
            }

            int playerLevel = plugin.getLevelsHandler().getPlayerLevel(player);
            if (playerLevel < requiredLevel) {
                plugin.getLogger().fine("Player " + player.getName() + " level " + playerLevel + 
                                      " insufficient for " + blockType + " (requires " + requiredLevel + ")");
                return false;
            }

            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error checking block level requirement", e);
            return true; // Default to allow on error
        }
    }

    /**
     * FIXED: Check if player can break this specific block type based on their tool level
     */
    private boolean canPlayerBreakBlockType(Player player, Material blockType) {
        // Get player's current tool
        ItemStack tool = getCurrentHeldTool(player);
        if (tool == null || !plugin.getToolManager().isGhastTool(tool)) {
            return false;
        }

        String toolType = plugin.getToolManager().getToolType(tool);
        int toolTier = plugin.getToolManager().getToolTier(tool);

        if (toolType == null || toolTier <= 0) {
            return false;
        }

        // Check if player can use this tool tier (level requirement)
        if (!plugin.getToolManager().canUseToolTier(player, toolType, toolTier)) {
            return false;
        }

        // FIXED: For milestone materials, check if player can actually break this material
        // This prevents tracking blocks that player cannot legitimately break
        if (plugin.getMilestoneManager() != null &&
                plugin.getMilestoneManager().isTrackedMaterial(blockType)) {

            // If it's a tracked milestone material, validate more strictly
            if (plugin.getLevelsHandler() != null && !player.hasPermission("ghasttools.bypass.levelcheck")) {
                int requiredLevel = getRequiredLevelForTool(toolType, toolTier);
                if (!plugin.getLevelsHandler().meetsLevelRequirement(player, requiredLevel)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * ADDED: Get required level for tool tier
     */
    private int getRequiredLevelForTool(String toolType, int toolTier) {
        try {
            var toolConfig = plugin.getToolManager().getToolConfigs().get(toolType);
            if (toolConfig != null) {
                var tierConfig = toolConfig.getTier(toolTier);
                if (tierConfig != null) {
                    return tierConfig.getLevelRequirement();
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Error getting level requirement: " + e.getMessage());
        }
        return 1; // Default level requirement
    }

    /**
     * FIXED: Get blocks in PERFECT SPHERE shape - improved algorithm for true sphere
     */
    private Set<Block> getBlocksInPerfectSphere(Location center, int radius) {
        Set<Block> blocks = new HashSet<>();

        // Use double precision for perfect sphere calculation
        double radiusSquared = radius * radius;
        double centerX = center.getX();
        double centerY = center.getY();
        double centerZ = center.getZ();

        // FIXED: Iterate through all possible positions in a cube around the center
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    // FIXED: Calculate EXACT distance using double precision and proper center coordinates
                    double dx = x;
                    double dy = y;
                    double dz = z;
                    double distanceSquared = dx * dx + dy * dy + dz * dz;

                    // Only include blocks within the PERFECT SPHERE
                    if (distanceSquared <= radiusSquared) {
                        // FIXED: Use proper block coordinates with center offset
                        int blockX = (int) Math.floor(centerX) + x;
                        int blockY = (int) Math.floor(centerY) + y;
                        int blockZ = (int) Math.floor(centerZ) + z;

                        Location blockLoc = new Location(center.getWorld(), blockX, blockY, blockZ);

                        // Ensure block is within world bounds
                        if (blockLoc.getY() >= center.getWorld().getMinHeight() &&
                                blockLoc.getY() <= center.getWorld().getMaxHeight()) {
                            Block block = blockLoc.getBlock();
                            if (block.getType() != Material.AIR) {
                                blocks.add(block);
                            }
                        }
                    }
                }
            }
        }

        return blocks;
    }

    /**
     * Command-based reward system that properly handles explosion enchantments and boost enchantments
     */
    private void updatePlayerRewardsWithCommands(Player player, PlayerData playerData, double totalXp,
                                                 double totalEssence, int blocksDestroyed, String enchantmentType,
                                                 Map<String, Integer> enchantments, boolean isExplosionEnchantment) {
        try {
            // Update player data
            playerData.addXpEarned(totalXp);
            playerData.addEssenceEarned(totalEssence);
            playerData.addBlocksBroken(blocksDestroyed);

            // Check boost enchantments
            boolean hasXpBoost = enchantments.getOrDefault("xpboost", 0) > 0;
            boolean hasEssenceBoost = enchantments.getOrDefault("essenceboost", 0) > 0;

            // Give XP for explosion enchantments OR boost enchantments using COMMANDS
            if ((isExplosionEnchantment || hasXpBoost) && totalXp > 0) {
                giveXpWithCommand(player, Math.round(totalXp));
            }

            // Give Essence for explosion enchantments OR boost enchantments using COMMANDS
            if ((isExplosionEnchantment || hasEssenceBoost) && totalEssence > 0) {
                giveEssenceWithCommand(player, Math.round(totalEssence));
            }

            // Save player data asynchronously
            plugin.getDataManager().savePlayerData(player.getUniqueId(), playerData)
                    .exceptionally(throwable -> {
                        plugin.getLogger().log(Level.WARNING, "Failed to save player data for " + player.getName(), throwable);
                        return null;
                    });

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error updating player rewards", e);
        }
    }

    /**
     * Give XP using command from rewards.yml
     */
    private void giveXpWithCommand(Player player, long amount) {
        try {
            FileConfiguration rewardsConfig = plugin.getConfigManager().getRewardsConfig();
            if (rewardsConfig == null) {
                plugin.getLogger().warning("Rewards config not available for XP command!");
                return;
            }

            String xpCommand = rewardsConfig.getString("rewards.xp.command", "levels give {player} {amount}");

            if (xpCommand == null || xpCommand.isEmpty()) {
                plugin.getLogger().warning("XP command not configured in rewards.yml!");
                return;
            }

            // Replace placeholders
            String finalCommand = xpCommand
                    .replace("{player}", player.getName())
                    .replace("{amount}", String.valueOf(amount));

            // Execute command on main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
                    if (!success) {
                        plugin.getLogger().warning("Failed to execute XP command: " + finalCommand);
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Error executing XP command: " + finalCommand, e);
                }
            });

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error giving XP with command to " + player.getName(), e);
        }
    }

    /**
     * Give Essence using command from rewards.yml
     */
    private void giveEssenceWithCommand(Player player, long amount) {
        try {
            FileConfiguration rewardsConfig = plugin.getConfigManager().getRewardsConfig();
            if (rewardsConfig == null) {
                plugin.getLogger().warning("Rewards config not available for Essence command!");
                return;
            }

            String essenceCommand = rewardsConfig.getString("rewards.essence.command", "essence give {player} {amount}");

            if (essenceCommand == null || essenceCommand.isEmpty()) {
                plugin.getLogger().warning("Essence command not configured in rewards.yml!");
                return;
            }

            // Replace placeholders
            String finalCommand = essenceCommand
                    .replace("{player}", player.getName())
                    .replace("{amount}", String.valueOf(amount));

            // Execute command on main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
                    if (!success) {
                        plugin.getLogger().warning("Failed to execute Essence command: " + finalCommand);
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Error executing Essence command: " + finalCommand, e);
                }
            });

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error giving Essence with command to " + player.getName(), e);
        }
    }

    /**
     * Explosion block rewards with inventory space check
     */
    private boolean giveExplosionBlockRewards(Player player, Map<Material, Integer> brokenBlocks) {
        try {
            FileConfiguration config = plugin.getConfigManager().getMainConfig();
            if (config == null) {
                plugin.getLogger().warning("Main config not available for explosion rewards!");
                return false;
            }

            ConfigurationSection explosionBlocks = config.getConfigurationSection("explosion-blocks");
            if (explosionBlocks == null) {
                plugin.getLogger().warning("No explosion-blocks section found in config!");
                return false;
            }

            // Calculate all reward items first
            List<ItemStack> rewardItems = new ArrayList<>();

            for (Map.Entry<Material, Integer> entry : brokenBlocks.entrySet()) {
                Material material = entry.getKey();
                int amount = entry.getValue();

                String materialKey = material.name().toLowerCase();
                ConfigurationSection blockConfig = explosionBlocks.getConfigurationSection(materialKey);

                if (blockConfig != null) {
                    // Get reward configuration
                    String itemName = blockConfig.getString("item-name", "");
                    String rewardMaterial = blockConfig.getString("material", material.name());
                    List<String> lore = blockConfig.getStringList("lore");
                    int customModelData = blockConfig.getInt("custom-model-data", 0);

                    // Create reward item
                    Material rewardMat;
                    try {
                        rewardMat = Material.valueOf(rewardMaterial.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        rewardMat = material; // Fallback to original material
                        plugin.getLogger().warning("Invalid reward material: " + rewardMaterial + ", using: " + material.name());
                    }

                    // Split into proper stacks respecting Minecraft's max stack size
                    List<ItemStack> stacks = createProperStacks(rewardMat, amount, itemName, lore, customModelData);
                    rewardItems.addAll(stacks);
                }
            }

            if (rewardItems.isEmpty()) {
                return false;
            }

            // Check if player has enough inventory space
            if (!hasInventorySpace(player, rewardItems)) {
                return false;
            }

            // Give all items to player
            PlayerInventory inventory = player.getInventory();
            for (ItemStack item : rewardItems) {
                inventory.addItem(item);
            }

            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error giving explosion block rewards to " + player.getName(), e);
            return false;
        }
    }

    /**
     * Proper whitelist checking functionality
     */
    private boolean isBlockAllowedByWhitelist(Material material) {
        try {
            FileConfiguration config = plugin.getConfigManager().getMainConfig();
            if (config == null) {
                return true; // Allow if config not available
            }

            boolean whitelistEnabled = config.getBoolean("explosion.block_whitelist.enabled", false);

            if (!whitelistEnabled) {
                return true; // Whitelist disabled, allow all blocks (except blacklisted)
            }

            List<String> whitelist = config.getStringList("explosion.block_whitelist.blocks");

            if (whitelist.isEmpty()) {
                return true; // Empty whitelist means allow all
            }

            String materialName = material.name().toLowerCase();
            return whitelist.contains(materialName) || whitelist.contains(material.name().toUpperCase());

        } catch (Exception e) {
            plugin.getLogger().fine("Error checking whitelist: " + e.getMessage());
            return true; // Default to allow on error
        }
    }

    // Helper methods and classes
    private static class BlockData {
        private final Location location;
        private final Material material;
        private final org.bukkit.block.data.BlockData blockData;

        public BlockData(Location location, Material material, org.bukkit.block.data.BlockData blockData) {
            this.location = location;
            this.material = material;
            this.blockData = blockData;
        }

        public Location getLocation() {
            return location;
        }

        public Material getMaterial() {
            return material;
        }

        public org.bukkit.block.data.BlockData getBlockData() {
            return blockData;
        }
    }

    private List<ItemStack> createProperStacks(Material material, int totalAmount, String itemName,
                                               List<String> lore, int customModelData) {
        List<ItemStack> stacks = new ArrayList<>();

        // Get the material's max stack size
        int maxStackSize = Math.min(MINECRAFT_MAX_STACK_SIZE, material.getMaxStackSize());

        // Calculate how many full stacks and remainder
        int fullStacks = totalAmount / maxStackSize;
        int remainder = totalAmount % maxStackSize;

        // Create full stacks
        for (int i = 0; i < fullStacks; i++) {
            ItemStack stack = createRewardItem(material, maxStackSize, itemName, lore, customModelData);
            stacks.add(stack);
        }

        // Create remainder stack if needed
        if (remainder > 0) {
            ItemStack stack = createRewardItem(material, remainder, itemName, lore, customModelData);
            stacks.add(stack);
        }

        return stacks;
    }

    private ItemStack createRewardItem(Material material, int amount, String itemName,
                                       List<String> lore, int customModelData) {
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            if (!itemName.isEmpty()) {
                meta.setDisplayName(translateColors(itemName));
            }

            if (!lore.isEmpty()) {
                List<String> coloredLore = lore.stream()
                        .map(this::translateColors)
                        .collect(java.util.stream.Collectors.toList());
                meta.setLore(coloredLore);
            }

            if (customModelData > 0) {
                meta.setCustomModelData(customModelData);
            }

            item.setItemMeta(meta);
        }

        return item;
    }

    private boolean hasInventorySpace(Player player, List<ItemStack> items) {
        if (items.isEmpty()) return true;

        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents().clone();

        for (ItemStack item : items) {
            if (!canFitItem(contents, item)) {
                return false;
            }
        }

        return true;
    }

    private boolean canFitItem(ItemStack[] contents, ItemStack item) {
        int remaining = item.getAmount();

        // First try to stack with existing items
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] == null) continue;

            if (contents[i].isSimilar(item)) {
                int canAdd = contents[i].getMaxStackSize() - contents[i].getAmount();
                if (canAdd > 0) {
                    int added = Math.min(canAdd, remaining);
                    remaining -= added;
                    contents[i] = contents[i].clone();
                    contents[i].setAmount(contents[i].getAmount() + added);

                    if (remaining <= 0) return true;
                }
            }
        }

        // Then try to use empty slots
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] == null) {
                int canAdd = Math.min(item.getMaxStackSize(), remaining);
                remaining -= canAdd;
                contents[i] = item.clone();
                contents[i].setAmount(canAdd);

                if (remaining <= 0) return true;
            }
        }

        return remaining <= 0;
    }

    // Helper methods remain the same
    private ItemStack getCurrentHeldTool(Player player) {
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

    private boolean canBreakInArea(Player player, Location center, int radius) {
        if (plugin.getWorldGuardHook() != null) {
            if (!plugin.getWorldGuardHook().canUseTools(player, center)) {
                return false;
            }

            for (int i = 0; i < 4; i++) {
                double angle = (Math.PI * 2 * i) / 4;
                Location checkPoint = center.clone().add(
                        Math.cos(angle) * radius,
                        0,
                        Math.sin(angle) * radius
                );

                if (!plugin.getWorldGuardHook().canUseTools(player, checkPoint)) {
                    return false;
                }
            }
        }

        return true;
    }

    private void breakBlockSafely(Block block) {
        try {
            block.setType(Material.AIR, false);

            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!plugin.isShuttingDown()) {
                    updateSurroundingPhysics(block.getLocation());
                }
            }, 1L);

        } catch (Exception e) {
            plugin.getLogger().fine("Error breaking block at " + block.getLocation() + ": " + e.getMessage());
        }
    }

    private void updateSurroundingPhysics(Location location) {
        try {
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x == 0 && y == 0 && z == 0) continue;

                        Location checkLoc = location.clone().add(x, y, z);
                        Block checkBlock = checkLoc.getBlock();

                        if (needsPhysicsUpdate(checkBlock.getType())) {
                            checkBlock.getState().update(true, true);
                        }
                    }
                }
            }

        } catch (Exception e) {
            plugin.getLogger().fine("Error updating block physics: " + e.getMessage());
        }
    }

    private boolean needsPhysicsUpdate(Material material) {
        return material == Material.SAND ||
                material == Material.GRAVEL ||
                material == Material.RED_SAND ||
                material.name().contains("CONCRETE_POWDER") ||
                material == Material.DRAGON_EGG ||
                material == Material.ANVIL ||
                material.name().contains("FALLING");
    }

    private Set<Block> limitBlocks(Set<Block> blocks, int maxBlocks) {
        if (blocks.size() <= maxBlocks) {
            return blocks;
        }

        Set<Block> limited = new HashSet<>();
        int count = 0;

        for (Block block : blocks) {
            if (count >= maxBlocks) break;
            limited.add(block);
            count++;
        }

        return limited;
    }

    private boolean isBlockBlacklisted(Material material) {
        try {
            FileConfiguration config = plugin.getConfigManager().getMainConfig();
            if (config == null) {
                return isDefaultBlacklisted(material);
            }

            List<String> blacklist = config.getStringList("explosion.blacklisted_blocks");

            if (blacklist.isEmpty()) {
                return isDefaultBlacklisted(material);
            }

            return blacklist.contains(material.name().toLowerCase()) || blacklist.contains(material.name().toUpperCase());

        } catch (Exception e) {
            plugin.getLogger().fine("Error checking blacklist: " + e.getMessage());
            return isDefaultBlacklisted(material);
        }
    }

    private boolean isDefaultBlacklisted(Material material) {
        return material == Material.BEDROCK ||
                material == Material.BARRIER ||
                material == Material.END_PORTAL_FRAME ||
                material == Material.SPAWNER ||
                material == Material.COMMAND_BLOCK ||
                material == Material.STRUCTURE_BLOCK ||
                material == Material.JIGSAW ||
                material == Material.LIGHT ||
                material.name().contains("PORTAL");
    }

    private String translateColors(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', text);
    }

    private double getXpReward(Material material) {
        try {
            FileConfiguration config = plugin.getConfigManager().getRewardsConfig();
            if (config != null) {
                return config.getDouble("rewards.xp.materials." + material.name().toLowerCase(), 0.0);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Error getting XP reward: " + e.getMessage());
        }
        return 0.0;
    }

    private double getEssenceReward(Material material) {
        try {
            FileConfiguration config = plugin.getConfigManager().getRewardsConfig();
            if (config != null) {
                return config.getDouble("rewards.essence.materials." + material.name().toLowerCase(), 0.0);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Error getting essence reward: " + e.getMessage());
        }
        return 0.0;
    }

    private int getMaxBlocks() {
        try {
            FileConfiguration config = plugin.getConfigManager().getMainConfig();
            if (config != null) {
                return config.getInt("explosion.max_blocks", DEFAULT_MAX_BLOCKS);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Error getting max blocks config: " + e.getMessage());
        }
        return DEFAULT_MAX_BLOCKS;
    }
}