package com.ghasttools.listeners;

import com.ghasttools.GhastToolsPlugin;
import com.ghasttools.gui.GuiManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

/**
 * FIXED: Thread-safe GUI click listener with proper title checking from config
 */
public class GuiClickListener implements Listener {

    private final GhastToolsPlugin plugin;
    private final GuiManager guiManager;

    public GuiClickListener(GhastToolsPlugin plugin, GuiManager guiManager) {
        this.plugin = plugin;
        this.guiManager = guiManager;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.isCancelled()) return;

        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();

        // FIXED: Check if it's any of our GUIs using config-based title checking
        String inventoryTitle = event.getView().getTitle();
        if (inventoryTitle == null ||
                (!guiManager.isUpgradeGui(inventoryTitle) &&
                        !guiManager.isRandomWinGui(inventoryTitle) &&
                        !guiManager.isShopGui(inventoryTitle))) {
            return;
        }

        // Cancel all clicks in our GUIs to prevent item taking
        event.setCancelled(true);

        // ENHANCED: Handle random win GUI clicks (animation-only, no interactions)
        if (guiManager.isRandomWinGui(inventoryTitle)) {
            // Random win GUI is animation-only, no interactions allowed during animation
            // Player can only close GUI to skip animation
            return;
        }

        // ADDED: Handle shop GUI clicks
        if (guiManager.isShopGui(inventoryTitle)) {
            // Check if click is in the GUI (not player inventory)
            if (event.getClickedInventory() == null ||
                    !event.getClickedInventory().equals(event.getView().getTopInventory())) {
                return;
            }

            int slot = event.getSlot();
            boolean isLeftClick = event.getClick() == ClickType.LEFT;
            boolean isRightClick = event.getClick() == ClickType.RIGHT;

            // Only handle left and right clicks
            if (!isLeftClick && !isRightClick) {
                return;
            }

            // Handle the shop click
            try {
                guiManager.handleGuiClick(player, slot, isLeftClick);
            } catch (Exception e) {
                plugin.getLogger().warning("Error handling shop GUI click for " + player.getName() + ": " + e.getMessage());
            }
            return;
        }

        // Handle upgrade GUI clicks
        if (guiManager.isUpgradeGui(inventoryTitle)) {
            // Check if click is in the GUI (not player inventory)
            if (event.getClickedInventory() == null ||
                    !event.getClickedInventory().equals(event.getView().getTopInventory())) {
                return;
            }

            int slot = event.getSlot();
            boolean isLeftClick = event.getClick() == ClickType.LEFT;
            boolean isRightClick = event.getClick() == ClickType.RIGHT;

            // Only handle left and right clicks
            if (!isLeftClick && !isRightClick) {
                return;
            }

            // Handle the upgrade GUI click
            try {
                guiManager.handleGuiClick(player, slot, isLeftClick);
            } catch (Exception e) {
                plugin.getLogger().warning("Error handling upgrade GUI click for " + player.getName() + ": " + e.getMessage());
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;

        Player player = (Player) event.getPlayer();
        String inventoryTitle = event.getView().getTitle();

        // FIXED: Check if it's upgrade GUI using config-based title checking
        if (guiManager.isUpgradeGui(inventoryTitle)) {
            // Remove tool lock when upgrade GUI is closed
            guiManager.removeLock(player);
            return;
        }

        // ENHANCED: Check if it's random win GUI using config-based title checking
        if (guiManager.isRandomWinGui(inventoryTitle)) {
            // Handle random win GUI close through the RandomWinGui class
            // This is handled internally by RandomWinGui
            return;
        }

        // ADDED: Check if it's shop GUI using config-based title checking
        if (guiManager.isShopGui(inventoryTitle)) {
            // Shop GUI doesn't need special close handling
            return;
        }
    }
}