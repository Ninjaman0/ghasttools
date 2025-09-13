package com.ghasttools.essence;

import com.ghasttools.GhastToolsPlugin;
import com.ninja.ghast.ghastessence.GhastEssence;
import com.ninja.ghast.ghastessence.managers.EssenceManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * ENHANCED: Handles GhastEssence integration with direct EssenceManager access
 */
public class EssenceHandler {

    private final GhastToolsPlugin plugin;
    private volatile EssenceManager essenceManager;
    private volatile GhastEssence ghastEssencePlugin;
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);

    public EssenceHandler(GhastToolsPlugin plugin) {
        this.plugin = plugin;
        initializeEssenceManager();
    }

    /**
     * Initialize connection to GhastEssence plugin
     */
    private void initializeEssenceManager() {
        try {
            ghastEssencePlugin = (GhastEssence) Bukkit.getPluginManager().getPlugin("GhastEssence");

            if (ghastEssencePlugin != null && ghastEssencePlugin.isEnabled()) {
                essenceManager = ghastEssencePlugin.getEssenceManager();

                if (essenceManager != null) {
                    isInitialized.set(true);
                    plugin.getLogger().info("Successfully connected to GhastEssence plugin");
                } else {
                    plugin.getLogger().warning("GhastEssence plugin found but EssenceManager is null!");
                }
            } else {
                plugin.getLogger().warning("GhastEssence plugin not found or not enabled. Essence features will be disabled.");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error connecting to GhastEssence: " + e.getMessage(), e);
        }
    }

    /**
     * Check if essence system is available
     */
    public boolean isAvailable() {
        return isInitialized.get() && essenceManager != null && ghastEssencePlugin != null && ghastEssencePlugin.isEnabled();
    }

    /**
     * ENHANCED: Get direct access to EssenceManager for giving essence directly
     */
    public EssenceManager getEssenceManager() {
        if (!isAvailable()) {
            initializeEssenceManager(); // Try to reconnect
        }
        return essenceManager;
    }

    /**
     * Get player's current essence level (not essence points)
     */
    public int getPlayerEssenceLevel(Player player) {
        if (!isAvailable()) {
            plugin.getLogger().fine("Essence system not available for level check");
            return 0;
        }

        try {
            return essenceManager.getLevel(player.getUniqueId());
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error getting essence level for " + player.getName(), e);
            return 0;
        }
    }

    /**
     * Get player's current essence level by UUID
     */
    public int getPlayerEssenceLevel(UUID playerUUID) {
        if (!isAvailable()) {
            plugin.getLogger().fine("Essence system not available for level check");
            return 0;
        }

        try {
            return essenceManager.getLevel(playerUUID);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error getting essence level for " + playerUUID, e);
            return 0;
        }
    }

    /**
     * Get player's raw essence amount (for calculations)
     */
    public int getPlayerEssence(Player player) {
        if (!isAvailable()) {
            return 0;
        }

        try {
            return essenceManager.getEssence(player.getUniqueId());
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error getting essence for " + player.getName(), e);
            return 0;
        }
    }

    /**
     * Check if player has enough essence levels for a purchase
     */
    public boolean hasEnoughEssenceLevels(Player player, int requiredLevels) {
        if (!isAvailable()) {
            plugin.getLogger().fine("Essence system not available for level check");
            return false;
        }

        if (requiredLevels <= 0) {
            return true;
        }

        try {
            int playerLevel = essenceManager.getLevel(player.getUniqueId());
            return playerLevel >= requiredLevels;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error checking essence levels for " + player.getName(), e);
            return false;
        }
    }

    /**
     * Withdraw essence levels from player (converts levels to essence points and withdraws)
     */
    public boolean withdrawEssenceLevels(Player player, int levelsToWithdraw) {
        if (!isAvailable()) {
            plugin.getLogger().warning("Cannot withdraw essence - system not available");
            return false;
        }

        if (levelsToWithdraw <= 0) {
            return true;
        }

        try {
            // First check if player has enough levels
            int currentLevel = essenceManager.getLevel(player.getUniqueId());

            if (currentLevel < levelsToWithdraw) {
                return false;
            }

            // Calculate essence points needed for the levels
            // Each level requires basePerLevel essence points
            int basePerLevel = essenceManager.getBasePerLevel();
            int essenceToWithdraw = levelsToWithdraw * basePerLevel;

            // Use the takeEssence method which handles the withdrawal safely
            boolean success = essenceManager.takeEssence(player, essenceToWithdraw);

            if (success) {
                plugin.getLogger().fine("Successfully withdrew " + levelsToWithdraw + " essence levels from " + player.getName());
            } else {
                plugin.getLogger().warning("Failed to withdraw essence from " + player.getName());
            }

            return success;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error withdrawing essence levels from " + player.getName(), e);
            return false;
        }
    }

    /**
     * Give essence levels to player (converts levels to essence points and gives)
     */
    public boolean giveEssenceLevels(Player player, int levelsToGive) {
        if (!isAvailable()) {
            plugin.getLogger().warning("Cannot give essence - system not available");
            return false;
        }

        if (levelsToGive <= 0) {
            return true;
        }

        try {
            // Calculate essence points needed for the levels
            int basePerLevel = essenceManager.getBasePerLevel();
            int essenceToGive = levelsToGive * basePerLevel;

            // Use the giveEssence method which handles the addition safely
            boolean success = essenceManager.giveEssence(player, essenceToGive);

            if (success) {
                plugin.getLogger().fine("Successfully gave " + levelsToGive + " essence levels to " + player.getName());
            } else {
                plugin.getLogger().warning("Failed to give essence to " + player.getName());
            }

            return success;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error giving essence levels to " + player.getName(), e);
            return false;
        }
    }

    /**
     * Get the essence cost in levels for a specific number of essence points
     */
    public int convertEssencePointsToLevels(int essencePoints) {
        if (!isAvailable() || essencePoints <= 0) {
            return 0;
        }

        try {
            int basePerLevel = essenceManager.getBasePerLevel();
            return essencePoints / basePerLevel;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error converting essence points to levels", e);
            return 0;
        }
    }

    /**
     * Get the essence points equivalent for a specific number of levels
     */
    public int convertLevelsToEssencePoints(int levels) {
        if (!isAvailable() || levels <= 0) {
            return 0;
        }

        try {
            int basePerLevel = essenceManager.getBasePerLevel();
            return levels * basePerLevel;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error converting levels to essence points", e);
            return 0;
        }
    }

    /**
     * Get maximum essence level from configuration
     */
    public int getMaxEssenceLevel() {
        if (!isAvailable()) {
            return 1000; // Default fallback
        }

        try {
            return essenceManager.getMaxLevel();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error getting max essence level", e);
            return 1000;
        }
    }

    /**
     * Format essence level for display
     */
    public String formatEssenceLevel(int level) {
        if (level >= 1_000_000) {
            return String.format("%.1fM", level / 1_000_000.0);
        } else if (level >= 1_000) {
            return String.format("%.1fK", level / 1_000.0);
        } else {
            return String.valueOf(level);
        }
    }

    /**
     * Reinitialize connection (for plugin reloads)
     */
    public void reinitialize() {
        isInitialized.set(false);
        essenceManager = null;
        ghastEssencePlugin = null;
        initializeEssenceManager();
    }

    /**
     * Cleanup resources
     */
    public void shutdown() {
        isInitialized.set(false);
        essenceManager = null;
        ghastEssencePlugin = null;
    }

    /**
     * Get debug information
     */
    public String getDebugInfo() {
        StringBuilder info = new StringBuilder();
        info.append("EssenceHandler Status:\n");
        info.append("- Available: ").append(isAvailable()).append("\n");
        info.append("- Initialized: ").append(isInitialized.get()).append("\n");
        info.append("- Plugin Found: ").append(ghastEssencePlugin != null).append("\n");
        info.append("- Manager Available: ").append(essenceManager != null).append("\n");

        if (isAvailable()) {
            try {
                info.append("- Base Per Level: ").append(essenceManager.getBasePerLevel()).append("\n");
                info.append("- Max Level: ").append(essenceManager.getMaxLevel()).append("\n");
            } catch (Exception e) {
                info.append("- Error getting config: ").append(e.getMessage()).append("\n");
            }
        }

        return info.toString();
    }
}