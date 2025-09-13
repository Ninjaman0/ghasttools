package com.ghasttools.listeners;

import com.ghasttools.GhastToolsPlugin;
import com.ghasttools.data.PlayerData;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * FIXED: Block breaking events with proper milestone tracking and validation
 */
public class BlockBreakListener implements Listener {

    private final GhastToolsPlugin plugin;

    public BlockBreakListener(GhastToolsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }

        final Player player = event.getPlayer();
        final ItemStack tool = player.getInventory().getItemInMainHand();
        final Block block = event.getBlock();
        final Material originalBlockType = block.getType();

        if (player == null || tool == null || block == null || originalBlockType == Material.AIR) {
            return;
        }

        if (!plugin.getToolManager().isGhastTool(tool)) {
            return;
        }

        final String toolType = plugin.getToolManager().getToolType(tool);
        final int toolTier = plugin.getToolManager().getToolTier(tool);

        if (toolType == null || toolTier <= 0) {
            return;
        }

        if (!plugin.getToolManager().canUseToolType(player, toolType)) {
            plugin.getMessageUtil().sendMessage(player, "no_permission_tool",
                    Map.of("tool", toolType));
            event.setCancelled(true);
            return;
        }

        // FIXED: Comprehensive validation before allowing block break
        if (!validateComprehensiveToolUsage(player, toolType, toolTier, originalBlockType, block)) {
            event.setCancelled(true);
            return;
        }

        // FIXED: Process block breaking with proper milestone tracking
        processBlockBreaking(player, originalBlockType, tool, toolType, event);
    }

    /**
     * FIXED: Comprehensive validation for tool usage including level, WorldGuard, and milestone requirements
     */
    private boolean validateComprehensiveToolUsage(Player player, String toolType, int toolTier,
                                                   Material blockType, Block block) {
        // 1. Check level requirement for tool tier
        if (!player.hasPermission("ghasttools.bypass.levelcheck")) {
            if (plugin.getLevelsHandler() != null) {
                int requiredLevel = getRequiredLevelForTool(toolType, toolTier);

                if (!plugin.getLevelsHandler().meetsLevelRequirement(player, requiredLevel)) {
                    int playerLevel = plugin.getLevelsHandler().getPlayerLevel(player);
                    player.sendMessage("§cYou cannot break blocks with this Tool!");
                    player.sendMessage("§7Required level: " + requiredLevel + "   | Your level: " + playerLevel);
                    return false;
                }
            }
        }

        // 2. Check block level requirements
        if (!meetsBlockLevelRequirement(player, blockType)) {
            return false;
        }

        // 3. Check WorldGuard permissions for specific block location
        if (plugin.getWorldGuardHook() != null) {
            if (!plugin.getWorldGuardHook().canUseTools(player, block.getLocation())) {
                plugin.getMessageUtil().sendMessage(player, "worldguard_deny");
                return false;
            }
        }

        // 4. FIXED: For milestone materials, ensure player can actually break this specific block
        if (plugin.getMilestoneManager() != null &&
                plugin.getMilestoneManager().isTrackedMaterial(blockType)) {

            // Additional validation for milestone materials
            if (!canPlayerBreakMilestoneBlock(player, blockType, block)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Check if player meets block level requirement for specific block type
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
                player.sendMessage("§cYou need level " + requiredLevel + " to break " + 
                                 formatBlockName(blockType) + "!");
                player.sendMessage("§7Your level: " + playerLevel);
                return false;
            }

            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error checking block level requirement", e);
            return true; // Default to allow on error
        }
    }

    /**
     * Format block name for display
     */
    private String formatBlockName(Material material) {
        if (material == null) return "Unknown";
        
        String name = material.name().toLowerCase().replace("_", " ");
        String[] words = name.split(" ");
        StringBuilder formatted = new StringBuilder();
        
        for (int i = 0; i < words.length; i++) {
            if (i > 0) formatted.append(" ");
            formatted.append(Character.toUpperCase(words[i].charAt(0)))
                     .append(words[i].substring(1));
        }
        
        return formatted.toString();
    }

    /**
     * FIXED: Check if player can break this specific milestone block
     */
    private boolean canPlayerBreakMilestoneBlock(Player player, Material blockType, Block block) {
        // Check if player is in valid WorldGuard region for this block
        if (plugin.getWorldGuardHook() != null) {
            if (!plugin.getWorldGuardHook().canUseTools(player, block.getLocation())) {
                return false;
            }
        }

        // Check if player's tool level allows breaking this block type
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool == null || !plugin.getToolManager().isGhastTool(tool)) {
            return false;
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

        return true;
    }

    /**
     * FIXED: Get required level for tool tier
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
        return 1;
    }

    /**
     * FIXED: Process block breaking with proper milestone tracking and data saving
     */
    private void processBlockBreaking(Player player, Material originalBlockType, ItemStack tool, String toolType, BlockBreakEvent event) {
        CompletableFuture<PlayerData> playerDataFuture = plugin.getDataManager().loadPlayerData(player.getUniqueId());

        playerDataFuture.thenAccept(playerData -> {
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try {
                        playerData.addBlocksBroken(1);
                        playerData.incrementToolUsage(toolType);
                        playerData.updateLastSeen();

                        // FIXED: Track milestone FIRST by adding to PlayerData
                        if (plugin.getMilestoneManager() != null &&
                                plugin.getMilestoneManager().isTrackedMaterial(originalBlockType)) {

                            // Add to PlayerData first (this saves to database)
                            playerData.addMilestoneBlocksBroken(originalBlockType, 1);


                        }

                        // Process enchantments (excluding haste which is passive)
                        Map<String, Integer> enchantments = plugin.getToolManager().getToolEnchantments(tool);
                        for (String enchantment : enchantments.keySet()) {
                            if (!enchantment.equals("haste")) {
                                plugin.getEnchantmentManager().triggerEnchantment(player, tool, enchantment, event);
                            }
                        }

                        // FIXED: Save player data first, then trigger milestone tracking
                        plugin.getDataManager().savePlayerData(player.getUniqueId(), playerData).thenRun(() -> {
                            // FIXED: Track with milestone manager AFTER data is saved
                            if (plugin.getMilestoneManager() != null &&
                                    plugin.getMilestoneManager().isTrackedMaterial(originalBlockType)) {
                                plugin.getMilestoneManager().trackBlockBreak(player, originalBlockType, 1);
                            }
                        }).exceptionally(throwable -> {
                            plugin.getLogger().log(Level.WARNING, "Failed to save player data for " + player.getName(), throwable);
                            return null;
                        });

                    } catch (Exception e) {
                        plugin.getLogger().log(Level.SEVERE, "Error processing block break event for player " + player.getName(), e);
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error in block break async processing for player " + player.getName(), e);
            }
        }).exceptionally(throwable -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to load player data for " + player.getName(), throwable);
            return null;
        });
    }
}