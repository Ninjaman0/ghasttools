package com.ghasttools.commands;

import com.ghasttools.GhastToolsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * ENHANCED: Thread-safe command handler with profile integration
 */
public class GhastToolsCommand implements CommandExecutor, TabCompleter {

    private final GhastToolsPlugin plugin;

    public GhastToolsCommand(GhastToolsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (plugin.isReloading()) {
            sender.sendMessage("§cPlugin is currently reloading, please wait...");
            return true;
        }

        if (args.length == 0) {
            sendHelpMessage(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        try {
            switch (subCommand) {
                case "give":
                    return handleGive(sender, args);
                case "givemax":
                case "maxtools":
                    return handleGiveMax(sender, args);
                case "upgrade":
                    return handleUpgrade(sender, args);
                case "upgradeamount":
                case "upgradeamt":
                    return handleUpgradeAmount(sender, args);
                case "enchant":
                case "addenchant":
                    return handleEnchant(sender, args);
                case "enchantamount":
                case "enchantamt":
                    return handleEnchantAmount(sender, args);
                case "removeenchant":
                case "delenchant":
                    return handleRemoveEnchant(sender, args);
                case "listenchants":
                case "enchants":
                    return handleListEnchants(sender, args);
                case "exportdata":
                    return handleExportData(sender, args);
                case "importdata":
                    return handleImportData(sender, args);
                case "cleandata":
                    return handleCleanData(sender, args);
                case "info":
                case "toolinfo":
                    return handleInfo(sender, args);
                case "cooldowns":
                case "cooldown":
                    return handleCooldowns(sender, args);
                case "stats":
                case "statistics":
                    return handleStats(sender, args);
                case "milestone":
                    return handleMilestone(sender, args);
                case "reload":
                    return handleReload(sender, args);
                case "debug":
                    return handleDebug(sender, args);
                case "test":
                    return handleTest(sender, args);
                case "version":
                case "ver":
                    return handleVersion(sender, args);
                case "help":
                    sendHelpMessage(sender);
                    return true;
                default:
                    plugin.getMessageUtil().sendMessage(sender, "unknown_command");
                    return true;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error executing command '" + subCommand + "'", e);
            sender.sendMessage("§cAn error occurred while executing the command.");
            return true;
        }
    }

    /**
     * ADDED: Handle milestone subcommand for player profile viewing
     */
    private boolean handleMilestone(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ghasttools.admin")) {
            plugin.getMessageUtil().sendMessage(sender, "no_permission");
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players!");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§cUsage: /ghasttools milestone <player>");
            sender.sendMessage("§7Opens the milestone profile GUI for the specified player");
            return true;
        }

        Player viewer = (Player) sender;
        Player target = Bukkit.getPlayer(args[1]);

        if (target == null) {
            sender.sendMessage("§cPlayer not found: " + args[1]);
            return true;
        }

        // Use the profile command to open the profile GUI
        if (plugin.getProfileCommand() != null) {
            boolean success = plugin.getProfileCommand().openProfileGui(viewer, target);
            if (!success) {
                sender.sendMessage("§cFailed to open milestone profile for " + target.getName() + "!");
            }
        } else {
            sender.sendMessage("§cProfile system not available!");
        }

        return true;
    }

    /**
     * Admin command to upgrade tool by amount of tiers
     */
    private boolean handleUpgradeAmount(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ghasttools.admin")) {
            plugin.getMessageUtil().sendMessage(sender, "no_permission");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage("§cUsage: /ghasttools upgradeamount <player> <amount>");
            sender.sendMessage("§7Example: /ghasttools upgradeamount Player 2");
            sender.sendMessage("§7This upgrades the player's held tool by 2 tiers");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            plugin.getMessageUtil().sendMessage(sender, "player_not_found", Map.of("player", args[1]));
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[2]);
            if (amount < 1 || amount > 5) {
                sender.sendMessage("§cAmount must be between 1 and 5!");
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§cAmount must be a number!");
            return true;
        }

        ItemStack heldTool = target.getInventory().getItemInMainHand();
        if (!plugin.getToolManager().isGhastTool(heldTool)) {
            sender.sendMessage("§cPlayer must be holding a GhastTool!");
            return true;
        }

        int currentTier = plugin.getToolManager().getToolTier(heldTool);
        int newTier = Math.min(6, currentTier + amount);

        if (newTier <= currentTier) {
            sender.sendMessage("§cTool is already at maximum tier or upgrade would exceed maximum!");
            return true;
        }

        plugin.getToolManager().upgradeTool(target, heldTool, newTier);
        sender.sendMessage("§aUpgraded " + target.getName() + "'s tool by " + amount + " tiers (Tier " + currentTier + " → " + newTier + ")!");
        target.sendMessage("§aYour tool has been upgraded by " + amount + " tiers to Tier " + newTier + "!");

        return true;
    }

    /**
     * Admin command to upgrade enchantments by amount of levels
     */
    private boolean handleEnchantAmount(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ghasttools.admin")) {
            plugin.getMessageUtil().sendMessage(sender, "no_permission");
            return true;
        }

        if (args.length < 4) {
            sender.sendMessage("§cUsage: /ghasttools enchantamount <player> <enchantment> <amount>");
            sender.sendMessage("§7Example: /ghasttools enchantamount Player haste 2");
            sender.sendMessage("§7This increases the enchantment level by 2");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            plugin.getMessageUtil().sendMessage(sender, "player_not_found", Map.of("player", args[1]));
            return true;
        }

        String enchantment = args[2].toLowerCase();
        int amount;

        try {
            amount = Integer.parseInt(args[3]);
            if (amount < 1 || amount > 6) {
                sender.sendMessage("§cAmount must be between 1 and 6!");
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§cAmount must be a number!");
            return true;
        }

        ItemStack heldTool = target.getInventory().getItemInMainHand();
        if (!plugin.getToolManager().isGhastTool(heldTool)) {
            sender.sendMessage("§cPlayer must be holding a GhastTool!");
            return true;
        }

        // Check enchantment exists in config
        var enchantConfig = plugin.getEnchantmentManager().getEnchantmentConfig(enchantment);
        if (enchantConfig == null) {
            sender.sendMessage("§cUnknown enchantment: " + enchantment);
            return true;
        }

        Map<String, Integer> enchantments = plugin.getToolManager().getToolEnchantments(heldTool);
        int currentLevel = enchantments.getOrDefault(enchantment, 0);
        int newLevel = Math.min(enchantConfig.getMaxLevel(), currentLevel + amount);

        if (newLevel <= currentLevel) {
            sender.sendMessage("§cEnchantment is already at maximum level or upgrade would exceed maximum!");
            return true;
        }

        plugin.getEnchantmentManager().applyEnchantment(heldTool, enchantment, newLevel);
        sender.sendMessage("§aUpgraded " + target.getName() + "'s " + enchantment + " by " + amount + " levels (Level " + currentLevel + " → " + newLevel + ")!");
        target.sendMessage("§aYour " + enchantment + " enchantment has been upgraded by " + amount + " levels to Level " + newLevel + "!");

        return true;
    }

    private boolean handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ghasttools.admin")) {
            plugin.getMessageUtil().sendMessage(sender, "no_permission");
            return true;
        }

        if (args.length < 4) {
            sender.sendMessage("§cUsage: /ghasttools give <player> <tool> <tier> [enchantments...]");
            sender.sendMessage("§7Example: /ghasttools give Player pickaxe 3 haste:2 explosive:1");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            plugin.getMessageUtil().sendMessage(sender, "player_not_found", Map.of("player", args[1]));
            return true;
        }

        String toolType = args[2].toLowerCase();
        int tier;

        try {
            tier = Integer.parseInt(args[3]);
            if (tier < 1 || tier > 6) {
                sender.sendMessage("§cTier must be between 1 and 6!");
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§cTier must be a number!");
            return true;
        }

        // Validate tool type
        if (!Arrays.asList("pickaxe", "axe", "hoe").contains(toolType)) {
            sender.sendMessage("§cValid tool types: pickaxe, axe, hoe");
            return true;
        }

        ItemStack tool = plugin.getToolManager().createTool(toolType, tier);
        if (tool == null) {
            sender.sendMessage("§cFailed to create tool! Check tier configuration.");
            return true;
        }

        // Apply enchantments if specified
        if (args.length > 4) {
            for (int i = 4; i < args.length; i++) {
                String[] enchantParts = args[i].split(":");
                if (enchantParts.length == 2) {
                    try {
                        String enchantName = enchantParts[0].toLowerCase();
                        int enchantLevel = Integer.parseInt(enchantParts[1]);

                        plugin.getEnchantmentManager().applyEnchantment(tool, enchantName, enchantLevel);
                    } catch (NumberFormatException e) {
                        sender.sendMessage("§eWarning: Invalid enchantment format: " + args[i]);
                    }
                }
            }
        }

        // Give tool to player
        Map<Integer, ItemStack> leftover = target.getInventory().addItem(tool);
        if (!leftover.isEmpty()) {
            target.getWorld().dropItemNaturally(target.getLocation(), tool);
            target.sendMessage("§eYour inventory was full, tool dropped at your location!");
        }

        plugin.getMessageUtil().sendMessage(sender, "tool_given",
                Map.of("player", target.getName(), "tool", toolType, "tier", String.valueOf(tier)));
        plugin.getMessageUtil().sendMessage(target, "tool_received",
                Map.of("tool", toolType, "tier", String.valueOf(tier)));

        return true;
    }

    private boolean handleGiveMax(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ghasttools.admin")) {
            plugin.getMessageUtil().sendMessage(sender, "no_permission");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage("§cUsage: /ghasttools givemax <player> <tool>");
            sender.sendMessage("§7Example: /ghasttools givemax Player pickaxe");
            sender.sendMessage("§7This gives a max tier tool with all enchantments at max level");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            plugin.getMessageUtil().sendMessage(sender, "player_not_found", Map.of("player", args[1]));
            return true;
        }

        String toolType = args[2].toLowerCase();

        // Validate tool type
        if (!Arrays.asList("pickaxe", "axe", "hoe").contains(toolType)) {
            sender.sendMessage("§cValid tool types: pickaxe, axe, hoe");
            return true;
        }

        ItemStack maxTool = plugin.getToolManager().createMaxTool(toolType);
        if (maxTool == null) {
            sender.sendMessage("§cFailed to create max tool! Check configuration.");
            return true;
        }

        // Give tool to player
        Map<Integer, ItemStack> leftover = target.getInventory().addItem(maxTool);
        if (!leftover.isEmpty()) {
            target.getWorld().dropItemNaturally(target.getLocation(), maxTool);
            target.sendMessage("§eYour inventory was full, max tool dropped at your location!");
        }

        sender.sendMessage("§aGave " + target.getName() + " a max " + toolType + " with all enchantments!");
        target.sendMessage("§aYou received a max " + toolType + " with all enchantments for testing!");

        return true;
    }

    private boolean handleUpgrade(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ghasttools.admin")) {
            plugin.getMessageUtil().sendMessage(sender, "no_permission");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage("§cUsage: /ghasttools upgrade <player> <tier>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            plugin.getMessageUtil().sendMessage(sender, "player_not_found", Map.of("player", args[1]));
            return true;
        }

        int tier;
        try {
            tier = Integer.parseInt(args[2]);
            if (tier < 1 || tier > 6) {
                sender.sendMessage("§cTier must be between 1 and 6!");
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§cTier must be a number!");
            return true;
        }

        ItemStack heldTool = target.getInventory().getItemInMainHand();
        if (!plugin.getToolManager().isGhastTool(heldTool)) {
            sender.sendMessage("§cPlayer must be holding a GhastTool!");
            return true;
        }

        int currentTier = plugin.getToolManager().getToolTier(heldTool);
        if (tier <= currentTier) {
            sender.sendMessage("§cNew tier must be higher than current tier (" + currentTier + ")!");
            return true;
        }

        plugin.getToolManager().upgradeTool(target, heldTool, tier);
        plugin.getMessageUtil().sendMessage(sender, "tool_upgraded_admin",
                Map.of("player", target.getName(), "tier", String.valueOf(tier)));

        return true;
    }

    private boolean handleEnchant(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ghasttools.admin")) {
            plugin.getMessageUtil().sendMessage(sender, "no_permission");
            return true;
        }

        if (args.length < 4) {
            sender.sendMessage("§cUsage: /ghasttools enchant <player> <enchantment> <level>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            plugin.getMessageUtil().sendMessage(sender, "player_not_found", Map.of("player", args[1]));
            return true;
        }

        String enchantment = args[2].toLowerCase();
        int level;

        try {
            level = Integer.parseInt(args[3]);
            if (level < 1 || level > 6) {
                sender.sendMessage("§cLevel must be between 1 and 6!");
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§cLevel must be a number!");
            return true;
        }

        ItemStack heldTool = target.getInventory().getItemInMainHand();
        if (!plugin.getToolManager().isGhastTool(heldTool)) {
            sender.sendMessage("§cPlayer must be holding a GhastTool!");
            return true;
        }

        // Check enchantment exists in config
        var enchantConfig = plugin.getEnchantmentManager().getEnchantmentConfig(enchantment);
        if (enchantConfig == null) {
            sender.sendMessage("§cUnknown enchantment: " + enchantment);
            return true;
        }

        if (level > enchantConfig.getMaxLevel()) {
            sender.sendMessage("§cMax level for " + enchantment + " is " + enchantConfig.getMaxLevel() + "!");
            return true;
        }

        plugin.getEnchantmentManager().applyEnchantment(heldTool, enchantment, level);
        plugin.getMessageUtil().sendMessage(target, "enchantment_received",
                Map.of("enchantment", enchantment, "level", String.valueOf(level)));

        return true;
    }

    private boolean handleRemoveEnchant(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ghasttools.admin")) {
            plugin.getMessageUtil().sendMessage(sender, "no_permission");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage("§cUsage: /ghasttools removeenchant <player> <enchantment>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            plugin.getMessageUtil().sendMessage(sender, "player_not_found", Map.of("player", args[1]));
            return true;
        }

        String enchantment = args[2].toLowerCase();

        ItemStack heldTool = target.getInventory().getItemInMainHand();
        if (!plugin.getToolManager().isGhastTool(heldTool)) {
            sender.sendMessage("§cPlayer must be holding a GhastTool!");
            return true;
        }

        Map<String, Integer> enchantments = plugin.getToolManager().getToolEnchantments(heldTool);
        if (!enchantments.containsKey(enchantment)) {
            sender.sendMessage("§cTool does not have the " + enchantment + " enchantment!");
            return true;
        }

        plugin.getEnchantmentManager().removeEnchantment(heldTool, enchantment);
        plugin.getMessageUtil().sendMessage(sender, "enchantment_removed",
                Map.of("enchantment", enchantment, "player", target.getName()));
        plugin.getMessageUtil().sendMessage(target, "enchantment_lost",
                Map.of("enchantment", enchantment));

        return true;
    }

    private boolean handleListEnchants(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ghasttools.admin")) {
            plugin.getMessageUtil().sendMessage(sender, "no_permission");
            return true;
        }

        sender.sendMessage("§6=== All Available Enchantments ===");
        sender.sendMessage("§7All enchantments now work on all tools!");

        List<String> enchantments = plugin.getEnchantmentManager().getAllEnchantmentNames();

        if (enchantments.isEmpty()) {
            sender.sendMessage("§7No enchantments configured.");
        } else {
            for (String enchant : enchantments) {
                var enchantConfig = plugin.getEnchantmentManager().getEnchantmentConfig(enchant);
                if (enchantConfig != null) {
                    String status = enchantConfig.isEnabled() ? "§aEnabled" : "§cDisabled";
                    sender.sendMessage("§7- §e" + enchant + " §7(Max Level: " + enchantConfig.getMaxLevel() +
                            ", " + status + "§7)");
                    sender.sendMessage("§8    " + enchantConfig.getDescription());
                } else {
                    sender.sendMessage("§7- §e" + enchant + " §c(Not configured)");
                }
            }
        }

        return true;
    }

    private boolean handleExportData(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ghasttools.admin")) {
            plugin.getMessageUtil().sendMessage(sender, "no_permission");
            return true;
        }

        String fileName = args.length > 1 ? args[1] : "ghasttools_export_" + System.currentTimeMillis() + ".json";

        sender.sendMessage("§eExporting data to " + fileName + "...");

        plugin.getDataManager().exportData(fileName).thenAccept(success -> {
            if (success) {
                plugin.getMessageUtil().sendMessage(sender, "data_exported", Map.of("file", fileName));
            } else {
                plugin.getMessageUtil().sendMessage(sender, "data_export_failed");
            }
        });

        return true;
    }

    private boolean handleImportData(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ghasttools.admin")) {
            plugin.getMessageUtil().sendMessage(sender, "no_permission");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§cUsage: /ghasttools importdata <file>");
            return true;
        }

        String fileName = args[1];
        sender.sendMessage("§eImporting data from " + fileName + "...");

        plugin.getDataManager().importData(fileName).thenAccept(success -> {
            if (success) {
                plugin.getMessageUtil().sendMessage(sender, "data_imported", Map.of("file", fileName));
            } else {
                plugin.getMessageUtil().sendMessage(sender, "data_import_failed");
            }
        });

        return true;
    }

    private boolean handleCleanData(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ghasttools.admin")) {
            plugin.getMessageUtil().sendMessage(sender, "no_permission");
            return true;
        }

        // Check for "none" option to disable data cleanup
        if (args.length > 1 && args[1].equalsIgnoreCase("none")) {
            sender.sendMessage("§eData cleanup disabled. No data will be removed.");
            return true;
        }

        int days = 30; // Default
        if (args.length > 1) {
            try {
                days = Integer.parseInt(args[1]);
                if (days < 1) {
                    sender.sendMessage("§cDays must be at least 1!");
                    return true;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage("§cDays must be a number or 'none' to disable cleanup!");
                return true;
            }
        }

        final int cleanupDays = days;
        sender.sendMessage("§eCleaning data for players offline for " + cleanupDays + "+ days...");

        plugin.getDataManager().cleanupOldData(cleanupDays).thenAccept(cleaned -> {
            plugin.getMessageUtil().sendMessage(sender, "data_cleaned",
                    Map.of("count", String.valueOf(cleaned), "days", String.valueOf(cleanupDays)));
        });

        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players!");
            return true;
        }

        Player player = (Player) sender;
        if (!player.hasPermission("ghasttools.player.info")) {
            plugin.getMessageUtil().sendMessage(player, "no_permission");
            return true;
        }

        ItemStack heldTool = player.getInventory().getItemInMainHand();
        if (!plugin.getToolManager().isGhastTool(heldTool)) {
            plugin.getMessageUtil().sendMessage(player, "not_holding_tool");
            return true;
        }

        showToolInfo(player, heldTool);
        return true;
    }

    private void showToolInfo(Player player, ItemStack tool) {
        try {
            String toolType = plugin.getToolManager().getToolType(tool);
            int toolTier = plugin.getToolManager().getToolTier(tool);
            String toolId = plugin.getToolManager().getToolId(tool);
            Map<String, Integer> enchantments = plugin.getToolManager().getToolEnchantments(tool);

            player.sendMessage("§6=== Tool Information ===");
            player.sendMessage("§7Type: §e" + (toolType != null ? toolType : "Unknown"));
            player.sendMessage("§7Tier: §e" + toolTier);
            player.sendMessage("§7Tool ID: §e" + (toolId != null ? toolId : "Unknown"));
            player.sendMessage("§7Unbreakable: §aYes");

            if (enchantments.isEmpty()) {
                player.sendMessage("§7Enchantments: §cNone");
            } else {
                player.sendMessage("§7Enchantments:");
                for (Map.Entry<String, Integer> entry : enchantments.entrySet()) {
                    var enchantConfig = plugin.getEnchantmentManager().getEnchantmentConfig(entry.getKey());
                    String status = (enchantConfig != null && enchantConfig.isEnabled()) ? "§a" : "§c";
                    player.sendMessage("§8  - " + status + entry.getKey() + " §7Level " + entry.getValue());
                }
            }

            // Show material and custom model data
            player.sendMessage("§7Material: §e" + tool.getType().name());
            if (tool.hasItemMeta() && tool.getItemMeta().hasCustomModelData()) {
                player.sendMessage("§7Custom Model: §e" + tool.getItemMeta().getCustomModelData());
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error showing tool info", e);
            player.sendMessage("§cError displaying tool information");
        }
    }

    private boolean handleCooldowns(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players!");
            return true;
        }

        Player player = (Player) sender;
        if (!player.hasPermission("ghasttools.player.cooldowns")) {
            plugin.getMessageUtil().sendMessage(player, "no_permission");
            return true;
        }

        // Load player data asynchronously
        plugin.getDataManager().loadPlayerData(player.getUniqueId()).thenAccept(playerData -> {
            Map<String, Long> cooldowns = playerData.getEnchantmentCooldowns();

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                player.sendMessage("§6=== Active Cooldowns ===");

                boolean hasCooldowns = false;
                for (Map.Entry<String, Long> entry : cooldowns.entrySet()) {
                    long remaining = entry.getValue() - System.currentTimeMillis();
                    if (remaining > 0) {
                        hasCooldowns = true;
                        int seconds = (int) (remaining / 1000);
                        int minutes = seconds / 60;
                        seconds = seconds % 60;

                        String timeFormat = minutes > 0 ?
                                String.format("%dm %ds", minutes, seconds) :
                                String.format("%ds", seconds);

                        player.sendMessage("§7" + entry.getKey() + ": §e" + timeFormat);
                    }
                }

                if (!hasCooldowns) {
                    player.sendMessage("§7No active cooldowns");
                }
            });
        });

        return true;
    }

    private boolean handleStats(CommandSender sender, String[] args) {
        Player target;

        if (args.length > 1 && sender.hasPermission("ghasttools.admin")) {
            // Admin viewing another player's stats
            target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                plugin.getMessageUtil().sendMessage(sender, "player_not_found", Map.of("player", args[1]));
                return true;
            }
        } else {
            // Player viewing their own stats
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cThis command can only be used by players!");
                return true;
            }

            target = (Player) sender;
            if (!target.hasPermission("ghasttools.player.stats")) {
                plugin.getMessageUtil().sendMessage(target, "no_permission");
                return true;
            }
        }

        // Load player data asynchronously
        plugin.getDataManager().loadPlayerData(target.getUniqueId()).thenAccept(playerData -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sender.sendMessage("§6=== " + target.getName() + "'s GhastTools Stats ===");
                sender.sendMessage("§7Blocks Broken: §e" + formatNumber(playerData.getTotalBlocksBroken()));
                sender.sendMessage("§7XP Earned: §e" + formatDecimal(playerData.getTotalXpEarned()));
                sender.sendMessage("§7Essence Earned: §e" + formatDecimal(playerData.getTotalEssenceEarned()));
                sender.sendMessage("§7Meteors Spawned: §e" + formatNumber(playerData.getTotalMeteorsSpawned()));
                sender.sendMessage("§7Airstrikes: §e" + formatNumber(playerData.getTotalAirstrikes()));
                sender.sendMessage("§7Favorite Tool: §e" + (playerData.getFavoriteToolType().isEmpty() ? "none" : playerData.getFavoriteToolType()));
                sender.sendMessage("§7Last Enchant Used: §e" + (playerData.getLastEnchantUsed().isEmpty() ? "none" : playerData.getLastEnchantUsed()));

                // Tool usage breakdown
                if (!playerData.getToolUsageCount().isEmpty()) {
                    sender.sendMessage("§7Tool Usage:");
                    for (Map.Entry<String, Integer> entry : playerData.getToolUsageCount().entrySet()) {
                        sender.sendMessage("§8  - §e" + entry.getKey() + ": §7" + formatNumber(entry.getValue()));
                    }
                }
            });
        });

        return true;
    }

    private boolean handleReload(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ghasttools.admin")) {
            plugin.getMessageUtil().sendMessage(sender, "no_permission");
            return true;
        }

        sender.sendMessage("§eReloading GhastTools...");

        // Run reload asynchronously to avoid blocking
        CompletableFuture.runAsync(() -> {
            boolean success = plugin.reloadPlugin();

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (success) {
                    plugin.getMessageUtil().sendMessage(sender, "plugin_reloaded");
                } else {
                    sender.sendMessage("§cFailed to reload plugin! Check console for errors.");
                }
            });
        });

        return true;
    }

    private boolean handleDebug(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ghasttools.admin")) {
            plugin.getMessageUtil().sendMessage(sender, "no_permission");
            return true;
        }

        sender.sendMessage("§6=== GhastTools Debug Information ===");
        sender.sendMessage("§7Plugin Version: §e" + plugin.getDescription().getVersion());
        sender.sendMessage("§7Active Tasks: §e" + plugin.getActiveTaskCount());

        if (plugin.getProtocolLibHook() != null) {
            sender.sendMessage("§7Active Animations: §e" + plugin.getProtocolLibHook().getTotalActiveAnimations());
        }

        if (plugin.getWorldGuardHook() != null) {
            sender.sendMessage("§7WorldGuard Flags: §e" + plugin.getWorldGuardHook().getFlagCount());
        }

        if (plugin.getPlaceholderAPIHook() != null) {
            var stats = plugin.getPlaceholderAPIHook().getCacheStats();
            sender.sendMessage("§7PlaceholderAPI Cache: §e" + stats.get("size") + "/" + stats.get("capacity"));
        }

        sender.sendMessage("§7Message Cache Size: §e" + plugin.getMessageUtil().getCacheSize());
        sender.sendMessage("§7Is Reloading: §e" + plugin.isReloading());

        // Level system debug info
        if (plugin.getLevelsHandler() != null) {
            sender.sendMessage("§7Level System: §aConnected to GhastLevels");
        } else {
            sender.sendMessage("§7Level System: §cNot Available");
        }

        // ADDED: Milestone system debug info
        if (plugin.getMilestoneManager() != null) {
            sender.sendMessage("§7Milestone System: §aEnabled");
            sender.sendMessage("§7Configured Materials: §e" + plugin.getMilestoneManager().getConfiguredMaterials().size());
            sender.sendMessage("§7Tracked Blocks: §e" + plugin.getMilestoneManager().getTrackedBlocks().size());
        } else {
            sender.sendMessage("§7Milestone System: §cDisabled");
        }

        // ADDED: Profile system debug info
        if (plugin.getProfileCommand() != null) {
            sender.sendMessage("§7Profile System: §aEnabled");
        } else {
            sender.sendMessage("§7Profile System: §cDisabled");
        }

        return true;
    }

    private boolean handleTest(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ghasttools.admin")) {
            plugin.getMessageUtil().sendMessage(sender, "no_permission");
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players!");
            return true;
        }

        Player player = (Player) sender;

        if (args.length < 2) {
            sender.sendMessage("§cUsage: /ghasttools test <type>");
            sender.sendMessage("§7Types: colors, enchant, animations, maxtools, meteor, level, playerlevel, milestone, profile");
            return true;
        }

        String testType = args[1].toLowerCase();

        switch (testType) {
            case "colors":
                testColors(player);
                break;
            case "enchant":
                testEnchantment(player, args);
                break;
            case "animations":
                testAnimations(player);
                break;
            case "maxtools":
                testMaxTools(player);
                break;
            case "meteor":
                testMeteorSpawn(player);
                break;
            case "level":
            case "playerlevel":
                testPlayerLevel(player, args);
                break;
            case "milestone":
                testMilestoneSystem(player);
                break;
            case "profile":
                testProfileSystem(player, args);
                break;
            default:
                sender.sendMessage("§cUnknown test type: " + testType);
                break;
        }

        return true;
    }

    /**
     * ADDED: Test milestone system functionality
     */
    private void testMilestoneSystem(Player player) {
        player.sendMessage("§eTesting milestone system...");

        if (plugin.getMilestoneManager() != null) {
            player.sendMessage("§aMilestone manager: Available");
            player.sendMessage("§7Configured materials: " + plugin.getMilestoneManager().getConfiguredMaterials().size());
            player.sendMessage("§7Tracked blocks: " + plugin.getMilestoneManager().getTrackedBlocks().size());

            // Test milestone data
            var milestoneData = plugin.getMilestoneManager().getPlayerMilestoneData(player.getUniqueId());
            player.sendMessage("§7Your milestone data loaded successfully");

            // Test opening milestone GUI
            if (plugin.getMilestoneGui() != null) {
                boolean success = plugin.getMilestoneGui().openMainMilestoneGui(player);
                player.sendMessage("§7Milestone GUI test: " + (success ? "§aSuccess" : "§cFailed"));
            } else {
                player.sendMessage("§cMilestone GUI not available");
            }
        } else {
            player.sendMessage("§cMilestone manager not available");
        }
    }

    /**
     * ADDED: Test profile system functionality
     */
    private void testProfileSystem(Player player, String[] args) {
        player.sendMessage("§eTesting profile system...");

        if (plugin.getProfileCommand() != null) {
            player.sendMessage("§aProfile command: Available");

            // Test opening own profile
            boolean success = plugin.getProfileCommand().openProfileGui(player, player);
            player.sendMessage("§7Profile GUI test: " + (success ? "§aSuccess" : "§cFailed"));

            // Test with another player if specified
            if (args.length > 2) {
                Player target = Bukkit.getPlayer(args[2]);
                if (target != null) {
                    boolean targetSuccess = plugin.getProfileCommand().openProfileGui(player, target);
                    player.sendMessage("§7Target profile test: " + (targetSuccess ? "§aSuccess" : "§cFailed"));
                } else {
                    player.sendMessage("§cTarget player not found: " + args[2]);
                }
            }
        } else {
            player.sendMessage("§cProfile command not available");
        }
    }

    /**
     * Test player level functionality with comprehensive level checking
     */
    private void testPlayerLevel(Player player, String[] args) {
        Player target;

        // Allow admin to check other player's level
        if (args.length > 2 && player.hasPermission("ghasttools.admin")) {
            target = Bukkit.getPlayer(args[2]);
            if (target == null) {
                player.sendMessage("§cPlayer not found: " + args[2]);
                return;
            }
        } else {
            target = player;
        }

        player.sendMessage("§eTesting level system for " + target.getName() + "...");

        if (plugin.getLevelsHandler() != null) {
            // Test async level getting
            plugin.getLevelsHandler().getPlayerLevelAsync(target).thenAccept(level -> {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§a" + target.getName() + "'s current level: §e" + level);

                    // Test level requirements for different tiers
                    String[] toolTypes = {"pickaxe", "axe", "hoe"};
                    for (String toolType : toolTypes) {
                        player.sendMessage("§7" + toolType + " tier access:");
                        for (int tier = 1; tier <= 6; tier++) {
                            boolean canUse = plugin.getToolManager().canUseToolTier(target, toolType, tier);
                            String status = canUse ? "§aYes" : "§cNo";
                            var toolConfig = plugin.getToolManager().getToolConfigs().get(toolType);
                            int requiredLevel = 1;
                            if (toolConfig != null && toolConfig.getTier(tier) != null) {
                                requiredLevel = toolConfig.getTier(tier).getLevelRequirement();
                            }
                            player.sendMessage("§8  Tier " + tier + " (req: " + requiredLevel + "): " + status);
                        }
                    }

                    // Test tool validation with current held tool
                    ItemStack heldTool = target.getInventory().getItemInMainHand();
                    if (plugin.getToolManager().isGhastTool(heldTool)) {
                        String toolType = plugin.getToolManager().getToolType(heldTool);
                        int toolTier = plugin.getToolManager().getToolTier(heldTool);
                        boolean canUseHeld = plugin.getToolManager().canUseToolTier(target, toolType, toolTier);

                        player.sendMessage("§7Current held tool: §e" + toolType + " Tier " + toolTier);
                        player.sendMessage("§7Can use held tool: " + (canUseHeld ? "§aYes" : "§cNo"));
                    } else {
                        player.sendMessage("§7" + target.getName() + " is not holding a GhastTool");
                    }
                });
            }).exceptionally(throwable -> {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§cError getting player level: " + throwable.getMessage());
                });
                return null;
            });
        } else {
            player.sendMessage("§cLevels handler not available!");
            player.sendMessage("§7This means the GhastLevels plugin is not loaded or accessible.");
        }
    }

    /**
     * Test meteor spawning specifically
     */
    private void testMeteorSpawn(Player player) {
        player.sendMessage("§eTesting meteor spawn...");

        if (plugin.getProtocolLibHook() != null) {
            plugin.getProtocolLibHook().spawnMeteor(
                    player,
                    player.getLocation().add(10, 0, 10),
                    5,
                    "item_display",
                    "spiral",
                    "explosion",
                    "entity_generic_explode",
                    3, // level 3
                    4, // meteor size
                    () -> player.sendMessage("§aMeteor test completed!")
            );
        } else {
            player.sendMessage("§cProtocolLib hook not available!");
        }
    }

    private void testColors(Player player) {
        player.sendMessage("§6=== Color Test ===");

        // Test basic colors
        player.sendMessage(plugin.getMessageUtil().testColorTranslation("&aGreen &bBlue &cRed &dPink"));

        // Test hex colors
        player.sendMessage(plugin.getMessageUtil().testColorTranslation("&#FF0000Red &#00FF00Green &#0000FFBlue"));

        // Test RGB colors
        player.sendMessage(plugin.getMessageUtil().testColorTranslation("&{255,0,0}RGB Red &{0,255,0}RGB Green"));

        // Test gradient (if supported)
        player.sendMessage(plugin.getMessageUtil().testColorTranslation("&[gradient:FF0000:0000FF]Gradient Text&[/gradient]"));
    }

    private void testEnchantment(Player player, String[] args) {
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!plugin.getToolManager().isGhastTool(tool)) {
            player.sendMessage("§cYou must hold a GhastTool!");
            return;
        }

        if (args.length < 3) {
            player.sendMessage("§cUsage: /ghasttools test enchant <enchantment>");
            return;
        }

        String enchantName = args[2].toLowerCase();
        player.sendMessage("§eForcing trigger of " + enchantName + " enchantment...");

        // Create a fake block break event for testing
        org.bukkit.event.block.BlockBreakEvent fakeEvent = new org.bukkit.event.block.BlockBreakEvent(
                player.getLocation().getBlock(), player
        );

        plugin.getEnchantmentManager().triggerEnchantment(player, tool, enchantName, fakeEvent);
    }

    private void testAnimations(Player player) {
        if (plugin.getProtocolLibHook() != null) {
            player.sendMessage("§eTesting airstrike animation...");
            plugin.getProtocolLibHook().spawnAirstrike(
                    player,
                    player.getLocation().add(5, 0, 5),
                    5,
                    3,
                    "scatter",
                    "explosion",
                    "entity_tnt_primed",
                    () -> player.sendMessage("§aAirstrike test completed!")
            );
        } else {
            player.sendMessage("§cProtocolLib hook not available!");
        }
    }

    private void testMaxTools(Player player) {
        player.sendMessage("§eTesting max tools creation...");

        String[] toolTypes = {"pickaxe", "axe", "hoe"};
        for (String toolType : toolTypes) {
            ItemStack maxTool = plugin.getToolManager().createMaxTool(toolType);
            if (maxTool != null) {
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(maxTool);
                if (!leftover.isEmpty()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), maxTool);
                }
                player.sendMessage("§aCreated max " + toolType + " with all enchantments!");
            } else {
                player.sendMessage("§cFailed to create max " + toolType);
            }
        }
    }

    private boolean handleVersion(CommandSender sender, String[] args) {
        sender.sendMessage("§6=== GhastTools Version Information ===");
        sender.sendMessage("§7Plugin Version: §e" + plugin.getDescription().getVersion());
        sender.sendMessage("§7Authors: §e" + String.join(", ", plugin.getDescription().getAuthors()));
        sender.sendMessage("§7Server Version: §e" + plugin.getServer().getVersion());
        sender.sendMessage("§7Bukkit Version: §e" + plugin.getServer().getBukkitVersion());

        // Check dependencies
        sender.sendMessage("§7Dependencies:");
        sender.sendMessage("§8  - ProtocolLib: " + (plugin.getServer().getPluginManager().getPlugin("ProtocolLib") != null ? "§aFound" : "§cMissing"));
        sender.sendMessage("§8  - WorldGuard: " + (plugin.getServer().getPluginManager().getPlugin("WorldGuard") != null ? "§aFound" : "§7Not found"));
        sender.sendMessage("§8  - PlaceholderAPI: " + (plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null ? "§aFound" : "§7Not found"));
        sender.sendMessage("§8  - GhastLevels: " + (plugin.getServer().getPluginManager().getPlugin("GhastLevels") != null ? "§aFound" : "§7Not found"));

        return true;
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage("§6=== GhastTools Commands ===");

        if (sender.hasPermission("ghasttools.admin")) {
            sender.sendMessage("§e/ghasttools give <player> <tool> <tier> [enchants] §7- Give a tool");
            sender.sendMessage("§e/ghasttools givemax <player> <tool> §7- Give max tool with all enchantments");
            sender.sendMessage("§e/ghasttools upgrade <player> <tier> §7- Upgrade held tool");
            sender.sendMessage("§e/ghasttools upgradeamount <player> <amount> §7- Upgrade tool by X tiers");
            sender.sendMessage("§e/ghasttools enchant <player> <enchantment> <level> §7- Apply enchantment");
            sender.sendMessage("§e/ghasttools enchantamount <player> <enchant> <amount> §7- Upgrade enchant by X levels");
            sender.sendMessage("§e/ghasttools removeenchant <player> <enchantment> §7- Remove enchantment");
            sender.sendMessage("§e/ghasttools listenchants §7- List all available enchantments");
            sender.sendMessage("§e/ghasttools exportdata [file] §7- Export player data");
            sender.sendMessage("§e/ghasttools importdata <file> §7- Import player data");
            sender.sendMessage("§e/ghasttools cleandata [days|none] §7- Clean old data");
            sender.sendMessage("§e/ghasttools stats [player] §7- View player statistics");
            sender.sendMessage("§e/ghasttools milestone <player> §7- View player milestone profile");
            sender.sendMessage("§e/ghasttools debug §7- Debug information");
            sender.sendMessage("§e/ghasttools test <type> §7- Test features (level, milestone, profile, etc.)");
            sender.sendMessage("§e/ghasttools reload §7- Reload plugin");
        }

        if (sender.hasPermission("ghasttools.player.info")) {
            sender.sendMessage("§e/ghasttools info §7- Show tool information");
        }

        if (sender.hasPermission("ghasttools.player.cooldowns")) {
            sender.sendMessage("§e/ghasttools cooldowns §7- Show active cooldowns");
        }

        if (sender.hasPermission("ghasttools.player.stats")) {
            sender.sendMessage("§e/ghasttools stats §7- Show your statistics");
        }

        sender.sendMessage("§e/ghasttools version §7- Show version information");
        sender.sendMessage("§e/ghasttools help §7- Show this help message");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List<String> subCommands = new ArrayList<>();

            if (sender.hasPermission("ghasttools.admin")) {
                subCommands.addAll(Arrays.asList("give", "givemax", "maxtools", "upgrade", "upgradeamount", "upgradeamt",
                        "enchant", "addenchant", "enchantamount", "enchantamt", "removeenchant", "delenchant",
                        "listenchants", "enchants", "exportdata", "importdata", "cleandata", "milestone", "reload", "debug", "test"));
            }

            if (sender.hasPermission("ghasttools.player.info")) {
                subCommands.addAll(Arrays.asList("info", "toolinfo"));
            }

            if (sender.hasPermission("ghasttools.player.cooldowns")) {
                subCommands.addAll(Arrays.asList("cooldowns", "cooldown"));
            }

            if (sender.hasPermission("ghasttools.player.stats")) {
                subCommands.addAll(Arrays.asList("stats", "statistics"));
            }

            subCommands.addAll(Arrays.asList("version", "ver", "help"));

            return subCommands.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "give":
            case "givemax":
            case "maxtools":
            case "upgrade":
            case "upgradeamount":
            case "upgradeamt":
            case "enchant":
            case "addenchant":
            case "enchantamount":
            case "enchantamt":
            case "removeenchant":
            case "delenchant":
            case "milestone":
                if (args.length == 2) {
                    return Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                } else if (args.length == 3 && (subCommand.equals("give") || subCommand.equals("givemax") || subCommand.equals("maxtools"))) {
                    return Arrays.asList("pickaxe", "axe", "hoe").stream()
                            .filter(tool -> tool.toLowerCase().startsWith(args[2].toLowerCase()))
                            .collect(Collectors.toList());
                } else if (args.length == 3 && (subCommand.equals("enchant") || subCommand.equals("addenchant") ||
                        subCommand.equals("enchantamount") || subCommand.equals("enchantamt") ||
                        subCommand.equals("removeenchant") || subCommand.equals("delenchant"))) {
                    return plugin.getEnchantmentManager().getAllEnchantmentNames().stream()
                            .filter(enchant -> enchant.toLowerCase().startsWith(args[2].toLowerCase()))
                            .collect(Collectors.toList());
                } else if (args.length == 4 && (subCommand.equals("give") || subCommand.equals("upgrade") ||
                        subCommand.equals("upgradeamount") || subCommand.equals("upgradeamt"))) {
                    if (subCommand.contains("amount")) {
                        return Arrays.asList("1", "2", "3", "4", "5");
                    } else {
                        return Arrays.asList("1", "2", "3", "4", "5", "6");
                    }
                } else if (args.length == 4 && (subCommand.equals("enchant") || subCommand.equals("addenchant") ||
                        subCommand.equals("enchantamount") || subCommand.equals("enchantamt"))) {
                    if (subCommand.contains("amount")) {
                        return Arrays.asList("1", "2", "3", "4", "5", "6");
                    } else {
                        return Arrays.asList("1", "2", "3", "4", "5", "6");
                    }
                }
                break;

            case "stats":
            case "statistics":
                if (args.length == 2 && sender.hasPermission("ghasttools.admin")) {
                    return Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                }
                break;

            case "cleandata":
                if (args.length == 2) {
                    return Arrays.asList("7", "14", "30", "60", "90", "none");
                }
                break;

            case "test":
                if (args.length == 2) {
                    return Arrays.asList("colors", "enchant", "animations", "maxtools", "meteor", "level", "playerlevel", "milestone", "profile").stream()
                            .filter(type -> type.toLowerCase().startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                } else if (args.length == 3) {
                    if (args[1].equalsIgnoreCase("enchant")) {
                        return plugin.getEnchantmentManager().getAllEnchantmentNames().stream()
                                .filter(enchant -> enchant.toLowerCase().startsWith(args[2].toLowerCase()))
                                .collect(Collectors.toList());
                    } else if (args[1].equalsIgnoreCase("level") || args[1].equalsIgnoreCase("playerlevel") ||
                            args[1].equalsIgnoreCase("profile")) {
                        return Bukkit.getOnlinePlayers().stream()
                                .map(Player::getName)
                                .filter(name -> name.toLowerCase().startsWith(args[2].toLowerCase()))
                                .collect(Collectors.toList());
                    }
                }
                break;
        }

        return completions;
    }

    // Utility methods
    private String formatNumber(long number) {
        if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000.0);
        } else if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000.0);
        } else {
            return String.valueOf(number);
        }
    }

    private String formatDecimal(double number) {
        if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000.0);
        } else if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000.0);
        } else {
            return String.format("%.1f", number);
        }
    }
}