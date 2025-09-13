package com.ghasttools.listeners;

import com.ghasttools.GhastToolsPlugin;
import com.ghasttools.milestones.MilestoneGui;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;

/**
 * FIXED: Milestone GUI listener with proper click handling
 */
public class MilestoneGuiListener implements Listener {

    private final GhastToolsPlugin plugin;
    private final MilestoneGui milestoneGui;

    public MilestoneGuiListener(GhastToolsPlugin plugin, MilestoneGui milestoneGui) {
        this.plugin = plugin;
        this.milestoneGui = milestoneGui;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        String inventoryTitle = event.getView().getTitle();

        // Check if it's a milestone GUI
        if (milestoneGui.isMilestoneGui(inventoryTitle)) {
            // Cancel the event to prevent item movement
            event.setCancelled(true);

            // FIXED: Only handle clicks in the top inventory (GUI), not player inventory
            if (event.getClickedInventory() == null ||
                    !event.getClickedInventory().equals(event.getView().getTopInventory())) {
                return;
            }

            // Handle the click
            int slot = event.getSlot();
            ItemStack clickedItem = event.getCurrentItem();

            // FIXED: Validate slot and item before processing
            if (clickedItem != null && slot >= 0 && slot < event.getInventory().getSize()) {

                milestoneGui.handleGuiClick(player, slot, clickedItem);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getPlayer();
        String inventoryTitle = event.getView().getTitle();

        // Check if it's a milestone GUI
        if (milestoneGui.isMilestoneGui(inventoryTitle)) {
            milestoneGui.removePlayerGui(player);
        }
    }
}