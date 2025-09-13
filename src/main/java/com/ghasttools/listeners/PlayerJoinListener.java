package com.ghasttools.listeners;

import com.ghasttools.GhastToolsPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FIXED: Handles player join/quit events with proper task management and memory leak prevention
 */
public class PlayerJoinListener implements Listener {

    private final GhastToolsPlugin plugin;

    // FIXED: Track haste tasks to prevent memory leaks
    private final ConcurrentHashMap<Player, BukkitTask> hasteTasks = new ConcurrentHashMap<>();

    public PlayerJoinListener(GhastToolsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Load player data
        plugin.getDataManager().loadPlayerData(player.getUniqueId()).thenAccept(playerData -> {
            playerData.updateLastSeen();
            // FIXED: Clean up expired data to prevent memory leaks
            playerData.cleanupExpiredCooldowns();
            plugin.getDataManager().savePlayerData(player.getUniqueId(), playerData);
        });

        // Start haste effect task for this player
        startHasteEffectTask(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // FIXED: Cancel and cleanup haste task to prevent memory leaks
        BukkitTask hasteTask = hasteTasks.remove(player);
        if (hasteTask != null && !hasteTask.isCancelled()) {
            hasteTask.cancel();
        }

        // Save player data on quit
        plugin.getDataManager().loadPlayerData(player.getUniqueId()).thenAccept(playerData -> {
            playerData.updateLastSeen();
            // FIXED: Cleanup expired data before saving
            playerData.cleanup();
            plugin.getDataManager().savePlayerData(player.getUniqueId(), playerData);
        });
    }

    /**
     * FIXED: Start haste effect task with proper tracking and cleanup
     */
    private void startHasteEffectTask(Player player) {
        // Cancel existing task if any
        BukkitTask existingTask = hasteTasks.get(player);
        if (existingTask != null && !existingTask.isCancelled()) {
            existingTask.cancel();
        }

        BukkitTask hasteTask = new BukkitRunnable() {
            @Override
            public void run() {
                // FIXED: Check if player is still online and plugin not shutting down
                if (!player.isOnline() || plugin.isShuttingDown()) {
                    hasteTasks.remove(player);
                    this.cancel();
                    return;
                }

                try {
                    ItemStack mainHand = player.getInventory().getItemInMainHand();
                    ItemStack offHand = player.getInventory().getItemInOffHand();

                    // Check main hand
                    if (plugin.getToolManager().isGhastTool(mainHand)) {
                        plugin.getEnchantmentManager().applyHasteEffect(player, mainHand);
                    }
                    // Check off hand
                    else if (plugin.getToolManager().isGhastTool(offHand)) {
                        plugin.getEnchantmentManager().applyHasteEffect(player, offHand);
                    }
                } catch (Exception e) {
                    plugin.getLogger().fine("Error applying haste effect to " + player.getName() + ": " + e.getMessage());
                }
            }
        }.runTaskTimer(plugin, 0L, 40L); // Run every 2 seconds

        // Track the task
        hasteTasks.put(player, hasteTask);
    }

    /**
     * FIXED: Cleanup method to cancel all haste tasks (for plugin shutdown)
     */
    public void cleanup() {
        for (Map.Entry<Player, BukkitTask> entry : hasteTasks.entrySet()) {
            BukkitTask task = entry.getValue();
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        hasteTasks.clear();
    }

    /**
     * Get active haste task count for monitoring
     */
    public int getActiveHasteTaskCount() {
        // Clean up cancelled tasks
        hasteTasks.entrySet().removeIf(entry ->
                entry.getValue() == null || entry.getValue().isCancelled() || !entry.getKey().isOnline());

        return hasteTasks.size();
    }
}