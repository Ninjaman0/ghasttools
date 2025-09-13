package com.ghasttools.milestones;

import com.ghasttools.GhastToolsPlugin;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * ENHANCED: Handles milestone GUI creation and management with linked materials support
 */
public class MilestoneGui {

    private final GhastToolsPlugin plugin;
    private final MilestoneManager milestoneManager;
    private final Map<UUID, String> openGuis;
    private final Map<UUID, Material> openGuiMaterials; // Track which material each player's GUI is for
    private final boolean placeholderApiAvailable;

    // GUI titles
    private static final String MAIN_GUI_TITLE = "§0§lMilestone Progress";
    private static final String PROFILE_GUI_TITLE = "§0§lPlayer Milestones";

    public MilestoneGui(GhastToolsPlugin plugin, MilestoneManager milestoneManager) {
        this.plugin = plugin;
        this.milestoneManager = milestoneManager;
        this.openGuis = new ConcurrentHashMap<>();
        this.openGuiMaterials = new ConcurrentHashMap<>();
        this.placeholderApiAvailable = plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");

        if (placeholderApiAvailable) {
            plugin.getLogger().info("PlaceholderAPI detected - milestone GUI will support custom placeholders");
        }

    }

    private String processPlaceholders(Player player, String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String processed = ChatColor.translateAlternateColorCodes('&', text);

        if (placeholderApiAvailable && player != null) {
            try {
                processed = PlaceholderAPI.setPlaceholders(player, processed);
            } catch (Exception e) {
                plugin.getLogger().fine("Error processing placeholders in text: " + text + " - " + e.getMessage());
            }
        }

        return processed;

    }

    private List<String> processPlaceholdersList(Player player, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> processed = new ArrayList<>();
        for (String line : lines) {
            processed.add(processPlaceholders(player, line));
        }
        return processed;

    }

    public boolean openMainMilestoneGui(Player player) {
        if (player == null) {
            return false;
        }

        try {
            Set<Material> configuredMaterials = milestoneManager.getConfiguredMaterials();

            if (configuredMaterials.isEmpty()) {
                player.sendMessage("§cNo milestones are configured!");
                return false;
            }

            int size = milestoneManager.getMainGuiSize();
            Inventory gui = Bukkit.createInventory(null, size, MAIN_GUI_TITLE);

            UUID playerUUID = player.getUniqueId();
            MilestoneData milestoneData = milestoneManager.getPlayerMilestoneData(playerUUID);

            for (Material material : configuredMaterials) {
                MilestoneConfig config = milestoneManager.getMilestoneConfig(material);
                if (config != null) {
                    int slot = config.getSlot();

                    if (slot < 0 || slot >= size) {
                        plugin.getLogger().warning("Invalid or missing slot configuration for " + material.name() + ": " + slot);
                        continue;
                    }

                    ItemStack item = createMainGuiItem(player, config, milestoneData);
                    gui.setItem(slot, item);
                }
            }

            fillEmptySlots(gui, Material.GRAY_STAINED_GLASS_PANE, " ");

            player.openInventory(gui);
            openGuis.put(player.getUniqueId(), MAIN_GUI_TITLE);

            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error opening main milestone GUI for " + player.getName(), e);
            return false;
        }

    }

    public boolean openSubMilestoneGui(Player player, Material material) {
        if (player == null || material == null) {
            return false;
        }

        try {
            MilestoneConfig config = milestoneManager.getMilestoneConfig(material);
            if (config == null) {
                player.sendMessage("§cNo milestones configured for " + material.name());
                return false;
            }

            Map<Integer, MilestoneConfig.MilestoneLevel> levels = config.getAllLevels();
            if (levels.isEmpty()) {
                player.sendMessage("§cNo milestone levels configured for " + material.name());
                return false;
            }

            int size = Math.max(9, ((levels.size() + 8) / 9) * 9);
            size = Math.min(54, size);

            // FIXED: Use the custom gui-name from the milestone configuration
            String title = processPlaceholders(player, config.getGuiName());
            Inventory gui = Bukkit.createInventory(null, size, title);

            UUID playerUUID = player.getUniqueId();
            MilestoneData milestoneData = milestoneManager.getPlayerMilestoneData(playerUUID);

            for (Map.Entry<Integer, MilestoneConfig.MilestoneLevel> entry : levels.entrySet()) {
                int levelNumber = entry.getKey();
                MilestoneConfig.MilestoneLevel level = entry.getValue();
                int slot = level.getSlot();

                if (slot < 0 || slot >= size) {
                    for (int i = 0; i < size; i++) {
                        if (gui.getItem(i) == null) {
                            slot = i;
                            break;
                        }
                    }
                }

                if (slot >= 0 && slot < size && gui.getItem(slot) == null) {
                    ItemStack item = createSubGuiItem(player, material, levelNumber, level, milestoneData);
                    gui.setItem(slot, item);
                }
            }

            fillEmptySlots(gui, Material.GRAY_STAINED_GLASS_PANE, " ");

            player.openInventory(gui);
            openGuis.put(player.getUniqueId(), title);
            openGuiMaterials.put(player.getUniqueId(), material); // Store the material for this GUI

            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error opening sub milestone GUI for " + player.getName(), e);
            return false;
        }

    }

    public boolean openProfileMilestoneGui(Player viewer, Player target) {
        if (viewer == null || target == null) {
            return false;
        }

        try {
            MilestoneManager.ProfileGuiConfig profileConfig = milestoneManager.getProfileGuiConfig();
            int size = profileConfig.getSize();

            Inventory gui = Bukkit.createInventory(null, size, PROFILE_GUI_TITLE);

            UUID targetUUID = target.getUniqueId();
            MilestoneData milestoneData = milestoneManager.getPlayerMilestoneData(targetUUID);

            ItemStack playerHead = createConfigurablePlayerHeadItem(target, profileConfig);
            gui.setItem(profileConfig.getPlayerHeadSlot(), playerHead);

            ItemStack farmingItem = createProfileCategoryItem(target, "farming", Material.WHEAT, "§6Farming Milestones");
            gui.setItem(profileConfig.getFarmingSlot(), farmingItem);

            ItemStack miningItem = createProfileCategoryItem(target, "mining", Material.DIAMOND_PICKAXE, "§bMining Milestones");
            gui.setItem(profileConfig.getMiningSlot(), miningItem);

            ItemStack foragingItem = createProfileCategoryItem(target, "foraging", Material.IRON_AXE, "§2Foraging Milestones");
            gui.setItem(profileConfig.getForagingSlot(), foragingItem);

            if (profileConfig.getMiscInfoConfig() != null) {
                ItemStack miscItem = createMiscInfoItem(target, profileConfig.getMiscInfoConfig());
                gui.setItem(profileConfig.getMiscInfoConfig().getSlot(), miscItem);
            }

            fillEmptySlots(gui, Material.GRAY_STAINED_GLASS_PANE, " ");

            viewer.openInventory(gui);
            openGuis.put(viewer.getUniqueId(), PROFILE_GUI_TITLE);

            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error opening profile milestone GUI", e);
            return false;
        }

    }

    private ItemStack createConfigurablePlayerHeadItem(Player target, MilestoneManager.ProfileGuiConfig profileConfig) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();

        if (meta != null) {
            meta.setOwningPlayer(target);
            meta.setDisplayName("§6" + target.getName());

            List<String> lore = new ArrayList<>();

            List<String> processedConfigLore = processPlaceholdersList(target, profileConfig.getPlayerHeadLore());
            lore.addAll(processedConfigLore);

            lore.add(processPlaceholders(target, "§7Player: §e%player_name%"));

            if (target.getFirstPlayed() > 0) {
                SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy");
                String joinDate = sdf.format(new Date(target.getFirstPlayed()));
                lore.add(processPlaceholders(target, "§7First joined: §e" + joinDate));
            }

            if (target.isOnline()) {
                lore.add(processPlaceholders(target, "§7Status: §aOnline"));
            } else if (target.getLastPlayed() > 0) {
                SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy");
                String lastOnline = sdf.format(new Date(target.getLastPlayed()));
                lore.add(processPlaceholders(target, "§7Last online: §e" + lastOnline));
            }

            meta.setLore(lore);
            head.setItemMeta(meta);

        }

        return head;

    }

    private ItemStack createMiscInfoItem(Player target, MilestoneManager.MiscInfoConfig miscConfig) {
        ItemStack item = new ItemStack(miscConfig.getMaterial());
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            String name = processPlaceholders(target, miscConfig.getItemName());
            meta.setDisplayName(name);

            List<String> lore = processPlaceholdersList(target, miscConfig.getItemLore());
            meta.setLore(lore);

            item.setItemMeta(meta);

        }

        return item;

    }

    /**
     * ENHANCED: Handle GUI click events with improved validation
     */
    public void handleGuiClick(Player player, int slot, ItemStack clickedItem) {
        if (player == null || clickedItem == null) {
            return;
        }

        String openGuiTitle = openGuis.get(player.getUniqueId());
        if (openGuiTitle == null) {
            return;
        }

        try {
            if (MAIN_GUI_TITLE.equals(openGuiTitle)) {
                handleMainGuiClick(player, slot, clickedItem);
            } else if (isSubMilestoneGui(player.getUniqueId())) {
                handleSubGuiClick(player, slot, clickedItem);
            } else if (PROFILE_GUI_TITLE.equals(openGuiTitle)) {
                handleProfileGuiClick(player, slot, clickedItem);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error handling milestone GUI click", e);
        }
    }

    private void handleMainGuiClick(Player player, int slot, ItemStack clickedItem) {
        Set<Material> configuredMaterials = milestoneManager.getConfiguredMaterials();

        Material clickedMaterial = null;
        for (Material material : configuredMaterials) {
            MilestoneConfig config = milestoneManager.getMilestoneConfig(material);
            if (config != null && config.getSlot() == slot) {
                clickedMaterial = material;
                break;
            }
        }

        if (clickedMaterial != null) {
            player.closeInventory();

            Material finalClickedMaterial = clickedMaterial;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    openSubMilestoneGui(player, finalClickedMaterial);
                }
            }, 2L);

        }

    }

    /**
     * ENHANCED: Handle sub GUI clicks with improved material tracking
     */
    private void handleSubGuiClick(Player player, int slot, ItemStack clickedItem) {
        // Get the material from our tracking map instead of parsing the title
        Material material = openGuiMaterials.get(player.getUniqueId());
        if (material == null) {
            plugin.getLogger().warning("Could not determine material for sub GUI click by " + player.getName());
            return;
        }

        try {
            MilestoneConfig config = milestoneManager.getMilestoneConfig(material);

            if (config != null) {
                Map<Integer, MilestoneConfig.MilestoneLevel> levels = config.getAllLevels();

                int milestoneLevel = -1;
                for (Map.Entry<Integer, MilestoneConfig.MilestoneLevel> entry : levels.entrySet()) {
                    if (entry.getValue().getSlot() == slot) {
                        milestoneLevel = entry.getKey();
                        break;
                    }
                }

                if (milestoneLevel != -1) {
                    if (clickedItem.getType() == Material.LIME_STAINED_GLASS_PANE) {

                        boolean success = milestoneManager.claimMilestone(player, material, milestoneLevel);

                        if (success) {
                            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);

                            // Refresh the GUI after a short delay
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                if (player.isOnline()) {
                                    openSubMilestoneGui(player, material);
                                }
                            }, 5L); // Slightly longer delay to ensure command execution completes

                        } else {
                            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                        }
                    } else if (clickedItem.getType() == Material.RED_STAINED_GLASS_PANE) {
                        player.sendMessage("§cYou haven't reached this milestone yet!");
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    } else if (clickedItem.getType() == Material.GRAY_STAINED_GLASS_PANE) {
                        // Already claimed milestone - show info
                        MilestoneConfig.MilestoneLevel milestone = config.getLevel(milestoneLevel);
                        if (milestone != null) {
                            player.sendMessage("§aThis milestone has already been claimed!");
                        }
                    }
                }
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error handling sub milestone GUI click", e);
        }
    }

    private void handleProfileGuiClick(Player player, int slot, ItemStack clickedItem) {
// Profile GUI is read-only for now
    }

    /**
     * ENHANCED: Create main GUI item with linked materials support
     */
    private ItemStack createMainGuiItem(Player player, MilestoneConfig config, MilestoneData milestoneData) {
        ItemStack item = new ItemStack(config.getGuiMaterial());
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            String displayName = processPlaceholders(player, config.getGuiName());
            meta.setDisplayName(displayName);

            List<String> lore = new ArrayList<>();

            List<String> configLore = processPlaceholdersList(player, config.getGuiLore());
            lore.addAll(configLore);

            lore.add("");
            lore.add(processPlaceholders(player, "§7Progress:"));

            // ENHANCED: Show combined progress for linked materials
            long totalBroken = milestoneManager.getCombinedProgress(player.getUniqueId(), config.getMaterial());
            String progressLine = processPlaceholders(player, "§8  Total broken: §e" + formatNumber(totalBroken));
            lore.add(progressLine);

            // Show linked materials information if applicable


            Map<Integer, MilestoneConfig.MilestoneLevel> levels = config.getAllLevels();
            int completedMilestones = 0;

            for (Map.Entry<Integer, MilestoneConfig.MilestoneLevel> entry : levels.entrySet()) {
                int levelNumber = entry.getKey();
                if (milestoneData.isMilestoneClaimed(config.getMaterial(), levelNumber)) {
                    completedMilestones++;
                }
            }

            String completionLine = processPlaceholders(player, "§8  Completed: §a" + completedMilestones + "§8/§7" + levels.size());
            lore.add(completionLine);

            meta.setLore(lore);
            item.setItemMeta(meta);

        }

        return item;
    }

    /**
     * ENHANCED: Create sub GUI item with linked materials support
     */
    private ItemStack createSubGuiItem(Player player, Material material, int levelNumber,
                                       MilestoneConfig.MilestoneLevel level, MilestoneData milestoneData) {

// ENHANCED: Use combined progress for linked materials
        long playerProgress = milestoneManager.getCombinedProgress(player.getUniqueId(), material);
        boolean hasReached = playerProgress >= level.getAmount();
        boolean isClaimed = milestoneData.isMilestoneClaimed(material, levelNumber);

        Material itemMaterial;
        if (isClaimed) {
            itemMaterial = Material.GRAY_STAINED_GLASS_PANE; // Already claimed - changed to gray
        } else if (hasReached) {
            itemMaterial = level.getGuiItemReady(); // Ready to claim
        } else {
            itemMaterial = level.getGuiItemNotReady(); // Not ready
        }

        ItemStack item = new ItemStack(itemMaterial);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            String displayName = processPlaceholders(player, level.getGuiName());
            meta.setDisplayName(displayName);

            List<String> lore = new ArrayList<>();

            List<String> configLore = processPlaceholdersList(player, level.getGuiLore());
            lore.addAll(configLore);

            lore.add("");
            String progressLine = processPlaceholders(player, "§7Progress: §e" + formatNumber(playerProgress) + "§8/§7" + formatNumber(level.getAmount()));
            lore.add(progressLine);

            double percentage = Math.min(1.0, (double) playerProgress / level.getAmount());
            lore.add(createProgressBar(percentage));


            lore.add("");
            if (isClaimed) {
                lore.add(processPlaceholders(player, "§a✓ Already claimed!"));
            } else if (hasReached) {
                lore.add(processPlaceholders(player, "§a✓ Ready to claim!"));
                lore.add(processPlaceholders(player, "§eClick to claim reward!"));
            } else {
                long remaining = level.getAmount() - playerProgress;
                lore.add(processPlaceholders(player, "§c✗ Not ready"));
                String remainingLine = processPlaceholders(player, "§7Need " + formatNumber(remaining) + " more");
                lore.add(remainingLine);
            }

            meta.setLore(lore);
            item.setItemMeta(meta);

        }

        return item;
    }

    private ItemStack createProfileCategoryItem(Player target, String category, Material icon, String title) {
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            String processedTitle = processPlaceholders(target, title);
            meta.setDisplayName(processedTitle);

            List<String> lore = new ArrayList<>();
            lore.add(processPlaceholders(target, "§7Category: §e" + category));
            lore.add("");

            List<Material> categoryMaterials = milestoneManager.getProfileCategoryMaterials(category);
            MilestoneData milestoneData = milestoneManager.getPlayerMilestoneData(target.getUniqueId());

            if (categoryMaterials.isEmpty()) {
                lore.add(processPlaceholders(target, "§7No materials configured"));
            } else {
                for (Material material : categoryMaterials) {
                    // ENHANCED: Use combined progress for linked materials
                    long amount = milestoneManager.getCombinedProgress(target.getUniqueId(), material);
                    String materialName = material.name().toLowerCase().replace("_", " ");
                    String materialLine = processPlaceholders(target, "§8  " + materialName + ": §e" + formatNumber(amount));
                    lore.add(materialLine);
                }
            }

            meta.setLore(lore);
            item.setItemMeta(meta);

        }

        return item;

    }

    private ItemStack createFillerItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }

        return item;

    }

    private void fillEmptySlots(Inventory gui, Material material, String name) {
        ItemStack filler = createFillerItem(material, name);

        for (int i = 0; i < gui.getSize(); i++) {
            if (gui.getItem(i) == null) {
                gui.setItem(i, filler);
            }
        }

    }

    private String createProgressBar(double percentage) {
        int barLength = 20;
        int filled = (int) (percentage * barLength);

        StringBuilder bar = new StringBuilder("§8[");
        for (int i = 0; i < barLength; i++) {
            if (i < filled) {
                bar.append("§a■");
            } else {
                bar.append("§7■");
            }
        }
        bar.append("§8] §e").append(Math.round(percentage * 100)).append("%");

        return bar.toString();

    }

    private String formatNumber(long number) {
        if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000.0);
        } else if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000.0);
        } else {
            return String.valueOf(number);
        }
    }

    /**
     * ENHANCED: Check if a GUI is a milestone GUI with improved detection
     */
    public boolean isMilestoneGui(String title) {
        if (title == null) {
            return false;
        }

        return title.equals(MAIN_GUI_TITLE) ||
                title.equals(PROFILE_GUI_TITLE) ||
                isSubMilestoneGuiTitle(title);
    }

    /**
     * Check if a title belongs to a sub milestone GUI by comparing with configured gui-names
     */
    private boolean isSubMilestoneGuiTitle(String title) {
        if (title == null) {
            return false;
        }

        Set<Material> configuredMaterials = milestoneManager.getConfiguredMaterials();
        for (Material material : configuredMaterials) {
            MilestoneConfig config = milestoneManager.getMilestoneConfig(material);
            if (config != null) {
                String configTitle = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', config.getGuiName()));
                String checkTitle = ChatColor.stripColor(title);
                if (configTitle.equals(checkTitle)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check if a player has a sub milestone GUI open
     */
    private boolean isSubMilestoneGui(UUID playerUUID) {
        return openGuiMaterials.containsKey(playerUUID);
    }

    public void removePlayerGui(Player player) {
        if (player != null) {
            openGuis.remove(player.getUniqueId());
            openGuiMaterials.remove(player.getUniqueId()); // Also remove material tracking
        }
    }

    public boolean isPlaceholderApiAvailable() {
        return placeholderApiAvailable;
    }

    public void refreshPlayerGui(Player player) {
        if (player == null) {
            return;
        }

        String openGuiTitle = openGuis.get(player.getUniqueId());
        if (openGuiTitle == null) {
            return;
        }

        try {
            if (MAIN_GUI_TITLE.equals(openGuiTitle)) {
                openMainMilestoneGui(player);
            } else if (isSubMilestoneGui(player.getUniqueId())) {
                Material material = openGuiMaterials.get(player.getUniqueId());
                if (material != null) {
                    openSubMilestoneGui(player, material);
                } else {
                    plugin.getLogger().warning("Could not refresh sub milestone GUI - no material tracked for " + player.getName());
                }
            } else if (PROFILE_GUI_TITLE.equals(openGuiTitle)) {
                player.closeInventory();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error refreshing milestone GUI for " + player.getName(), e);
        }

    }

    public void shutdown() {
        openGuis.clear();
        openGuiMaterials.clear();
    }
}