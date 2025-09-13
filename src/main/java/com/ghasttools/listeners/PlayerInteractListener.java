package com.ghasttools.listeners;

import com.ghasttools.GhastToolsPlugin;
import com.ghasttools.data.PlayerData;
import com.ghasttools.gui.GuiManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * FIXED: Player interaction handling with boost enchantments only triggering on right-click
 */
public class PlayerInteractListener implements Listener {

    private final GhastToolsPlugin plugin;
    private final GuiManager guiManager;
    private final ConcurrentHashMap<Player, Long> rightClickCooldowns = new ConcurrentHashMap<>();

    public PlayerInteractListener(GhastToolsPlugin plugin, GuiManager guiManager) {
        this.plugin = plugin;
        this.guiManager = guiManager;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        final Player player = event.getPlayer();
        final ItemStack item = event.getItem();

        // Enhanced null checks
        if (player == null || item == null) {
            return;
        }

        if (!plugin.getToolManager().isGhastTool(item)) {
            return;
        }

        final String toolType = plugin.getToolManager().getToolType(item);
        final int toolTier = plugin.getToolManager().getToolTier(item);

        if (toolType == null || toolTier <= 0) {
            return;
        }

        try {
            // ENHANCED: Comprehensive tool usage validation with level requirements
            if (!validateComprehensiveToolUsage(player, item, toolType, toolTier)) {
                return;
            }

            // Check WorldGuard restrictions
            if (plugin.getWorldGuardHook() != null &&
                    !plugin.getWorldGuardHook().canUseTools(player, player.getLocation())) {
                plugin.getMessageUtil().sendMessage(player, "worldguard_deny");
                return;
            }

            // Handle right-click to open GUI instead of executing commands
            handleRightClickGui(player, item, toolType);

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error handling player interact event for " + player.getName(), e);
        }
    }

    /**
     * ENHANCED: Comprehensive tool usage validation with all checks
     */
    private boolean validateComprehensiveToolUsage(Player player, ItemStack tool, String toolType, int toolTier) {
        // Basic tool validation
        if (!plugin.getToolManager().validateToolUsage(player, tool)) {
            return false;
        }

        // ENHANCED: Check level requirement through levelshandler
        if (plugin.getLevelsHandler() != null) {
            int requiredLevel = getRequiredLevel(toolType, toolTier);

            if (!plugin.getLevelsHandler().canPerformAction(player, "use this " + toolType + " tier " + toolTier, requiredLevel)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Get required level for tool tier
     */
    private int getRequiredLevel(String toolType, int toolTier) {
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
     * ENHANCED: Block inventory interactions during random win animation
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();

        // Check if player is blocked from inventory interactions (during random win animation)
        if (guiManager.getRandomWinGui() != null &&
                guiManager.getRandomWinGui().isPlayerBlocked(player)) {

            // Only allow closing the GUI
            if (event.getView().getTitle() != null &&
                    guiManager.isRandomWinGui(event.getView().getTitle())) {
                return; // Allow interactions in random win GUI (for closing)
            }

            // Block all other inventory interactions
            event.setCancelled(true);
        }
    }

    /**
     * ENHANCED: Handle right-click to open upgrade GUI with tool locking and level validation
     */
    private void handleRightClickGui(Player player, ItemStack tool, String toolType) {
        try {
            // Check cooldown
            if (isOnRightClickCooldown(player)) {
                long remainingTime = getRemainingCooldown(player);
                player.sendMessage("§cPlease wait " + (remainingTime / 1000) + " seconds before opening the GUI again!");
                return;
            }

            // Set cooldown
            setRightClickCooldown(player);

            // Open upgrade GUI instead of executing commands
            boolean success = guiManager.openUpgradeGui(player, tool);

            if (!success) {
                player.sendMessage("§cFailed to open upgrade menu! Please try again.");
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error in right-click GUI handling for " + player.getName(), e);
        }
    }

    /**
     * Check right-click cooldown
     */
    private boolean isOnRightClickCooldown(Player player) {
        try {
            Long lastRightClick = rightClickCooldowns.get(player);
            if (lastRightClick == null) {
                return false;
            }

            int cooldownSeconds = plugin.getConfigManager().getMainConfig().getInt("right_click_commands.cooldown", 1);
            long cooldownMs = cooldownSeconds * 1000L;

            return System.currentTimeMillis() - lastRightClick < cooldownMs;
        } catch (Exception e) {
            plugin.getLogger().fine("Error checking right-click cooldown: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get remaining cooldown time
     */
    private long getRemainingCooldown(Player player) {
        Long lastRightClick = rightClickCooldowns.get(player);
        if (lastRightClick == null) return 0;

        int cooldownSeconds = plugin.getConfigManager().getMainConfig().getInt("right_click_commands.cooldown", 1);
        long cooldownMs = cooldownSeconds * 1000L;

        return Math.max(0, cooldownMs - (System.currentTimeMillis() - lastRightClick));
    }

    /**
     * Set right-click cooldown
     */
    private void setRightClickCooldown(Player player) {
        rightClickCooldowns.put(player, System.currentTimeMillis());

        // Clean up cooldowns for offline players
        rightClickCooldowns.entrySet().removeIf(entry -> !entry.getKey().isOnline());
    }

    /**
     * FIXED: Handle boost enchantments on RIGHT-CLICK ONLY (separate from block breaking)
     */
    private void handleBoostEnchantmentsOnRightClick(Player player, ItemStack tool) {
        try {
            Map<String, Integer> enchantments = plugin.getToolManager().getToolEnchantments(tool);

            // Load player data asynchronously
            plugin.getDataManager().loadPlayerData(player.getUniqueId()).thenAccept(playerData -> {
                try {
                    // Execute on main thread to avoid AsyncCatcher errors
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        try {
                            boolean dataChanged = false;

                            // Try XP Boost - only if tool has xpboost enchantment AND on cooldown
                            int xpBoostLevel = enchantments.getOrDefault("xpboost", 0);
                            if (xpBoostLevel > 0 && !playerData.isOnCooldown("xpboost_rightclick")) {
                                if (player.hasPermission("ghasttools.enchant.xpboost")) {
                                    // Give instant XP boost
                                    double xpAmount = xpBoostLevel * 10; // 10 XP per level
                                    playerData.addXpEarned(xpAmount);
                                    playerData.setCooldown("xpboost_rightclick", 30000); // 30 second cooldown
                                    dataChanged = true;

                                    // Execute XP command for right-click boost
                                    String command = plugin.getConfigManager().getRewardsConfig()
                                            .getString("rewards.xp.command", "");
                                    if (!command.isEmpty()) {
                                        String finalXpCommand = command.replace("{player}", player.getName())
                                                .replace("{amount}", String.valueOf(Math.round(xpAmount)));
                                        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), finalXpCommand);
                                    }

                                    player.sendMessage("§a+XP Boost activated! (+" + Math.round(xpAmount) + " XP)");
                                }
                            }

                            // Try Essence Boost - only if tool has essenceboost enchantment AND on cooldown
                            int essenceBoostLevel = enchantments.getOrDefault("essenceboost", 0);
                            if (essenceBoostLevel > 0 && !playerData.isOnCooldown("essenceboost_rightclick")) {
                                if (player.hasPermission("ghasttools.enchant.essenceboost")) {
                                    // Give instant Essence boost
                                    double essenceAmount = essenceBoostLevel * 5; // 5 Essence per level
                                    playerData.addEssenceEarned(essenceAmount);
                                    playerData.setCooldown("essenceboost_rightclick", 30000); // 30 second cooldown
                                    dataChanged = true;

                                    // Execute essence command for right-click boost
                                    String command = plugin.getConfigManager().getRewardsConfig()
                                            .getString("rewards.essence.command", "");
                                    if (!command.isEmpty()) {
                                        String finalEssenceCommand = command.replace("{player}", player.getName())
                                                .replace("{amount}", String.valueOf(Math.round(essenceAmount)));
                                        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), finalEssenceCommand);
                                    }

                                    player.sendMessage("§a+Essence Boost activated! (+" + Math.round(essenceAmount) + " Essence)");
                                }
                            }

                            // Save player data if changed
                            if (dataChanged) {
                                plugin.getDataManager().savePlayerData(player.getUniqueId(), playerData)
                                        .exceptionally(throwable -> {
                                            plugin.getLogger().log(Level.WARNING, "Failed to save player data for " + player.getName(), throwable);
                                            return null;
                                        });
                            }

                        } catch (Exception e) {
                            plugin.getLogger().log(Level.WARNING, "Error in boost enchantment handling for " + player.getName(), e);
                        }
                    });
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Error in async boost processing for " + player.getName(), e);
                }
            }).exceptionally(throwable -> {
                plugin.getLogger().log(Level.WARNING, "Failed to load player data for boost handling: " + player.getName(), throwable);
                return null;
            });

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error handling boost enchantments for " + player.getName(), e);
        }
    }

    /**
     * Clean up cooldowns for offline players
     */
    public void cleanupCooldowns() {
        rightClickCooldowns.entrySet().removeIf(entry -> !entry.getKey().isOnline());
    }
}