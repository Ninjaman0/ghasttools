package com.ghasttools.gui;

import com.ghasttools.GhastToolsPlugin;
import com.ghasttools.levelsmanager.levelshandler;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * FIXED: Enchantment shop GUI with essence level integration and proper currency validation
 */
public class ShopGui {

    private final GhastToolsPlugin plugin;
    private final levelshandler levelsHandler;

    // Click cooldown management
    private final ConcurrentHashMap<UUID, Long> clickCooldowns = new ConcurrentHashMap<>();
    private static final long CLICK_COOLDOWN_MS = 1000; // 1 second cooldown

    // Configuration cache
    private volatile FileConfiguration shopConfig;

    public ShopGui(GhastToolsPlugin plugin, levelshandler levelsHandler) {
        this.plugin = plugin;
        this.levelsHandler = levelsHandler;
        loadConfiguration();
    }

    /**
     * Load shop configuration
     */
    private void loadConfiguration() {
        try {
            shopConfig = plugin.getConfigManager().getConfig("shop.yml");
            if (shopConfig == null) {
                plugin.getLogger().severe("shop.yml configuration not found!");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error loading shop configuration", e);
        }
    }

    /**
     * ENHANCED: Open shop GUI with comprehensive validation and essence level checking
     */
    public boolean openShopGui(Player player) {
        if (player == null || shopConfig == null) {
            return false;
        }

        try {
            // Check if player is holding a GhastTool
            ItemStack heldTool = getCurrentHeldTool(player);
            if (heldTool == null) {
                player.sendMessage("§cYou must be holding a Tool to use the shop!");
                return false;
            }

            // Get tool information
            String toolType = plugin.getToolManager().getToolType(heldTool);
            int toolTier = plugin.getToolManager().getToolTier(heldTool);

            if (toolType == null || toolTier <= 0) {
                player.sendMessage("§cInvalid tool data!");
                return false;
            }

            // ENHANCED: Check level requirements for the tool
            if (!validateLevelRequirement(player, toolType, toolTier)) {
                return false;
            }

            // Create and open shop GUI
            Inventory shopGui = createShopGui(player, heldTool, toolType, toolTier);

            // Open on main thread
            if (Bukkit.isPrimaryThread()) {
                player.openInventory(shopGui);
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> player.openInventory(shopGui));
            }

            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error opening shop GUI for " + player.getName(), e);
            return false;
        }
    }

    /**
     * ENHANCED: Validate level requirements for tool usage
     */
    private boolean validateLevelRequirement(Player player, String toolType, int toolTier) {
        if (levelsHandler == null) {
            return true; // Allow if levels handler not available
        }

        int requiredLevel = getRequiredLevelForTool(toolType, toolTier);
        return levelsHandler.canPerformAction(player, "use the enchantment shop with this tool", requiredLevel);
    }

    /**
     * Get required level for tool tier
     */
    private int getRequiredLevelForTool(String toolType, int toolTier) {
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
     * Create the shop GUI inventory
     */
    private Inventory createShopGui(Player player, ItemStack tool, String toolType, int toolTier) {
        String title = ChatColor.translateAlternateColorCodes('&',
                shopConfig.getString("shop.title", "&5ENCHANTMENT SHOP"));
        int size = shopConfig.getInt("shop.size", 45);

        Inventory gui = Bukkit.createInventory(null, size, title);

        // Add preview tool
        addPreviewItem(gui, tool);

        // Add enchantment items in proper order
        addEnchantmentItems(gui, player, tool, toolType, toolTier);

        // Add filler items

        return gui;
    }

    /**
     * Add preview tool to shop
     */
    private void addPreviewItem(Inventory gui, ItemStack tool) {
        try {
            int previewSlot = shopConfig.getInt("shop.items.preview_item", 14);

            if (previewSlot >= 0 && previewSlot < gui.getSize()) {
                ItemStack previewTool = tool.clone();
                ItemMeta meta = previewTool.getItemMeta();

                if (meta != null) {
                    List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                    lore.add("");
                    lore.add("§7§o(Preview - Cannot be taken)");
                    meta.setLore(lore);
                    previewTool.setItemMeta(meta);
                }

                gui.setItem(previewSlot, previewTool);
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error adding preview item", e);
        }
    }

    /**
     * ENHANCED: Add enchantment items with proper availability checking and level validation
     */
    private void addEnchantmentItems(Inventory gui, Player player, ItemStack tool, String toolType, int toolTier) {
        ConfigurationSection enchantmentsSection = shopConfig.getConfigurationSection("shop.items.enchantment");
        if (enchantmentsSection == null) return;

        Map<String, Integer> currentEnchantments = plugin.getToolManager().getToolEnchantments(tool);

        // Process enchantments in configured order
        for (String enchantmentKey : enchantmentsSection.getKeys(false)) {
            ConfigurationSection enchantConfig = enchantmentsSection.getConfigurationSection(enchantmentKey);
            if (enchantConfig == null) continue;

            try {
                // Get enchantment configuration
                Material material = Material.valueOf(enchantConfig.getString("material", "BOOK"));
                String name = enchantConfig.getString("name", "Unknown Enchantment");
                List<String> lore = enchantConfig.getStringList("lore");
                int slot = enchantConfig.getInt("slot", 0);
                String shownPrice = enchantConfig.getString("shown_price", "&6{price} Essence Levels");

                // Check if this enchantment can be applied to this tool tier
                if (!canApplyEnchantment(enchantConfig, toolType, toolTier)) {
                    continue;
                }

                // Get current enchantment level
                int currentLevel = currentEnchantments.getOrDefault(enchantmentKey, 0);

                // Get next available level
                EnchantmentPurchase purchase = getNextLevelPurchase(enchantConfig, toolType, toolTier, currentLevel);

                if (purchase != null && slot >= 0 && slot < gui.getSize()) {
                    ItemStack enchantItem = createEnchantmentItem(material, name, lore, currentLevel, purchase, player, shownPrice);
                    gui.setItem(slot, enchantItem);
                }

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error creating enchantment item: " + enchantmentKey, e);
            }
        }
    }

    /**
     * Get next level purchase information
     */
    private EnchantmentPurchase getNextLevelPurchase(ConfigurationSection enchantConfig, String toolType,
                                                     int toolTier, int currentLevel) {
        try {
            // Get max level for this tool tier
            ConfigurationSection maxPerTier = enchantConfig.getConfigurationSection("max_per_tier." + toolType);
            if (maxPerTier == null) return null;

            int maxLevel = maxPerTier.getInt(String.valueOf(toolTier), 0);
            if (maxLevel <= 0) return null;

            // Calculate next level
            int nextLevel = currentLevel + 1;
            if (nextLevel > maxLevel) return null;

            // Get price for next level with multiplier
            ConfigurationSection levelsPrices = enchantConfig.getConfigurationSection("levels_prices." + toolType);
            if (levelsPrices == null) return null;

            int basePrice = levelsPrices.getInt(String.valueOf(nextLevel), -1);
            if (basePrice < 0) return null;

            // Apply global multiplier
            double multiplier = shopConfig.getDouble("shop.currency.multiplier", 1.0);
            int finalPrice = (int) Math.round(basePrice * multiplier);

            return new EnchantmentPurchase(nextLevel, finalPrice, maxLevel);

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error getting purchase info", e);
            return null;
        }
    }

    /**
     * FIXED: Create enchantment item with proper essence level display
     */
    private ItemStack createEnchantmentItem(Material material, String baseName, List<String> baseLore,
                                            int currentLevel, EnchantmentPurchase purchase, Player player, String shownPrice) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            // Dynamic name based on next level
            String displayName = baseName.replace("I", romanNumeral(purchase.nextLevel));
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', displayName));

            // Dynamic lore
            List<String> lore = new ArrayList<>();

            // Add base lore
            for (String line : baseLore) {
                lore.add(ChatColor.translateAlternateColorCodes('&', line));
            }

            lore.add("");

            // Current level info
            if (currentLevel > 0) {
                lore.add("§7Current Level: §e" + romanNumeral(currentLevel));
            } else {
                lore.add("§7Current Level: §cNone");
            }

            // Next level info
            lore.add("§7Next Level: §a" + romanNumeral(purchase.nextLevel));

            // ENHANCED: Use shown price format with proper essence level display
            String priceDisplay = shownPrice.replace("{price}", formatNumber(purchase.price));
            lore.add(ChatColor.translateAlternateColorCodes('&', priceDisplay));

            // FIXED: Essence level check with proper balance validation
            boolean canAfford = hasEnoughEssenceLevels(player, purchase.price);
            if (canAfford) {
                lore.add("§a✓ You can afford this upgrade");
            } else {
                lore.add("§c✗ Not enough essence levels");

                // Show current balance
                int balance = getCurrentEssenceLevels(player);
                lore.add("§7Your essence level: " + balance);
            }

            // Max level check
            if (purchase.nextLevel >= purchase.maxLevel) {
                lore.add("§6⚠ This will be the maximum level");
            }

            lore.add("");
            lore.add("§e§lClick to purchase!");

            meta.setLore(lore);
            item.setItemMeta(meta);
        }

        return item;
    }

    /**
     * Check if enchantment can be applied to tool
     */
    private boolean canApplyEnchantment(ConfigurationSection enchantConfig, String toolType, int toolTier) {
        ConfigurationSection maxPerTier = enchantConfig.getConfigurationSection("max_per_tier." + toolType);
        if (maxPerTier == null) return false;

        // Check if this tool tier is supported
        return maxPerTier.contains(String.valueOf(toolTier));
    }

    /**
     * ENHANCED: Handle shop GUI click with proper validation and essence level withdrawal
     */
    public void handleShopClick(Player player, int slot, boolean isLeftClick) {
        if (player == null || shopConfig == null) return;

        // Check click cooldown
        if (isOnClickCooldown(player)) {
            player.sendMessage("§cPlease wait before clicking again!");
            return;
        }

        // Set click cooldown
        setClickCooldown(player);

        try {
            // Get clicked enchantment
            String enchantmentKey = getEnchantmentBySlot(slot);
            if (enchantmentKey == null) return;

            // Validate tool
            ItemStack heldTool = getCurrentHeldTool(player);
            if (heldTool == null) {
                player.sendMessage("§cYou must be holding a Tool!");
                player.closeInventory();
                return;
            }

            String toolType = plugin.getToolManager().getToolType(heldTool);
            int toolTier = plugin.getToolManager().getToolTier(heldTool);

            if (toolType == null || toolTier <= 0) {
                player.sendMessage("§cInvalid tool data!");
                player.closeInventory();
                return;
            }

            // ENHANCED: Validate level requirement
            if (!validateLevelRequirement(player, toolType, toolTier)) {
                player.closeInventory();
                return;
            }

            // Get enchantment configuration
            ConfigurationSection enchantConfig = shopConfig.getConfigurationSection("shop.items.enchantment." + enchantmentKey);
            if (enchantConfig == null) return;

            // Check if enchantment can be applied
            if (!canApplyEnchantment(enchantConfig, toolType, toolTier)) {
                player.sendMessage("§cThis enchantment cannot be applied to your tool!");
                return;
            }

            // Get current enchantments
            Map<String, Integer> currentEnchantments = plugin.getToolManager().getToolEnchantments(heldTool);
            int currentLevel = currentEnchantments.getOrDefault(enchantmentKey, 0);

            // Get purchase information
            EnchantmentPurchase purchase = getNextLevelPurchase(enchantConfig, toolType, toolTier, currentLevel);
            if (purchase == null) {
                player.sendMessage("§cThis enchantment cannot be upgraded right now!");
                return;
            }

            // Check essence levels before purchase
            if (!hasEnoughEssenceLevels(player, purchase.price)) {
                player.sendMessage("§cYou need " + formatNumber(purchase.price) + " essence to purchase this!");
                int balance = getCurrentEssenceLevels(player);
                player.sendMessage("§7Your current essence: " + balance);
                return;
            }

            // FIXED: Process purchase with proper essence level withdrawal
            if (processPurchase(player, heldTool, enchantmentKey, purchase)) {
                player.sendMessage("§a✓ Successfully upgraded " + enchantmentKey + " to level " + romanNumeral(purchase.nextLevel) + "!");
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.5f);

                // Close GUI and reopen to refresh
                player.closeInventory();

                // Reopen shop after short delay
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        openShopGui(player);
                    }
                }, 5L);
            } else {
                player.sendMessage("§cFailed to process purchase! Please try again.");
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error handling shop click for " + player.getName(), e);
        }
    }

    /**
     * FIXED: Process enchantment purchase with proper essence level withdrawal
     */
    private boolean processPurchase(Player player, ItemStack tool, String enchantmentKey, EnchantmentPurchase purchase) {
        try {
            // Withdraw essence levels first using the EssenceHandler
            if (!withdrawEssenceLevels(player, purchase.price)) {
                return false;
            }

            // Apply enchantment
            plugin.getEnchantmentManager().applyEnchantment(tool, enchantmentKey, purchase.nextLevel);


            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error processing purchase", e);
            return false;
        }
    }

    /**
     * FIXED: Check if player has enough essence levels with proper EssenceHandler integration
     */
    private boolean hasEnoughEssenceLevels(Player player, int requiredLevels) {
        try {
            if (plugin.getEssenceHandler() != null && plugin.getEssenceHandler().isAvailable()) {
                return plugin.getEssenceHandler().hasEnoughEssenceLevels(player, requiredLevels);
            } else {
                plugin.getLogger().fine("EssenceHandler not available for balance check");
                return false;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error checking essence levels for " + player.getName(), e);
            return false;
        }
    }

    /**
     * FIXED: Get current essence levels for display purposes
     */
    private int getCurrentEssenceLevels(Player player) {
        try {
            if (plugin.getEssenceHandler() != null && plugin.getEssenceHandler().isAvailable()) {
                return plugin.getEssenceHandler().getPlayerEssenceLevel(player);
            } else {
                return 0;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error getting current essence levels for " + player.getName(), e);
            return 0;
        }
    }

    /**
     * FIXED: Withdraw essence levels from player with proper EssenceHandler integration
     */
    private boolean withdrawEssenceLevels(Player player, int levels) {
        try {
            if (plugin.getEssenceHandler() != null && plugin.getEssenceHandler().isAvailable()) {
                return plugin.getEssenceHandler().withdrawEssenceLevels(player, levels);
            } else {
                plugin.getLogger().warning("Cannot withdraw essence levels - EssenceHandler not available");
                return false;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error withdrawing essence levels for " + player.getName(), e);
            return false;
        }
    }

    /**
     * Get enchantment key by slot
     */
    private String getEnchantmentBySlot(int slot) {
        ConfigurationSection enchantmentsSection = shopConfig.getConfigurationSection("shop.items.enchantment");
        if (enchantmentsSection == null) return null;

        for (String enchantmentKey : enchantmentsSection.getKeys(false)) {
            ConfigurationSection enchantConfig = enchantmentsSection.getConfigurationSection(enchantmentKey);
            if (enchantConfig != null && enchantConfig.getInt("slot", -1) == slot) {
                return enchantmentKey;
            }
        }

        return null;
    }

    /**
     * Add filler items to GUI
     */
    private void addFillerItems(Inventory gui) {
        // Add filler items to empty slots
        for (int i = 0; i < gui.getSize(); i++) {
            if (gui.getItem(i) == null) {
                ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
                ItemMeta meta = filler.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(" ");
                    filler.setItemMeta(meta);
                }
                gui.setItem(i, filler);
            }
        }
    }

    /**
     * Get current held GhastTool
     */
    private ItemStack getCurrentHeldTool(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (plugin.getToolManager().isGhastTool(mainHand)) {
            return mainHand;
        }

        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (plugin.getToolManager().isGhastTool(offHand)) {
            return offHand;
        }

        return null;
    }

    /**
     * Check if GUI title matches shop GUI using config
     */
    public boolean isShopGui(String title) {
        if (title == null || shopConfig == null) return false;
        String configTitle = ChatColor.translateAlternateColorCodes('&',
                shopConfig.getString("shop.title", "&5ENCHANTMENT SHOP"));
        return ChatColor.stripColor(title).equalsIgnoreCase(ChatColor.stripColor(configTitle));
    }

    /**
     * Click cooldown management
     */
    private boolean isOnClickCooldown(Player player) {
        Long lastClick = clickCooldowns.get(player.getUniqueId());
        if (lastClick == null) return false;

        return System.currentTimeMillis() - lastClick < CLICK_COOLDOWN_MS;
    }

    private void setClickCooldown(Player player) {
        clickCooldowns.put(player.getUniqueId(), System.currentTimeMillis());

        // Clean up cooldowns for offline players
        clickCooldowns.entrySet().removeIf(entry -> {
            Player p = plugin.getServer().getPlayer(entry.getKey());
            return p == null || !p.isOnline();
        });
    }

    /**
     * Utility methods
     */
    private String romanNumeral(int number) {
        switch (number) {
            case 1:
                return "I";
            case 2:
                return "II";
            case 3:
                return "III";
            case 4:
                return "IV";
            case 5:
                return "V";
            case 6:
                return "VI";
            case 7:
                return "VII";
            case 8:
                return "VIII";
            case 9:
                return "IX";
            case 10:
                return "X";
            default:
                return String.valueOf(number);
        }
    }

    private String formatNumber(int number) {
        if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000.0);
        } else if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000.0);
        } else {
            return String.valueOf(number);
        }
    }

    /**
     * Cleanup on shutdown
     */
    public void shutdown() {
        clickCooldowns.clear();
    }

    public void reloadConfig() {
        loadConfiguration();
    }

    /**
     * Inner class for enchantment purchase information
     */
    private static class EnchantmentPurchase {
        public final int nextLevel;
        public final int price;
        public final int maxLevel;

        public EnchantmentPurchase(int nextLevel, int price, int maxLevel) {
            this.nextLevel = nextLevel;
            this.price = price;
            this.maxLevel = maxLevel;
        }
    }
}