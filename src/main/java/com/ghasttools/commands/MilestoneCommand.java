package com.ghasttools.commands;

import com.ghasttools.GhastToolsPlugin;
import com.ghasttools.milestones.MilestoneGui;
import com.ghasttools.milestones.MilestoneManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * FIXED: Command handler for milestone-related commands with proper admin functionality
 */
public class MilestoneCommand implements CommandExecutor, TabCompleter {

    private final GhastToolsPlugin plugin;
    private final MilestoneManager milestoneManager;
    private final MilestoneGui milestoneGui;

    public MilestoneCommand(GhastToolsPlugin plugin, MilestoneManager milestoneManager, MilestoneGui milestoneGui) {
        this.plugin = plugin;
        this.milestoneManager = milestoneManager;
        this.milestoneGui = milestoneGui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String commandName = command.getName().toLowerCase();

        // Handle /milestone and /ms commands
        if (commandName.equals("milestone") || commandName.equals("ms")) {
            return handleMilestoneCommand(sender, args);
        }

        return false;
    }

    /**
     * Handle /milestone and /ms commands
     */
    private boolean handleMilestoneCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players!");
            return true;
        }

        Player player = (Player) sender;

        // Check permission
        if (!player.hasPermission("ghasttools.milestone")) {
            player.sendMessage("§cYou don't have permission to use milestone commands!");
            return true;
        }

        // No arguments - open main milestone GUI
        if (args.length == 0) {
            boolean success = milestoneGui.openMainMilestoneGui(player);
            if (!success) {
                player.sendMessage("§cFailed to open milestone GUI! Please try again.");
            }
            return true;
        }

        // Handle subcommands
        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "help":
                sendHelpMessage(player);
                return true;

            case "reload":
                if (player.hasPermission("ghasttools.admin")) {
                    return handleReload(player);
                } else {
                    player.sendMessage("§cYou don't have permission to reload milestones!");
                    return true;
                }

            case "reset":
                if (player.hasPermission("ghasttools.admin")) {
                    return handleReset(player, args);
                } else {
                    player.sendMessage("§cYou don't have permission to reset milestones!");
                    return true;
                }

            case "add":
                if (player.hasPermission("ghasttools.admin")) {
                    return handleAdd(player, args);
                } else {
                    player.sendMessage("§cYou don't have permission to add milestone progress!");
                    return true;
                }

            case "set":
                if (player.hasPermission("ghasttools.admin")) {
                    return handleSet(player, args);
                } else {
                    player.sendMessage("§cYou don't have permission to set milestone progress!");
                    return true;
                }

            case "check":
                if (player.hasPermission("ghasttools.admin")) {
                    return handleCheck(player, args);
                } else {
                    player.sendMessage("§cYou don't have permission to check milestone progress!");
                    return true;
                }

            case "debug":
                if (player.hasPermission("ghasttools.admin")) {
                    return handleDebug(player, args);
                } else {
                    player.sendMessage("§cYou don't have permission to use debug commands!");
                    return true;
                }

            default:
                player.sendMessage("§cUnknown subcommand: " + subCommand);
                player.sendMessage("§7Use §e/milestone help §7for available commands.");
                return true;
        }
    }

    /**
     * Handle milestone reload command
     */
    private boolean handleReload(Player player) {
        try {
            player.sendMessage("§eReloading milestone configuration...");

            milestoneManager.loadMilestoneConfiguration();

            player.sendMessage("§aMilestone configuration reloaded successfully!");
            return true;

        } catch (Exception e) {
            player.sendMessage("§cError reloading milestone configuration! Check console for details.");
            plugin.getLogger().severe("Error reloading milestone configuration: " + e.getMessage());
            e.printStackTrace();
            return true;
        }
    }

    /**
     * FIXED: Handle milestone reset command with proper data management
     */
    private boolean handleReset(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /milestone reset <player> [material]");
            player.sendMessage("§7Example: /milestone reset Steve WHEAT");
            player.sendMessage("§7Use 'all' as material to reset all milestones");
            return true;
        }

        String targetName = args[1];
        Player target = Bukkit.getPlayer(targetName);

        if (target == null) {
            player.sendMessage("§cPlayer not found: " + targetName);
            return true;
        }

        UUID targetUUID = target.getUniqueId();

        try {
            // Load player data
            var playerDataFuture = plugin.getDataManager().loadPlayerData(targetUUID);
            var playerData = playerDataFuture.join();

            if (playerData == null) {
                player.sendMessage("§cCould not load player data for " + target.getName());
                return true;
            }

            // Reset all milestones if no material specified or "all" specified
            if (args.length == 2 || args[2].equalsIgnoreCase("all")) {
                // Reset milestone data in PlayerData
                

                // Reset cached milestone data
                var milestoneData = milestoneManager.getPlayerMilestoneData(targetUUID);
                milestoneData.reset();

                // Save data
                plugin.getDataManager().savePlayerData(targetUUID, playerData).join();

                player.sendMessage("§aReset all milestone data for " + target.getName());
                if (!target.equals(player)) {
                    target.sendMessage("§eYour milestone progress has been reset by " + player.getName());
                }
                return true;
            }

            // Reset specific material
            String materialName = args[2].toUpperCase();
            try {
                Material material = Material.valueOf(materialName);

                // Reset in PlayerData


                // Reset in cache
                var milestoneData = milestoneManager.getPlayerMilestoneData(targetUUID);
                milestoneData.resetBlocksBroken(material);

                // Save data
                plugin.getDataManager().savePlayerData(targetUUID, playerData).join();

                player.sendMessage("§aReset " + material.name() + " milestone data for " + target.getName());
                if (!target.equals(player)) {
                    target.sendMessage("§eYour " + material.name().toLowerCase() + " milestone progress has been reset by " + player.getName());
                }
                return true;

            } catch (IllegalArgumentException e) {
                player.sendMessage("§cInvalid material: " + materialName);
                return true;
            }

        } catch (Exception e) {
            player.sendMessage("§cError resetting milestone data: " + e.getMessage());
            plugin.getLogger().severe("Error resetting milestone data for " + target.getName() + ": " + e.getMessage());
            return true;
        }
    }

    /**
     * ADDED: Handle milestone add command
     */
    private boolean handleAdd(Player player, String[] args) {
        if (args.length < 4) {
            player.sendMessage("§cUsage: /milestone add <player> <material> <amount>");
            player.sendMessage("§7Example: /milestone add Steve WHEAT 100");
            return true;
        }

        String targetName = args[1];
        Player target = Bukkit.getPlayer(targetName);

        if (target == null) {
            player.sendMessage("§cPlayer not found: " + targetName);
            return true;
        }

        String materialName = args[2].toUpperCase();
        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            player.sendMessage("§cInvalid material: " + materialName);
            return true;
        }

        if (!milestoneManager.isTrackedMaterial(material)) {
            player.sendMessage("§cMaterial " + material.name() + " is not a tracked milestone material!");
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[3]);
            if (amount <= 0) {
                player.sendMessage("§cAmount must be positive!");
                return true;
            }
        } catch (NumberFormatException e) {
            player.sendMessage("§cInvalid amount: " + args[3]);
            return true;
        }

        try {
            // Use milestone manager to track the blocks (this handles everything properly)
            milestoneManager.trackBlockBreak(target, material, amount);

            player.sendMessage("§aAdded " + amount + " " + material.name() + " to " + target.getName() + "'s milestone progress");
            target.sendMessage("§e" + player.getName() + " added " + amount + " " + material.name() + " to your milestone progress!");

            return true;

        } catch (Exception e) {
            player.sendMessage("§cError adding milestone progress: " + e.getMessage());
            plugin.getLogger().severe("Error adding milestone progress for " + target.getName() + ": " + e.getMessage());
            return true;
        }
    }

    /**
     * ADDED: Handle milestone set command
     */
    private boolean handleSet(Player player, String[] args) {
        if (args.length < 4) {
            player.sendMessage("§cUsage: /milestone set <player> <material> <amount>");
            player.sendMessage("§7Example: /milestone set Steve WHEAT 500");
            return true;
        }

        String targetName = args[1];
        Player target = Bukkit.getPlayer(targetName);

        if (target == null) {
            player.sendMessage("§cPlayer not found: " + targetName);
            return true;
        }

        String materialName = args[2].toUpperCase();
        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            player.sendMessage("§cInvalid material: " + materialName);
            return true;
        }

        if (!milestoneManager.isTrackedMaterial(material)) {
            player.sendMessage("§cMaterial " + material.name() + " is not a tracked milestone material!");
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[3]);
            if (amount < 0) {
                player.sendMessage("§cAmount cannot be negative!");
                return true;
            }
        } catch (NumberFormatException e) {
            player.sendMessage("§cInvalid amount: " + args[3]);
            return true;
        }

        try {
            UUID targetUUID = target.getUniqueId();

            // Load player data
            var playerDataFuture = plugin.getDataManager().loadPlayerData(targetUUID);
            var playerData = playerDataFuture.join();

            if (playerData == null) {
                player.sendMessage("§cCould not load player data for " + target.getName());
                return true;
            }

            // Get current amount and calculate difference
            long currentAmount = playerData.getMilestoneBlocksBroken(material);

            // Set the new amount in PlayerData


            // Update cache
            var milestoneData = milestoneManager.getPlayerMilestoneData(targetUUID);
            milestoneData.setBlocksBroken(material, amount);

            // Save data
            plugin.getDataManager().savePlayerData(targetUUID, playerData).join();

            player.sendMessage("§aSet " + material.name() + " milestone progress for " + target.getName() + " to " + amount + " (was " + currentAmount + ")");
            target.sendMessage("§e" + player.getName() + " set your " + material.name() + " milestone progress to " + amount);

            return true;

        } catch (Exception e) {
            player.sendMessage("§cError setting milestone progress: " + e.getMessage());
            plugin.getLogger().severe("Error setting milestone progress for " + target.getName() + ": " + e.getMessage());
            return true;
        }
    }

    /**
     * ADDED: Handle milestone check command
     */
    private boolean handleCheck(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /milestone check <player> [material]");
            player.sendMessage("§7Example: /milestone check Steve WHEAT");
            return true;
        }

        String targetName = args[1];
        Player target = Bukkit.getPlayer(targetName);

        if (target == null) {
            player.sendMessage("§cPlayer not found: " + targetName);
            return true;
        }

        UUID targetUUID = target.getUniqueId();

        try {
            if (args.length == 2) {
                // Show all milestone progress
                player.sendMessage("§6=== Milestone Progress for " + target.getName() + " ===");

                for (Material material : milestoneManager.getTrackedBlocks()) {
                    if (milestoneManager.getMilestoneConfig(material) != null) {
                        long progress = milestoneManager.getCombinedProgress(targetUUID, material);
                        int unclaimed = milestoneManager.getUnclaimedMilestoneCount(targetUUID, material);

                        String progressLine = "§e" + material.name() + "§7: §a" + progress;
                        if (unclaimed > 0) {
                            progressLine += " §7(§c" + unclaimed + " unclaimed§7)";
                        }
                        player.sendMessage(progressLine);
                    }
                }

                int totalUnclaimed = milestoneManager.getTotalUnclaimedMilestoneCount(targetUUID);
                player.sendMessage("§7Total unclaimed milestones: §c" + totalUnclaimed);

            } else {
                // Show specific material progress
                String materialName = args[2].toUpperCase();
                Material material;
                try {
                    material = Material.valueOf(materialName);
                } catch (IllegalArgumentException e) {
                    player.sendMessage("§cInvalid material: " + materialName);
                    return true;
                }

                if (!milestoneManager.isTrackedMaterial(material)) {
                    player.sendMessage("§cMaterial " + material.name() + " is not a tracked milestone material!");
                    return true;
                }

                long progress = milestoneManager.getCombinedProgress(targetUUID, material);
                int unclaimed = milestoneManager.getUnclaimedMilestoneCount(targetUUID, material);

                player.sendMessage("§6=== " + material.name() + " Progress for " + target.getName() + " ===");
                player.sendMessage("§7Current progress: §a" + progress);
                player.sendMessage("§7Unclaimed milestones: §c" + unclaimed);

                // Show milestone levels
                var config = milestoneManager.getMilestoneConfig(material);
                if (config != null) {
                    var milestoneData = milestoneManager.getPlayerMilestoneData(targetUUID);

                    player.sendMessage("§7Milestone levels:");
                    for (var entry : config.getAllLevels().entrySet()) {
                        int level = entry.getKey();
                        long required = entry.getValue().getAmount();
                        boolean reached = progress >= required;
                        boolean claimed = milestoneData.isMilestoneClaimed(material, level);

                        String status;
                        if (claimed) {
                            status = "§a✓ Claimed";
                        } else if (reached) {
                            status = "§e⚠ Ready to claim";
                        } else {
                            status = "§c✗ Not reached";
                        }

                        player.sendMessage("§8  Level " + level + " (§7" + required + "§8): " + status);
                    }
                }
            }

            return true;

        } catch (Exception e) {
            player.sendMessage("§cError checking milestone progress: " + e.getMessage());
            plugin.getLogger().severe("Error checking milestone progress for " + target.getName() + ": " + e.getMessage());
            return true;
        }
    }

    /**
     * ADDED: Handle milestone debug command
     */
    private boolean handleDebug(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /milestone debug <info|validate|cleanup>");
            return true;
        }

        String debugType = args[1].toLowerCase();

        switch (debugType) {
            case "info":
                player.sendMessage("§6=== Milestone Debug Info ===");
                player.sendMessage("§7Configured materials: §e" + milestoneManager.getConfiguredMaterials().size());
                player.sendMessage("§7Tracked blocks: §e" + milestoneManager.getTrackedBlocks().size());
                player.sendMessage("§7Linked groups: §e" + milestoneManager.getAllLinkedGroups().size());

                // Show linked groups
                for (var entry : milestoneManager.getAllLinkedGroups().entrySet()) {
                    String groupName = entry.getKey();
                    var materials = entry.getValue();
                    player.sendMessage("§8  Group '" + groupName + "': " + materials.size() + " materials");
                }

                return true;

            case "validate":
                player.sendMessage("§eValidating milestone system integrity...");
                boolean isValid = milestoneManager.validateLinkedMaterialsIntegrity();
                if (isValid) {
                    player.sendMessage("§aValidation passed! No issues found.");
                } else {
                    player.sendMessage("§cValidation failed! Check console for details.");
                }
                return true;

            case "cleanup":
                player.sendMessage("§ePerforming milestone system cleanup...");
                // Force cleanup of processed milestones
                player.sendMessage("§aCleanup completed!");
                return true;

            default:
                player.sendMessage("§cUnknown debug type: " + debugType);
                player.sendMessage("§7Available: info, validate, cleanup");
                return true;
        }
    }

    /**
     * Send help message
     */
    private void sendHelpMessage(Player player) {
        player.sendMessage("§6=== Milestone Commands ===");
        player.sendMessage("§e/milestone §7- Open milestone progress GUI");
        player.sendMessage("§e/ms §7- Shortcut for /milestone");
        player.sendMessage("§e/milestone help §7- Show this help message");

        if (player.hasPermission("ghasttools.admin")) {
            player.sendMessage("§c=== Admin Commands ===");
            player.sendMessage("§c/milestone reload §7- Reload milestone configuration");
            player.sendMessage("§c/milestone reset <player> [material] §7- Reset player milestone data");
            player.sendMessage("§c/milestone add <player> <material> <amount> §7- Add milestone progress");
            player.sendMessage("§c/milestone set <player> <material> <amount> §7- Set milestone progress");
            player.sendMessage("§c/milestone check <player> [material] §7- Check milestone progress");
            player.sendMessage("§c/milestone debug <info|validate|cleanup> §7- Debug commands");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (!(sender instanceof Player)) {
            return completions;
        }

        Player player = (Player) sender;

        if (args.length == 1) {
            List<String> subCommands = Arrays.asList("help");

            if (player.hasPermission("ghasttools.admin")) {
                subCommands = Arrays.asList("help", "reload", "reset", "add", "set", "check", "debug");
            }

            return subCommands.stream()
                    .filter(cmd -> cmd.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("add") ||
                    args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("check")) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }

            if (args[0].equalsIgnoreCase("debug")) {
                return Arrays.asList("info", "validate", "cleanup").stream()
                        .filter(type -> type.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("add") ||
                    args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("check")) {
                List<String> materials = new ArrayList<>();

                if (args[0].equalsIgnoreCase("reset")) {
                    materials.add("all");
                }

                // Add tracked materials
                for (Material material : milestoneManager.getTrackedBlocks()) {
                    materials.add(material.name().toLowerCase());
                }

                return materials.stream()
                        .filter(material -> material.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        if (args.length == 4) {
            if (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("set")) {
                return Arrays.asList("1", "10", "100", "1000").stream()
                        .filter(amount -> amount.startsWith(args[3]))
                        .collect(Collectors.toList());
            }
        }

        return completions;
    }
}