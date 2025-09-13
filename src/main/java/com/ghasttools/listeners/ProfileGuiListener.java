package com.ghasttools.listeners;

import com.ghasttools.GhastToolsPlugin;
import com.ghasttools.commands.ProfileCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Updated listener for profile GUI interactions with configurable layout support
 */
public class ProfileGuiListener implements Listener {

    private final GhastToolsPlugin plugin;
    private final ProfileCommand profileCommand;

    public ProfileGuiListener(GhastToolsPlugin plugin, ProfileCommand profileCommand) {
        this.plugin = plugin;
        this.profileCommand = profileCommand;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        String inventoryTitle = event.getView().getTitle();

        // Check if it's a profile GUI using the updated configuration-based method
        if (!profileCommand.isProfileGui(inventoryTitle)) {
            return;
        }

        // Cancel the event to prevent item movement
        event.setCancelled(true);

        // Handle the click with configurable slot support
        int slot = event.getRawSlot();
        ItemStack clickedItem = event.getCurrentItem();

        if (clickedItem != null && slot >= 0 && slot < event.getInventory().getSize()) {
            // Updated to handle configurable profile GUI clicks
            handleConfigurableProfileClick(player, slot, clickedItem, inventoryTitle);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getPlayer();
        String inventoryTitle = event.getView().getTitle();

        // Check if it's a profile GUI using the updated configuration-based method
        if (profileCommand.isProfileGui(inventoryTitle)) {
            profileCommand.removePlayerProfile(player);
        }
    }

    /**
     * Handle profile GUI clicks with configurable slot support
     */
    private void handleConfigurableProfileClick(Player player, int slot, ItemStack clickedItem, String inventoryTitle) {
        try {
            // Get profile GUI configuration
            var profileConfig = plugin.getMilestoneManager().getProfileGuiConfig();

            // Check if clicked slot matches configured category slots
            if (slot == profileConfig.getFarmingSlot()) {
                // Handle farming category click
                handleCategoryClick(player, "farming");
            } else if (slot == profileConfig.getMiningSlot()) {
                // Handle mining category click
                handleCategoryClick(player, "mining");
            } else if (slot == profileConfig.getForagingSlot()) {
                // Handle foraging category click
                handleCategoryClick(player, "foraging");
            } else if (slot == profileConfig.getPlayerHeadSlot()) {
                // Handle player head click
                handlePlayerHeadClick(player);
            } else if (profileConfig.getMiscInfoConfig() != null &&
                    slot == profileConfig.getMiscInfoConfig().getSlot()) {
                // Handle misc info click
                handleMiscInfoClick(player);
            } else {
                // Default profile click handling
                profileCommand.handleProfileClick(player, slot, clickedItem);
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Error handling configurable profile click: " + e.getMessage());
            // Fallback to default handling
            profileCommand.handleProfileClick(player, slot, clickedItem);
        }
    }

    /**
     * Handle category item clicks
     */
    private void handleCategoryClick(Player player, String category) {

    }

    /**
     * Handle player head clicks
     */
    private void handlePlayerHeadClick(Player player) {

    }

    /**
     * Handle misc info clicks
     */
    private void handleMiscInfoClick(Player player) {

    }
}