package com.ghasttools.commands;

import com.ghasttools.GhastToolsPlugin;
import com.ghasttools.milestones.MilestoneData;
import com.ghasttools.milestones.MilestoneManager;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * ENHANCED: Thread-safe Profile command handler with configurable GUI layout, custom item names, and proper error handling
 */
public class ProfileCommand implements CommandExecutor, TabCompleter {

    private final GhastToolsPlugin plugin;
    private final Map<UUID, String> openProfiles;
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);

    // GUI titles
    private static final String PROFILE_GUI_TITLE = "§6§lPlayer Milestones";

    // Offline placeholder replacement
    private static final String OFFLINE_PLACEHOLDER = "§7Player is currently offline";

    public ProfileCommand(GhastToolsPlugin plugin) {
        this.plugin = plugin;
        this.openProfiles = new ConcurrentHashMap<>();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players!");
            return true;
        }

        Player viewer = (Player) sender;

        // Check permission
        if (!viewer.hasPermission("ghasttools.profile")) {
            viewer.sendMessage("§cYou don't have permission to use this command!");
            return true;
        }

        // Check if plugin is shutting down
        if (isShuttingDown.get() || plugin.isShuttingDown()) {
            viewer.sendMessage("§cProfile system is currently unavailable!");
            return true;
        }

        // Determine target player
        Player target;
        if (args.length == 0) {
            // View own profile
            target = viewer;
        } else {
            // View another player's profile
            if (!viewer.hasPermission("ghasttools.profile.others")) {
                viewer.sendMessage("§cYou don't have permission to view other players' profiles!");
                return true;
            }

            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                // Try to find offline player
                return handleOfflinePlayer(viewer, args[0]);
            }
        }

        // Open profile GUI
        CompletableFuture.supplyAsync(() -> openProfileGui(viewer, target), plugin.getAsyncExecutor())
                .thenAccept(success -> {
                    if (!success) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (viewer.isOnline()) {
                                viewer.sendMessage("§cFailed to open profile! Please try again.");
                            }
                        });
                    }
                })
                .exceptionally(throwable -> {
                    plugin.getLogger().log(Level.WARNING, "Error opening profile GUI", throwable);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (viewer.isOnline()) {
                            viewer.sendMessage("§cAn error occurred while opening the profile!");
                        }
                    });
                    return null;
                });

        return true;
    }

    /**
     * ENHANCED: Handle offline player profile viewing with proper error handling
     */
    private boolean handleOfflinePlayer(Player viewer, String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            viewer.sendMessage("§cInvalid player name!");
            return true;
        }

        viewer.sendMessage("§eSearching for player: " + playerName + "...");

        // Search for offline player asynchronously
        CompletableFuture.supplyAsync(() -> {
                    try {
                        @SuppressWarnings("deprecation")
                        org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
                        return offlinePlayer;
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING, "Error searching for offline player: " + playerName, e);
                        return null;
                    }
                }, plugin.getAsyncExecutor())
                .thenAccept(offlinePlayer -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!viewer.isOnline()) return;

                        if (offlinePlayer == null) {
                            viewer.sendMessage("§cError searching for player: " + playerName);
                            return;
                        }

                        if (offlinePlayer.hasPlayedBefore()) {
                            CompletableFuture.supplyAsync(() -> openOfflineProfileGui(viewer, offlinePlayer), plugin.getAsyncExecutor())
                                    .thenAccept(success -> {
                                        if (!success) {
                                            Bukkit.getScheduler().runTask(plugin, () -> {
                                                if (viewer.isOnline()) {
                                                    viewer.sendMessage("§cFailed to load profile for " + playerName + "!");
                                                }
                                            });
                                        }
                                    })
                                    .exceptionally(throwable -> {
                                        plugin.getLogger().log(Level.WARNING, "Error opening offline profile", throwable);
                                        Bukkit.getScheduler().runTask(plugin, () -> {
                                            if (viewer.isOnline()) {
                                                viewer.sendMessage("§cFailed to load profile for " + playerName + "!");
                                            }
                                        });
                                        return null;
                                    });
                        } else {
                            viewer.sendMessage("§cPlayer '" + playerName + "' has never joined this server!");
                        }
                    });
                })
                .exceptionally(throwable -> {
                    plugin.getLogger().log(Level.WARNING, "Error in offline player search", throwable);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (viewer.isOnline()) {
                            viewer.sendMessage("§cError searching for player: " + playerName);
                        }
                    });
                    return null;
                });

        return true;
    }

    /**
     * ENHANCED: Open profile GUI for online player using configurable layout with thread safety
     */
    public boolean openProfileGui(Player viewer, Player target) {
        if (viewer == null || target == null || isShuttingDown.get()) {
            return false;
        }

        try {
            // Get profile GUI configuration with null check
            MilestoneManager milestoneManager = plugin.getMilestoneManager();
            if (milestoneManager == null) {
                plugin.getLogger().warning("Milestone manager not available!");
                return false;
            }

            MilestoneManager.ProfileGuiConfig profileConfig = milestoneManager.getProfileGuiConfig();
            if (profileConfig == null) {
                plugin.getLogger().warning("Profile GUI configuration not found!");
                return false;
            }

            // Use configured GUI size
            int size = profileConfig.getSize();
            Inventory gui = Bukkit.createInventory(null, size, PROFILE_GUI_TITLE);

            UUID targetUUID = target.getUniqueId();
            MilestoneData milestoneData = milestoneManager.getPlayerMilestoneData(targetUUID);

            // Populate GUI with configurable layout
            populateConfigurableProfileGui(gui, target, milestoneData, profileConfig, true);

            // Open GUI on main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (viewer.isOnline() && !isShuttingDown.get()) {
                    viewer.openInventory(gui);
                    openProfiles.put(viewer.getUniqueId(), PROFILE_GUI_TITLE);
                }
            });

            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error opening profile GUI for " + target.getName(), e);
            return false;
        }
    }

    /**
     * ENHANCED: Open profile GUI for offline player using configurable layout with thread safety
     */
    public boolean openOfflineProfileGui(Player viewer, org.bukkit.OfflinePlayer target) {
        if (viewer == null || target == null || isShuttingDown.get()) {
            return false;
        }

        try {
            // Get profile GUI configuration with null check
            MilestoneManager milestoneManager = plugin.getMilestoneManager();
            if (milestoneManager == null) {
                plugin.getLogger().warning("Milestone manager not available!");
                return false;
            }

            MilestoneManager.ProfileGuiConfig profileConfig = milestoneManager.getProfileGuiConfig();
            if (profileConfig == null) {
                plugin.getLogger().warning("Profile GUI configuration not found!");
                return false;
            }

            // Use configured GUI size
            int size = profileConfig.getSize();
            Inventory gui = Bukkit.createInventory(null, size, PROFILE_GUI_TITLE);

            UUID targetUUID = target.getUniqueId();
            MilestoneData milestoneData = milestoneManager.getPlayerMilestoneData(targetUUID);

            // Populate GUI with configurable layout for offline player
            populateConfigurableOfflineProfileGui(gui, target, milestoneData, profileConfig);

            // Open GUI on main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (viewer.isOnline() && !isShuttingDown.get()) {
                    viewer.openInventory(gui);
                    openProfiles.put(viewer.getUniqueId(), PROFILE_GUI_TITLE);
                }
            });

            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error opening offline profile GUI for " + target.getName(), e);
            return false;
        }
    }

    /**
     * ENHANCED: Populate profile GUI using configurable layout with proper null checks
     */
    private void populateConfigurableProfileGui(Inventory gui, Player target, MilestoneData milestoneData,
                                                MilestoneManager.ProfileGuiConfig profileConfig, boolean isOnline) {

        if (gui == null || target == null || milestoneData == null || profileConfig == null) {
            plugin.getLogger().warning("Null parameters in populateConfigurableProfileGui");
            return;
        }

        try {
            // Place player head in configured slot with configured lore
            populateConfigurablePlayerHead(gui, target, profileConfig, isOnline);

            // Place category items in configured slots with custom names
            populateConfigurableFarmingCategory(gui, target, milestoneData, profileConfig);
            populateConfigurableMiningCategory(gui, target, milestoneData, profileConfig);
            populateConfigurableForagingCategory(gui, target, milestoneData, profileConfig);

            // Place misc info item if configured
            if (profileConfig.getMiscInfoConfig() != null) {
                populateConfigurableMiscInfo(gui, target, profileConfig);
            }

            // Fill empty slots with glass panes
            fillEmptySlots(gui);

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error populating configurable profile GUI", e);
        }
    }

    /**
     * ENHANCED: Populate profile GUI for offline player using configurable layout with proper null checks
     */
    private void populateConfigurableOfflineProfileGui(Inventory gui, org.bukkit.OfflinePlayer target,
                                                       MilestoneData milestoneData, MilestoneManager.ProfileGuiConfig profileConfig) {

        if (gui == null || target == null || milestoneData == null || profileConfig == null) {
            plugin.getLogger().warning("Null parameters in populateConfigurableOfflineProfileGui");
            return;
        }

        try {
            // Place player head in configured slot with configured lore (offline version)
            populateConfigurableOfflinePlayerHead(gui, target, profileConfig);

            // Place category items in configured slots (offline version) with custom names
            populateConfigurableOfflineFarmingCategory(gui, target, milestoneData, profileConfig);
            populateConfigurableOfflineMiningCategory(gui, target, milestoneData, profileConfig);
            populateConfigurableOfflineForagingCategory(gui, target, milestoneData, profileConfig);

            // Place misc info item if configured (offline version)
            if (profileConfig.getMiscInfoConfig() != null) {
                populateConfigurableOfflineMiscInfo(gui, target, profileConfig);
            }

            // Fill empty slots with glass panes
            fillEmptySlots(gui);

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error populating configurable offline profile GUI", e);
        }
    }

    /**
     * ENHANCED: Populate player head using configured slot and lore with PlaceholderAPI support and null safety
     */
    private void populateConfigurablePlayerHead(Inventory gui, Player target,
                                                MilestoneManager.ProfileGuiConfig profileConfig, boolean isOnline) {
        try {
            ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta skullMeta = (SkullMeta) playerHead.getItemMeta();

            if (skullMeta != null) {
                skullMeta.setOwningPlayer(target);
                skullMeta.setDisplayName("§6§l" + target.getName() + "'s Profile");

                List<String> lore = new ArrayList<>();

                // Add configured lore with placeholder support
                List<String> configuredLore = profileConfig.getPlayerHeadLore();
                if (configuredLore != null) {
                    for (String line : configuredLore) {
                        if (line != null) {
                            String processedLine = ChatColor.translateAlternateColorCodes('&', line);

                            // Process placeholders if PlaceholderAPI is available and player is online
                            if (isOnline && plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                                try {
                                    processedLine = PlaceholderAPI.setPlaceholders(target, processedLine);
                                } catch (Exception e) {
                                    plugin.getLogger().fine("Error processing placeholder: " + e.getMessage());
                                    // Keep the original line if placeholder processing fails
                                }
                            }

                            lore.add(processedLine);
                        }
                    }
                }

                // Add basic player info
                lore.add("");
                lore.add("§7Player: §e" + target.getName());

                if (target.getFirstPlayed() > 0) {
                    try {
                        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy 'at' HH:mm");
                        String joinDate = sdf.format(new Date(target.getFirstPlayed()));
                        lore.add("§7First joined: §e" + joinDate);
                    } catch (Exception e) {
                        lore.add("§7First joined: §7Unknown");
                    }
                }

                if (isOnline) {
                    lore.add("§7Status: §aOnline");


                    if (plugin.getLevelsHandler() != null) {
                        try {
                            int level = plugin.getLevelsHandler().getPlayerLevel(target);
                            lore.add("§7Level: §e" + level);
                        } catch (Exception e) {
                            plugin.getLogger().fine("Error getting player level: " + e.getMessage());
                        }
                    }
                } else {
                    if (target.getLastPlayed() > 0) {
                        try {
                            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy 'at' HH:mm");
                            String lastOnline = sdf.format(new Date(target.getLastPlayed()));
                            lore.add("§7Last online: §e" + lastOnline);
                        } catch (Exception e) {
                            lore.add("§7Last online: §7Unknown");
                        }
                    }
                    lore.add("§7Status: §cOffline");
                }

                skullMeta.setLore(lore);
                playerHead.setItemMeta(skullMeta);
            }

            // Place in configured slot with bounds checking
            int slot = profileConfig.getPlayerHeadSlot();
            if (slot >= 0 && slot < gui.getSize()) {
                gui.setItem(slot, playerHead);
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error creating player head item", e);
        }
    }

    /**
     * ENHANCED: Populate offline player head with proper null handling and offline placeholder replacement
     */
    private void populateConfigurableOfflinePlayerHead(Inventory gui, org.bukkit.OfflinePlayer target,
                                                       MilestoneManager.ProfileGuiConfig profileConfig) {
        try {
            ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta skullMeta = (SkullMeta) playerHead.getItemMeta();

            if (skullMeta != null) {
                skullMeta.setOwningPlayer(target);
                String playerName = target.getName() != null ? target.getName() : "Unknown";
                skullMeta.setDisplayName("§6§l" + playerName + "'s Profile");

                List<String> lore = new ArrayList<>();

                // Add configured lore with offline placeholder replacement
                List<String> configuredLore = profileConfig.getPlayerHeadLore();
                if (configuredLore != null) {
                    for (String line : configuredLore) {
                        if (line != null) {
                            String processedLine = ChatColor.translateAlternateColorCodes('&', line);

                            // Replace any remaining placeholders with offline message
                            if (processedLine.contains("%")) {
                                processedLine = OFFLINE_PLACEHOLDER;
                            }

                            lore.add(processedLine);
                        }
                    }
                }

                // Add basic offline player info
                lore.add("");
                lore.add("§7Player: §e" + playerName);

                if (target.getFirstPlayed() > 0) {
                    try {
                        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy 'at' HH:mm");
                        String joinDate = sdf.format(new Date(target.getFirstPlayed()));
                        lore.add("§7First joined: §e" + joinDate);
                    } catch (Exception e) {
                        lore.add("§7First joined: §7Unknown");
                    }
                }

                if (target.getLastPlayed() > 0) {
                    try {
                        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy 'at' HH:mm");
                        String lastOnline = sdf.format(new Date(target.getLastPlayed()));
                        lore.add("§7Last online: §e" + lastOnline);
                    } catch (Exception e) {
                        lore.add("§7Last online: §7Unknown");
                    }
                }

                lore.add("§7Status: §cOffline");

                skullMeta.setLore(lore);
                playerHead.setItemMeta(skullMeta);
            }

            // Place in configured slot with bounds checking
            int slot = profileConfig.getPlayerHeadSlot();
            if (slot >= 0 && slot < gui.getSize()) {
                gui.setItem(slot, playerHead);
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error creating offline player head item", e);
        }
    }

    /**
     * ENHANCED: Populate farming category with custom item names and proper null checks
     */
    private void populateConfigurableFarmingCategory(Inventory gui, Player target, MilestoneData milestoneData,
                                                     MilestoneManager.ProfileGuiConfig profileConfig) {
        try {
            ItemStack farmingItem = new ItemStack(Material.WHEAT);
            ItemMeta meta = farmingItem.getItemMeta();

            if (meta != null) {
                meta.setDisplayName("§6§lFarming Milestones");

                List<String> lore = new ArrayList<>();
                lore.add("§7Category: §efarming");
                lore.add("");

                MilestoneManager milestoneManager = plugin.getMilestoneManager();
                if (milestoneManager != null) {
                    List<Material> farmingMaterials = milestoneManager.getProfileCategoryMaterials("farming");

                    if (farmingMaterials == null || farmingMaterials.isEmpty()) {
                        lore.add("§7No materials configured");
                    } else {
                        for (Material material : farmingMaterials) {
                            if (material != null && milestoneData != null) {
                                // ENHANCED: Use combined progress for linked materials
                                long amount = milestoneManager.getCombinedProgress(target.getUniqueId(), material);

                                // ADDED: Use custom item name if available
                                String materialName = getCustomOrFormattedMaterialName("farming", material);
                                lore.add("§8" + materialName + ": §e" + formatNumber(amount));
                            }
                        }
                    }
                } else {
                    lore.add("§7Milestone system unavailable");
                }

                meta.setLore(lore);
                farmingItem.setItemMeta(meta);
            }

            // Place in configured slot with bounds checking
            int slot = profileConfig.getFarmingSlot();
            if (slot >= 0 && slot < gui.getSize()) {
                gui.setItem(slot, farmingItem);
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error creating farming category item", e);
        }
    }

    /**
     * ENHANCED: Populate mining category with custom item names and proper null checks
     */
    private void populateConfigurableMiningCategory(Inventory gui, Player target, MilestoneData milestoneData,
                                                    MilestoneManager.ProfileGuiConfig profileConfig) {
        try {
            ItemStack miningItem = new ItemStack(Material.DIAMOND_PICKAXE);
            ItemMeta meta = miningItem.getItemMeta();

            if (meta != null) {
                meta.setDisplayName("§b§lMining Milestones");

                List<String> lore = new ArrayList<>();
                lore.add("§7Category: §emining");
                lore.add("");

                MilestoneManager milestoneManager = plugin.getMilestoneManager();
                if (milestoneManager != null) {
                    List<Material> miningMaterials = milestoneManager.getProfileCategoryMaterials("mining");

                    if (miningMaterials == null || miningMaterials.isEmpty()) {
                        lore.add("§7No materials configured");
                    } else {
                        for (Material material : miningMaterials) {
                            if (material != null && milestoneData != null) {
                                // ENHANCED: Use combined progress for linked materials
                                long amount = milestoneManager.getCombinedProgress(target.getUniqueId(), material);

                                // ADDED: Use custom item name if available
                                String materialName = getCustomOrFormattedMaterialName("mining", material);
                                lore.add("§8" + materialName + ": §e" + formatNumber(amount));
                            }
                        }
                    }
                } else {
                    lore.add("§7Milestone system unavailable");
                }

                meta.setLore(lore);
                miningItem.setItemMeta(meta);
            }

            // Place in configured slot with bounds checking
            int slot = profileConfig.getMiningSlot();
            if (slot >= 0 && slot < gui.getSize()) {
                gui.setItem(slot, miningItem);
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error creating mining category item", e);
        }
    }

    /**
     * ENHANCED: Populate foraging category with custom item names and proper null checks
     */
    private void populateConfigurableForagingCategory(Inventory gui, Player target, MilestoneData milestoneData,
                                                      MilestoneManager.ProfileGuiConfig profileConfig) {
        try {
            ItemStack foragingItem = new ItemStack(Material.IRON_AXE);
            ItemMeta meta = foragingItem.getItemMeta();

            if (meta != null) {
                meta.setDisplayName("§2§lForaging Milestones");

                List<String> lore = new ArrayList<>();
                lore.add("§7Category: §eforaging");
                lore.add("");

                MilestoneManager milestoneManager = plugin.getMilestoneManager();
                if (milestoneManager != null) {
                    List<Material> foragingMaterials = milestoneManager.getProfileCategoryMaterials("foraging");

                    if (foragingMaterials == null || foragingMaterials.isEmpty()) {
                        lore.add("§7No materials configured");
                    } else {
                        for (Material material : foragingMaterials) {
                            if (material != null && milestoneData != null) {
                                // ENHANCED: Use combined progress for linked materials
                                long amount = milestoneManager.getCombinedProgress(target.getUniqueId(), material);

                                // ADDED: Use custom item name if available
                                String materialName = getCustomOrFormattedMaterialName("foraging", material);
                                lore.add("§8" + materialName + ": §e" + formatNumber(amount));
                            }
                        }
                    }
                } else {
                    lore.add("§7Milestone system unavailable");
                }

                meta.setLore(lore);
                foragingItem.setItemMeta(meta);
            }

            // Place in configured slot with bounds checking
            int slot = profileConfig.getForagingSlot();
            if (slot >= 0 && slot < gui.getSize()) {
                gui.setItem(slot, foragingItem);
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error creating foraging category item", e);
        }
    }

    /**
     * ADDED: Get custom item name or fallback to formatted material name
     */
    private String getCustomOrFormattedMaterialName(String category, Material material) {
        if (material == null) {
            return "Unknown";
        }

        MilestoneManager milestoneManager = plugin.getMilestoneManager();
        if (milestoneManager != null) {
            String customName = milestoneManager.getCustomItemName(category, material);
            if (customName != null && !customName.trim().isEmpty()) {
                // Apply color codes and return custom name
                return ChatColor.translateAlternateColorCodes('&', customName);
            }
        }

        // Fallback to formatted material name
        return formatMaterialName(material);
    }

    /**
     * ENHANCED: Populate misc info with PlaceholderAPI support and proper null handling
     */
    private void populateConfigurableMiscInfo(Inventory gui, Player target, MilestoneManager.ProfileGuiConfig profileConfig) {
        try {
            MilestoneManager.MiscInfoConfig miscConfig = profileConfig.getMiscInfoConfig();
            if (miscConfig == null) return;

            ItemStack miscItem = new ItemStack(miscConfig.getMaterial());
            ItemMeta meta = miscItem.getItemMeta();

            if (meta != null) {
                // Process configured name with placeholders
                String name = miscConfig.getItemName();
                if (name != null) {
                    name = ChatColor.translateAlternateColorCodes('&', name);
                    if (plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                        try {
                            name = PlaceholderAPI.setPlaceholders(target, name);
                        } catch (Exception e) {
                            plugin.getLogger().fine("Error processing placeholder in misc info name: " + e.getMessage());
                        }
                    }
                    meta.setDisplayName(name);
                }

                List<String> lore = new ArrayList<>();
                // Process configured lore with placeholders
                List<String> configuredLore = miscConfig.getItemLore();
                if (configuredLore != null) {
                    for (String line : configuredLore) {
                        if (line != null) {
                            String processedLine = ChatColor.translateAlternateColorCodes('&', line);
                            if (plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                                try {
                                    processedLine = PlaceholderAPI.setPlaceholders(target, processedLine);
                                } catch (Exception e) {
                                    plugin.getLogger().fine("Error processing placeholder in misc info lore: " + e.getMessage());
                                    // Keep the original line if placeholder processing fails
                                }
                            }
                            lore.add(processedLine);
                        }
                    }
                }

                meta.setLore(lore);
                miscItem.setItemMeta(meta);
            }

            // Place in configured slot with bounds checking
            int slot = miscConfig.getSlot();
            if (slot >= 0 && slot < gui.getSize()) {
                gui.setItem(slot, miscItem);
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error creating misc info item", e);
        }
    }

    /**
     * Offline versions of category population methods with custom item names and proper null handling
     */
    private void populateConfigurableOfflineFarmingCategory(Inventory gui, org.bukkit.OfflinePlayer target,
                                                            MilestoneData milestoneData, MilestoneManager.ProfileGuiConfig profileConfig) {
        try {
            ItemStack farmingItem = new ItemStack(Material.WHEAT);
            ItemMeta meta = farmingItem.getItemMeta();

            if (meta != null) {
                meta.setDisplayName("§6§lFarming Milestones");

                List<String> lore = new ArrayList<>();
                lore.add("§7Category: §efarming");
                lore.add("");

                MilestoneManager milestoneManager = plugin.getMilestoneManager();
                if (milestoneManager != null) {
                    List<Material> farmingMaterials = milestoneManager.getProfileCategoryMaterials("farming");

                    if (farmingMaterials == null || farmingMaterials.isEmpty()) {
                        lore.add("§7No materials configured");
                    } else {
                        for (Material material : farmingMaterials) {
                            if (material != null && milestoneData != null) {
                                // ENHANCED: Use combined progress for linked materials
                                long amount = milestoneManager.getCombinedProgress(target.getUniqueId(), material);

                                // ADDED: Use custom item name if available
                                String materialName = getCustomOrFormattedMaterialName("farming", material);
                                lore.add("§8" + materialName + ": §e" + formatNumber(amount));
                            }
                        }
                    }
                } else {
                    lore.add("§7Milestone system unavailable");
                }

                meta.setLore(lore);
                farmingItem.setItemMeta(meta);
            }

            int slot = profileConfig.getFarmingSlot();
            if (slot >= 0 && slot < gui.getSize()) {
                gui.setItem(slot, farmingItem);
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error creating offline farming category item", e);
        }
    }

    private void populateConfigurableOfflineMiningCategory(Inventory gui, org.bukkit.OfflinePlayer target,
                                                           MilestoneData milestoneData, MilestoneManager.ProfileGuiConfig profileConfig) {
        try {
            ItemStack miningItem = new ItemStack(Material.DIAMOND_PICKAXE);
            ItemMeta meta = miningItem.getItemMeta();

            if (meta != null) {
                meta.setDisplayName("§b§lMining Milestones");

                List<String> lore = new ArrayList<>();
                lore.add("§7Category: §emining");
                lore.add("");

                MilestoneManager milestoneManager = plugin.getMilestoneManager();
                if (milestoneManager != null) {
                    List<Material> miningMaterials = milestoneManager.getProfileCategoryMaterials("mining");

                    if (miningMaterials == null || miningMaterials.isEmpty()) {
                        lore.add("§7No materials configured");
                    } else {
                        for (Material material : miningMaterials) {
                            if (material != null && milestoneData != null) {
                                // ENHANCED: Use combined progress for linked materials
                                long amount = milestoneManager.getCombinedProgress(target.getUniqueId(), material);

                                // ADDED: Use custom item name if available
                                String materialName = getCustomOrFormattedMaterialName("mining", material);
                                lore.add("§8" + materialName + ": §e" + formatNumber(amount));
                            }
                        }
                    }
                } else {
                    lore.add("§7Milestone system unavailable");
                }

                meta.setLore(lore);
                miningItem.setItemMeta(meta);
            }

            int slot = profileConfig.getMiningSlot();
            if (slot >= 0 && slot < gui.getSize()) {
                gui.setItem(slot, miningItem);
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error creating offline mining category item", e);
        }
    }

    private void populateConfigurableOfflineForagingCategory(Inventory gui, org.bukkit.OfflinePlayer target,
                                                             MilestoneData milestoneData, MilestoneManager.ProfileGuiConfig profileConfig) {
        try {
            ItemStack foragingItem = new ItemStack(Material.IRON_AXE);
            ItemMeta meta = foragingItem.getItemMeta();

            if (meta != null) {
                meta.setDisplayName("§2§lForaging Milestones");

                List<String> lore = new ArrayList<>();
                lore.add("§7Category: §eforaging");
                lore.add("");

                MilestoneManager milestoneManager = plugin.getMilestoneManager();
                if (milestoneManager != null) {
                    List<Material> foragingMaterials = milestoneManager.getProfileCategoryMaterials("foraging");

                    if (foragingMaterials == null || foragingMaterials.isEmpty()) {
                        lore.add("§7No materials configured");
                    } else {
                        for (Material material : foragingMaterials) {
                            if (material != null && milestoneData != null) {
                                // ENHANCED: Use combined progress for linked materials
                                long amount = milestoneManager.getCombinedProgress(target.getUniqueId(), material);

                                // ADDED: Use custom item name if available
                                String materialName = getCustomOrFormattedMaterialName("foraging", material);
                                lore.add("§8" + materialName + ": §e" + formatNumber(amount));
                            }
                        }
                    }
                } else {
                    lore.add("§7Milestone system unavailable");
                }

                meta.setLore(lore);
                foragingItem.setItemMeta(meta);
            }

            int slot = profileConfig.getForagingSlot();
            if (slot >= 0 && slot < gui.getSize()) {
                gui.setItem(slot, foragingItem);
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error creating offline foraging category item", e);
        }
    }

    /**
     * ENHANCED: Populate offline misc info with proper placeholder replacement
     */
    private void populateConfigurableOfflineMiscInfo(Inventory gui, org.bukkit.OfflinePlayer target,
                                                     MilestoneManager.ProfileGuiConfig profileConfig) {
        try {
            MilestoneManager.MiscInfoConfig miscConfig = profileConfig.getMiscInfoConfig();
            if (miscConfig == null) return;

            ItemStack miscItem = new ItemStack(miscConfig.getMaterial());
            ItemMeta meta = miscItem.getItemMeta();

            if (meta != null) {
                // Process configured name (no placeholders for offline players)
                String name = miscConfig.getItemName();
                if (name != null) {
                    name = ChatColor.translateAlternateColorCodes('&', name);
                    meta.setDisplayName(name);
                }

                List<String> lore = new ArrayList<>();
                // Process configured lore (replace placeholders with offline message)
                List<String> configuredLore = miscConfig.getItemLore();
                if (configuredLore != null) {
                    for (String line : configuredLore) {
                        if (line != null) {
                            String processedLine = ChatColor.translateAlternateColorCodes('&', line);

                            // Replace any placeholders with offline message
                            if (processedLine.contains("%")) {
                                processedLine = OFFLINE_PLACEHOLDER;
                            }

                            lore.add(processedLine);
                        }
                    }
                }

                // Add offline notice
                lore.add("");
                lore.add("§7§oPlayer is offline - some data may be limited");

                meta.setLore(lore);
                miscItem.setItemMeta(meta);
            }

            int slot = miscConfig.getSlot();
            if (slot >= 0 && slot < gui.getSize()) {
                gui.setItem(slot, miscItem);
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error creating offline misc info item", e);
        }
    }

    /**
     * Fill empty slots with glass panes
     */
    private void fillEmptySlots(Inventory gui) {
        try {
            ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ", Arrays.asList());

            for (int i = 0; i < gui.getSize(); i++) {
                if (gui.getItem(i) == null) {
                    gui.setItem(i, filler);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error filling empty slots", e);
        }
    }

    /**
     * Handle GUI click events with proper null checks
     */
    public void handleProfileClick(Player player, int slot, ItemStack clickedItem) {
        if (player == null || clickedItem == null || isShuttingDown.get()) {
            return;
        }

        String openProfileTitle = openProfiles.get(player.getUniqueId());
        if (openProfileTitle == null) {
            return;
        }

        try {
            // Profile GUI is mostly read-only, but we can add click sounds for feedback
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);

            // Could add specific click actions here in the future
            // For example, clicking on a milestone item could show detailed progress
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error handling profile click", e);
        }
    }

    /**
     * Check if a GUI title is a profile GUI
     */
    public boolean isProfileGui(String title) {
        return title != null && title.equals(PROFILE_GUI_TITLE);
    }

    /**
     * Remove player from open profiles map when they close inventory
     */
    public void removePlayerProfile(Player player) {
        if (player != null) {
            openProfiles.remove(player.getUniqueId());
        }
    }

    /**
     * Utility methods with proper null checks
     */
    private ItemStack createItem(Material material, String name, List<String> lore) {
        try {
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();

            if (meta != null) {
                if (name != null) {
                    meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
                }

                if (lore != null) {
                    List<String> coloredLore = lore.stream()
                            .filter(Objects::nonNull)
                            .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                            .collect(Collectors.toList());
                    meta.setLore(coloredLore);
                }

                item.setItemMeta(meta);
            }

            return item;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error creating item", e);
            return new ItemStack(Material.STONE); // Fallback item
        }
    }

    private String formatMaterialName(Material material) {
        if (material == null) return "Unknown";

        try {
            return Arrays.stream(material.name().toLowerCase().split("_"))
                    .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                    .collect(Collectors.joining(" "));
        } catch (Exception e) {
            return material.name();
        }
    }

    private String formatNumber(long number) {
        try {
            if (number >= 1_000_000) {
                return String.format("%.1fM", number / 1_000_000.0);
            } else if (number >= 1_000) {
                return String.format("%.1fK", number / 1_000.0);
            } else {
                return String.valueOf(number);
            }
        } catch (Exception e) {
            return String.valueOf(number);
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
            // Only show player names if they have permission to view others
            if (player.hasPermission("ghasttools.profile.others")) {
                try {
                    return Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(Objects::nonNull)
                            .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                            .collect(Collectors.toList());
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Error in tab completion", e);
                }
            }
        }

        return completions;
    }

    /**
     * ENHANCED: Cleanup resources with proper shutdown handling
     */
    public void shutdown() {
        isShuttingDown.set(true);

        // Close all open profile GUIs
        for (UUID playerId : new HashSet<>(openProfiles.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                try {
                    player.closeInventory();
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Error closing profile GUI for " + player.getName(), e);
                }
            }
        }

        openProfiles.clear();
        plugin.getLogger().info("ProfileCommand shutdown complete");
    }
}