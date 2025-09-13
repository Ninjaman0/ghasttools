package com.ghasttools.gui;

import com.ghasttools.GhastToolsPlugin;
import com.ghasttools.data.LockedTool;
import com.ghasttools.levelsmanager.levelshandler;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * FIXED: Thread-safe GUI manager with essence level integration and proper currency validation
 */
public class GuiManager {

    private final GhastToolsPlugin plugin;
    private final levelshandler levelsHandler;
    private final RandomWinGui randomWinGui;
    private final ShopGui shopGui;
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    
    // Vault Economy integration
    private Economy economy = null;

    // Tool lock mechanism
    private final ConcurrentHashMap<UUID, LockedTool> lockedTools = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastClickTime = new ConcurrentHashMap<>();

    // Click delay settings
    private volatile boolean clickDelayEnabled;
    private volatile long clickDelayTicks;

    // Cleanup task
    private BukkitTask cleanupTask;

    public GuiManager(GhastToolsPlugin plugin, levelshandler levelsHandler) {
        this.plugin = plugin;
        this.levelsHandler = levelsHandler;

        // Initialize GUI systems
        this.randomWinGui = new RandomWinGui(plugin, levelsHandler);
        this.shopGui = new ShopGui(plugin, levelsHandler);

        loadConfiguration();
        setupEconomy();
        startCleanupTask();
    }

    /**
     * Setup Vault economy integration with proper error handling
     */
    private void setupEconomy() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning("Vault not found! Economy features will be disabled.");
            return;
        }

        try {
            RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp == null) {
                plugin.getLogger().warning("No economy provider found! Economy features will be disabled.");
                return;
            }

            economy = rsp.getProvider();
            if (economy == null) {
                plugin.getLogger().warning("Failed to get economy provider! Economy features will be disabled.");
                return;
            }

            plugin.getLogger().info("Successfully hooked into Vault economy: " + economy.getName());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error setting up Vault economy integration", e);
            economy = null;
        }
    }

    /**
     * Load GUI configuration
     */
    private void loadConfiguration() {
        try {
            FileConfiguration config = plugin.getConfigManager().getGuiConfig();
            if (config != null) {
                clickDelayEnabled = config.getBoolean("click_delay.enabled", true);
                clickDelayTicks = config.getLong("click_delay.delay_ticks", 10);
            } else {
                clickDelayEnabled = true;
                clickDelayTicks = 10;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error loading GUI configuration", e);
            clickDelayEnabled = true;
            clickDelayTicks = 10;
        }
    }

    /**
     * ENHANCED: Open upgrade GUI with comprehensive level and tool validation
     */
    public boolean openUpgradeGui(Player player, ItemStack heldTool) {
        if (player == null || heldTool == null) {
            return false;
        }

        // Check if tool is a GhastTool
        if (!plugin.getToolManager().isGhastTool(heldTool)) {
            player.sendMessage("§cYou must be holding a Tool!");
            return false;
        }

        // Get tool information
        String toolType = plugin.getToolManager().getToolType(heldTool);
        int toolTier = plugin.getToolManager().getToolTier(heldTool);
        String toolId = plugin.getToolManager().getToolId(heldTool);

        if (toolType == null || toolId == null) {
            player.sendMessage("§cInvalid tool data!");
            return false;
        }

        // Validate tool tier bounds
        if (toolTier <= 0 || toolTier > 6) {
            player.sendMessage("§cInvalid tool tier!");
            return false;
        }

        // ENHANCED: Check level requirement for current tool
        if (!validateToolLevelRequirement(player, toolType, toolTier)) {
            return false;
        }

        // Create tool lock
        int heldSlot = getHeldToolSlot(player, heldTool);
        LockedTool lockedTool = new LockedTool(player.getUniqueId(), heldTool,
                toolType, toolTier, toolId, heldSlot);
        lockedTools.put(player.getUniqueId(), lockedTool);

        try {
            // Create GUI
            Inventory gui = createUpgradeGui(player, heldTool, toolType, toolTier, toolId);

            // Open GUI on main thread
            if (Bukkit.isPrimaryThread()) {
                player.openInventory(gui);
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> player.openInventory(gui));
            }

            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error opening upgrade GUI for " + player.getName(), e);
            // Remove lock if GUI failed to open
            lockedTools.remove(player.getUniqueId());
            return false;
        }
    }

    /**
     * ENHANCED: Validate tool level requirement
     */
    private boolean validateToolLevelRequirement(Player player, String toolType, int toolTier) {
        if (levelsHandler == null) {
            return true; // Allow if levels handler not available
        }

        int requiredLevel = getToolLevelRequirement(toolType, toolTier);
        return levelsHandler.canPerformAction(player, "use this " + toolType + " tier " + toolTier, requiredLevel);
    }

    /**
     * Get tool level requirement
     */
    private int getToolLevelRequirement(String toolType, int toolTier) {
        try {
            var toolConfig = plugin.getToolManager().getToolConfigs().get(toolType);
            if (toolConfig != null) {
                var tierConfig = toolConfig.getTier(toolTier);
                if (tierConfig != null) {
                    return tierConfig.getLevelRequirement();
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Error getting tool level requirement: " + e.getMessage());
        }
        return 1; // Default requirement
    }

    /**
     * ENHANCED: Open random win GUI with essence level validation
     */
    public boolean openRandomWinGui(Player player, ItemStack heldTool) {
        if (randomWinGui != null) {
            // Check and withdraw GUI opening price first
            if (!checkAndWithdrawRandomWinPrice(player)) {
                return false;
            }
            return randomWinGui.openRandomWinGui(player);
        }
        return false;
    }

    /**
     * Open shop GUI for player
     */
    public boolean openShopGui(Player player, ItemStack heldTool) {
        if (shopGui != null) {
            return shopGui.openShopGui(player);
        }
        return false;
    }

    /**
     * FIXED: Check and withdraw random win GUI opening price with essence level integration
     */
    private boolean checkAndWithdrawRandomWinPrice(Player player) {
        try {
            FileConfiguration randomWinConfig = plugin.getConfigManager().getConfig("random_win.yml");
            if (randomWinConfig == null) {
                return false;
            }

            int price = randomWinConfig.getInt("currency.price", 1);
            String currencyType = randomWinConfig.getString("currency.type", "essence_levels");

            // FIXED: Use essence levels instead of placeholder system
            if ("essence_levels".equals(currencyType)) {
                if (plugin.getEssenceHandler() == null || !plugin.getEssenceHandler().isAvailable()) {
                    return false;
                }

                // Check if player has enough essence levels
                if (!plugin.getEssenceHandler().hasEnoughEssenceLevels(player, price)) {
                    int currentLevel = plugin.getEssenceHandler().getPlayerEssenceLevel(player);
                    player.sendMessage("§cYou need " + price + " essence to open this! Your Have: " + currentLevel);
                    return false;
                }

                // Withdraw essence levels
                if (!plugin.getEssenceHandler().withdrawEssenceLevels(player, price)) {

                    return false;
                }

                return true;
            } else {
                player.sendMessage("§cUnsupported currency type: " + currencyType);
                return false;
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error checking random win price", e);
            return false;
        }
    }

    /**
     * Create the upgrade GUI inventory
     */
    private Inventory createUpgradeGui(Player player, ItemStack playerTool,
                                       String toolType, int toolTier, String toolId) {

        FileConfiguration config = plugin.getConfigManager().getGuiConfig();
        if (config == null) {
            throw new RuntimeException("GUI configuration not found!");
        }

        String title = ChatColor.translateAlternateColorCodes('&',
                config.getString("gui.title", "&6&lGhastTools Upgrade Menu"));
        int size = config.getInt("gui.size", 27);

        // Create inventory
        Inventory gui = Bukkit.createInventory(null, size, title);

        // Add filler items
        addFillerItems(gui, config);

        // Add preview tool (slot 13)
        addPreviewTool(gui, playerTool, toolType, toolTier, config);

        // Add menu buttons
        addMenuButtons(gui, config, player);

        // Add upgrade icons
        addUpgradeIcons(gui, config, player, toolType, toolTier);

        return gui;
    }

    /**
     * Add filler items to GUI
     */
    private void addFillerItems(Inventory gui, FileConfiguration config) {
        ConfigurationSection fillerSection = config.getConfigurationSection("gui.items.filler");
        if (fillerSection == null) return;

        try {
            Material material = Material.valueOf(fillerSection.getString("material", "GRAY_STAINED_GLASS_PANE"));
            String name = ChatColor.translateAlternateColorCodes('&', fillerSection.getString("name", " "));
            List<Integer> slots = fillerSection.getIntegerList("slots");

            ItemStack filler = new ItemStack(material);
            ItemMeta meta = filler.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(name);
                filler.setItemMeta(meta);
            }

            for (int slot : slots) {
                if (slot >= 0 && slot < gui.getSize()) {
                    gui.setItem(slot, filler);
                }
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error adding filler items", e);
        }
    }

    /**
     * Add preview tool to GUI
     */
    private void addPreviewTool(Inventory gui, ItemStack playerTool, String toolType,
                                int toolTier, FileConfiguration config) {
        try {
            int previewSlot = config.getInt("gui.items.preview_tool.slot", 13);

            // Clone the player's tool for preview
            ItemStack previewTool = playerTool.clone();
            ItemMeta meta = previewTool.getItemMeta();

            if (meta != null) {
                // Add preview indicator to lore
                List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add("");
                lore.add("§7§o(Preview - Cannot be taken)");
                meta.setLore(lore);
                previewTool.setItemMeta(meta);
            }

            gui.setItem(previewSlot, previewTool);

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error adding preview tool", e);
        }
    }

    /**
     * Add menu buttons to GUI with random win and shop integration
     */
    private void addMenuButtons(Inventory gui, FileConfiguration config, Player player) {
        addRandomWinButton(gui, config);
        addShopButton(gui, config);
    }

    /**
     * Add random win button
     */
    private void addRandomWinButton(Inventory gui, FileConfiguration config) {
        ConfigurationSection buttonSection = config.getConfigurationSection("gui.items.menu_1");
        if (buttonSection == null) return;

        try {
            Material material = Material.valueOf(buttonSection.getString("material", "EMERALD"));
            int slot = buttonSection.getInt("slot", 10);
            String name = "§6&lRandom Enchantment";
            int modelData = buttonSection.getInt("model-data", 0);

            List<String> lore = new ArrayList<>();
            lore.add("§7Try your luck and roll a");
            lore.add("§7random enchantment for your tool!");
            lore.add("");
            lore.add("§7§lCost: §c50 §7Essence");
            lore.add("§7§lRequirements:");
            lore.add("§7- Must hold a Tool");
            lore.add("§7- Must have enough Essence");
            lore.add("");
            lore.add("§e§lClick to roll!");

            ItemStack button = new ItemStack(material);
            ItemMeta meta = button.getItemMeta();

            if (meta != null) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
                meta.setLore(lore);

                if (modelData > 0) {
                    meta.setCustomModelData(modelData);
                }

                button.setItemMeta(meta);
            }

            if (slot >= 0 && slot < gui.getSize()) {
                gui.setItem(slot, button);
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error adding random win button", e);
        }
    }

    /**
     * Add shop button
     */
    private void addShopButton(Inventory gui, FileConfiguration config) {
        ConfigurationSection buttonSection = config.getConfigurationSection("gui.items.menu_2");
        if (buttonSection == null) return;

        try {
            Material material = Material.valueOf(buttonSection.getString("material", "DIAMOND"));
            int slot = buttonSection.getInt("slot", 12);
            String name = "§5&lEnchantment Shop";
            int modelData = buttonSection.getInt("model-data", 0);

            List<String> lore = new ArrayList<>();
            lore.add("§7Don't want to risk bad luck?");
            lore.add("§7Browse powerful enchantments");
            lore.add("§7and purchase upgrades directly!");
            lore.add("");
            lore.add("§7§lFeatures:");
            lore.add("§7- Buy enchantments using Essence");
            lore.add("§7- Prices scale with enchant level");
            lore.add("");
            lore.add("§e§lClick to shop!");

            ItemStack button = new ItemStack(material);
            ItemMeta meta = button.getItemMeta();

            if (meta != null) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
                meta.setLore(lore);

                if (modelData > 0) {
                    meta.setCustomModelData(modelData);
                }

                button.setItemMeta(meta);
            }

            if (slot >= 0 && slot < gui.getSize()) {
                gui.setItem(slot, button);
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error adding shop button", e);
        }
    }

    /**
     * ENHANCED: Add upgrade icons with comprehensive level checking and currency validation
     */
    private void addUpgradeIcons(Inventory gui, FileConfiguration config, Player player,
                                 String toolType, int currentTier) {
        ConfigurationSection upgradeSection = config.getConfigurationSection("gui.items.upgrade_icon");
        if (upgradeSection == null) return;

        try {
            Material material = Material.valueOf(upgradeSection.getString("material", "GOLD_NUGGET"));
            String baseName = upgradeSection.getString("name", "§5Upgrade Tool");
            List<String> baseLore = upgradeSection.getStringList("lore");
            int modelData = upgradeSection.getInt("model-data", 0);
            List<Integer> slots = upgradeSection.getIntegerList("slots");

            // Find upgrade configuration for current tool
            UpgradeConfig upgradeConfig = findUpgradeConfig(config, toolType, currentTier);

            for (int slot : slots) {
                if (slot >= 0 && slot < gui.getSize()) {
                    ItemStack upgradeIcon = createUpgradeIcon(material, baseName, baseLore,
                            modelData, upgradeConfig, player);
                    gui.setItem(slot, upgradeIcon);
                }
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error adding upgrade icons", e);
        }
    }

    /**
     * ENHANCED: Create upgrade icon with comprehensive requirement checking
     */
    private ItemStack createUpgradeIcon(Material material, String baseName, List<String> baseLore,
                                        int modelData, UpgradeConfig upgradeConfig, Player player) {
        ItemStack icon = new ItemStack(material);
        ItemMeta meta = icon.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', baseName));

            List<String> lore = new ArrayList<>();

            if (upgradeConfig != null) {
                // Get player's current level
                int playerLevel = levelsHandler != null ? levelsHandler.getPlayerLevel(player) : 0;

                for (String line : baseLore) {
                    String processedLine = line
                            .replace("{price}", formatNumber(upgradeConfig.getMoney()))
                            .replace("{level}", String.valueOf(upgradeConfig.getLevel()));

                    lore.add(ChatColor.translateAlternateColorCodes('&', processedLine));
                }

                // Add comprehensive requirement status checking
                lore.add("");

                // ENHANCED: Level requirement check with levels handler
                if (levelsHandler != null && !player.hasPermission("ghasttools.bypass.levelcheck")) {
                    if (levelsHandler.meetsLevelRequirement(player, upgradeConfig.getLevel())) {
                        lore.add("§a✓ Level requirement met");
                    } else {
                        lore.add("§c✗ Level requirement not met");
                        lore.add("§c  Required: " + upgradeConfig.getLevel() + " | Your Level: " + playerLevel);
                    }
                } else {
                    lore.add("§7✓ Level requirement bypassed");
                }

                // Money requirement check
                if (hasEnoughMoney(player, upgradeConfig.getMoney())) {
                    lore.add("§a✓ Money requirement met");
                } else {
                    lore.add("§c✗ Not enough money");
                    lore.add("§c  Required: " + formatNumber(upgradeConfig.getMoney()));
                }

                // Add upgrade target info
                lore.add("");
                lore.add("§7Upgrade: §e" + upgradeConfig.getCurrentTier() + " §7→ §a" + upgradeConfig.getTargetTier());

                // Show current balance if economy is available
                if (isEconomyAvailable()) {
                    double balance = getPlayerBalance(player);
                    lore.add("§7Your balance: §e" + formatNumber((long)balance));
                }

            } else {
                // Tool is at max tier or no upgrade available
                lore.add("§7This tool is already at maximum tier");
                lore.add("§7or no upgrade is available!");
            }

            meta.setLore(lore);

            if (modelData > 0) {
                meta.setCustomModelData(modelData);
            }

            icon.setItemMeta(meta);
        }

        return icon;
    }

    /**
     * Find upgrade configuration for tool type and tier
     */
    private UpgradeConfig findUpgradeConfig(FileConfiguration config, String toolType, int currentTier) {
        ConfigurationSection upgradesSection = config.getConfigurationSection("gui.items.upgrade_icon.upgrades");
        if (upgradesSection == null) return null;

        String upgradeKey = toolType + "_" + currentTier + "_to_" + (currentTier + 1);
        ConfigurationSection upgradeSection = upgradesSection.getConfigurationSection(upgradeKey);

        if (upgradeSection == null) return null;

        return new UpgradeConfig(
                upgradeSection.getString("type"),
                upgradeSection.getInt("current_tier"),
                upgradeSection.getInt("target_tier"),
                upgradeSection.getInt("level"),
                upgradeSection.getLong("money")
        );
    }

    /**
     * ENHANCED: Handle GUI click with comprehensive validation and proper error handling
     */
    public void handleGuiClick(Player player, int slot, boolean isLeftClick) {
        if (player == null || isShuttingDown.get()) return;

        // Check click delay
        if (clickDelayEnabled && isOnClickDelay(player)) {
            return;
        }

        // Set click delay
        if (clickDelayEnabled) {
            setClickDelay(player);
        }

        try {
            FileConfiguration config = plugin.getConfigManager().getGuiConfig();
            if (config == null) return;

            String currentTitle = player.getOpenInventory().getTitle();

            // Check if it's a random win GUI
            if (randomWinGui != null && randomWinGui.isRandomWinGui(currentTitle)) {
                return;
            }

            // Check if it's a shop GUI
            if (shopGui != null && shopGui.isShopGui(currentTitle)) {
                shopGui.handleShopClick(player, slot, isLeftClick);
                return;
            }

            // Check if it's upgrade GUI
            String upgradeGuiTitle = ChatColor.translateAlternateColorCodes('&',
                    config.getString("gui.title", "&6&lGhastTools Upgrade Menu"));

            if (!currentTitle.equals(upgradeGuiTitle)) {
                return;
            }

            // Validate player has a locked tool
            LockedTool lockedTool = lockedTools.get(player.getUniqueId());
            if (lockedTool == null) {
                player.sendMessage("§cPlease try again.");
                player.closeInventory();
                return;
            }

            // Handle different button clicks
            if (isUpgradeIconSlot(config, slot)) {
                handleUpgradeClick(player, lockedTool, isLeftClick);
            } else if (isMenuButtonSlot(config, slot, "menu_1")) {
                handleRandomWinClick(player, lockedTool);
            } else if (isMenuButtonSlot(config, slot, "menu_2")) {
                handleShopClick(player, lockedTool);
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error handling GUI click for " + player.getName(), e);
        }
    }

    /**
     * Handle random win button click
     */
    private void handleRandomWinClick(Player player, LockedTool lockedTool) {
        try {
            ItemStack currentTool = getCurrentHeldTool(player);
            if (currentTool == null || !plugin.getToolManager().isGhastTool(currentTool)) {
                player.sendMessage("§cYou must be holding a Tool!");
                return;
            }

            String currentToolType = plugin.getToolManager().getToolType(currentTool);
            int currentToolTier = plugin.getToolManager().getToolTier(currentTool);
            String currentToolId = plugin.getToolManager().getToolId(currentTool);

            if (!lockedTool.validateTool(currentTool, currentToolType, currentToolTier, currentToolId)) {
                player.sendMessage("§cTool has been modified! Please close and reopen the GUI.");
                return;
            }

            player.closeInventory();

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    boolean success = openRandomWinGui(player, currentTool);
                    if (!success) {
                        player.sendMessage("§cFailed to open random enchantment menu! Please try again.");
                    }
                }
            }, 3L);

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error opening random win GUI", e);
            player.sendMessage("§cAn error occurred while opening the random enchantment menu!");
        }
    }

    /**
     * Handle shop button click
     */
    private void handleShopClick(Player player, LockedTool lockedTool) {
        try {
            ItemStack currentTool = getCurrentHeldTool(player);
            if (currentTool == null || !plugin.getToolManager().isGhastTool(currentTool)) {
                player.sendMessage("§cYou must be holding a Tool!");
                return;
            }

            String currentToolType = plugin.getToolManager().getToolType(currentTool);
            int currentToolTier = plugin.getToolManager().getToolTier(currentTool);
            String currentToolId = plugin.getToolManager().getToolId(currentTool);

            if (!lockedTool.validateTool(currentTool, currentToolType, currentToolTier, currentToolId)) {
                player.sendMessage("§cTool has been modified! Please close and reopen the GUI.");
                return;
            }

            player.closeInventory();

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    boolean success = openShopGui(player, currentTool);
                    if (!success) {
                        player.sendMessage("§c Please try again.");
                    }
                }
            }, 3L);

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error opening shop GUI", e);
        }
    }

    /**
     * ENHANCED: Handle upgrade icon click with comprehensive validation
     */
    private void handleUpgradeClick(Player player, LockedTool lockedTool, boolean isLeftClick) {
        ItemStack currentTool = getCurrentHeldTool(player);

        if (currentTool == null || !plugin.getToolManager().isGhastTool(currentTool)) {
            player.sendMessage("§cYou must be holding a Tool!");
            player.closeInventory();
            return;
        }

        String currentToolType = plugin.getToolManager().getToolType(currentTool);
        int currentToolTier = plugin.getToolManager().getToolTier(currentTool);
        String currentToolId = plugin.getToolManager().getToolId(currentTool);

        // Validate tool hasn't changed
        if (!lockedTool.validateTool(currentTool, currentToolType, currentToolTier, currentToolId)) {
            player.sendMessage("§cTool has been modified or switched! Upgrade cancelled for security.");
            player.closeInventory();
            return;
        }

        // Check if tool is at max tier
        if (currentToolTier >= 6) {
            player.sendMessage("§cYour tool is already at maximum tier!");
            return;
        }

        // Find upgrade configuration
        FileConfiguration config = plugin.getConfigManager().getGuiConfig();
        UpgradeConfig upgradeConfig = findUpgradeConfig(config, currentToolType, currentToolTier);

        if (upgradeConfig == null) {
            player.sendMessage("§cNo upgrade available for this tool!");
            return;
        }

        // ENHANCED: Check level requirement using levelshandler
        if (levelsHandler != null && !player.hasPermission("ghasttools.bypass.levelcheck")) {
            if (!levelsHandler.meetsLevelRequirement(player, upgradeConfig.getLevel())) {
                int playerLevel = levelsHandler.getPlayerLevel(player);
                player.sendMessage("§cYou need to be level " + upgradeConfig.getLevel() + " to upgrade! Your level: " + playerLevel);
                return;
            }
        }

        // Check money requirement
        if (!hasEnoughMoney(player, upgradeConfig.getMoney())) {
            player.sendMessage("§cYou need " + formatNumber(upgradeConfig.getMoney()) + " money to upgrade!");
            return;
        }

        // Perform upgrade
        if (performUpgrade(player, currentTool, upgradeConfig)) {
            player.sendMessage("§a✓ Tool upgraded successfully!");


            // Play success sound
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);

            player.closeInventory();

            // Remove tool lock after successful upgrade
            lockedTools.remove(player.getUniqueId());
        } else {
            player.sendMessage("§cUpgrade failed! Please try again.");
        }
    }

    /**
     * Perform the actual tool upgrade with currency withdrawal
     */
    private boolean performUpgrade(Player player, ItemStack tool, UpgradeConfig upgradeConfig) {
        try {
            // Withdraw money first
            if (!withdrawMoney(player, upgradeConfig.getMoney())) {
                return false;
            }

            // Upgrade tool
            plugin.getToolManager().upgradeTool(player, tool, upgradeConfig.getTargetTier());

            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error performing tool upgrade", e);
            return false;
        }
    }

    /**
     * Check if player has enough money using Vault economy
     */
    private boolean hasEnoughMoney(Player player, long amount) {
        try {
            if (economy == null) {
                plugin.getLogger().warning("Economy not available for balance check");
                return false;
            }

            double balance = economy.getBalance(player);
            return balance >= amount;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error checking money for " + player.getName(), e);
            return false;
        }
    }

    /**
     * Withdraw money from player using Vault economy
     */
    private boolean withdrawMoney(Player player, long amount) {
        try {
            if (economy == null) {
                plugin.getLogger().warning("Economy not available for money withdrawal");
                return false;
            }

            if (!hasEnoughMoney(player, amount)) {
                double balance = economy.getBalance(player);
                player.sendMessage("§cInsufficient funds! Required: " + formatNumber(amount) + 
                                 " | Your balance: " + formatNumber((long)balance));
                return false;
            }

            var response = economy.withdrawPlayer(player, amount);
            if (response.transactionSuccess()) {
                plugin.getLogger().info("Successfully withdrew " + amount + " from " + player.getName() + 
                                      " (New balance: " + economy.getBalance(player) + ")");
                return true;
            } else {
                plugin.getLogger().warning("Economy withdrawal failed for " + player.getName() + 
                                         ": " + response.errorMessage);
                player.sendMessage("§cTransaction failed: " + response.errorMessage);
                return false;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error withdrawing money for " + player.getName(), e);
            player.sendMessage("§cAn error occurred during the transaction!");
            return false;
        }
    }

    /**
     * Get player's current balance for display
     */
    public double getPlayerBalance(Player player) {
        try {
            if (economy == null) {
                return 0.0;
            }
            return economy.getBalance(player);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error getting balance for " + player.getName(), e);
            return 0.0;
        }
    }

    /**
     * Check if economy is available
     */
    public boolean isEconomyAvailable() {
        return economy != null;
    }

    /**
     * Get economy provider name for debugging
     */
    public String getEconomyProviderName() {
        return economy != null ? economy.getName() : "None";
    }

    /**
     * Get current held tool (re-check for validation)
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
     * Get slot of held tool
     */
    private int getHeldToolSlot(Player player, ItemStack tool) {
        if (player.getInventory().getItemInMainHand().equals(tool)) {
            return player.getInventory().getHeldItemSlot();
        } else if (player.getInventory().getItemInOffHand().equals(tool)) {
            return 40; // Offhand slot
        }
        return -1;
    }

    /**
     * Check if slot is an upgrade icon slot
     */
    private boolean isUpgradeIconSlot(FileConfiguration config, int slot) {
        List<Integer> upgradeSlots = config.getIntegerList("gui.items.upgrade_icon.slots");
        return upgradeSlots.contains(slot);
    }

    /**
     * Check if slot is a menu button slot
     */
    private boolean isMenuButtonSlot(FileConfiguration config, int slot, String buttonKey) {
        int buttonSlot = config.getInt("gui.items." + buttonKey + ".slot", -1);
        return buttonSlot == slot;
    }

    /**
     * Check click delay
     */
    private boolean isOnClickDelay(Player player) {
        Long lastClick = lastClickTime.get(player.getUniqueId());
        if (lastClick == null) return false;

        long currentTime = System.currentTimeMillis();
        long delayMs = clickDelayTicks * 50; // Convert ticks to milliseconds

        return (currentTime - lastClick) < delayMs;
    }

    /**
     * Set click delay
     */
    private void setClickDelay(Player player) {
        lastClickTime.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /**
     * Format number for display
     */
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
     * Clean up expired locks and click delays
     */
    private void startCleanupTask() {
        cleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!isShuttingDown.get() && !plugin.isShuttingDown()) {
                // Clean up expired tool locks
                lockedTools.entrySet().removeIf(entry -> entry.getValue().isExpired());

                // Clean up old click delays (older than 5 minutes)
                long cutoffTime = System.currentTimeMillis() - 300000; // 5 minutes
                lastClickTime.entrySet().removeIf(entry -> entry.getValue() < cutoffTime);

                // Clean up offline players
                lockedTools.entrySet().removeIf(entry -> {
                    Player player = Bukkit.getPlayer(entry.getKey());
                    return player == null || !player.isOnline();
                });

                lastClickTime.entrySet().removeIf(entry -> {
                    Player player = Bukkit.getPlayer(entry.getKey());
                    return player == null || !player.isOnline();
                });
            }
        }, 20L * 60, 20L * 60); // Run every minute
    }

    /**
     * Remove tool lock when player closes inventory
     */
    public void removeLock(Player player) {
        if (player != null) {
            lockedTools.remove(player.getUniqueId());
        }
    }

    /**
     * Get locked tool for player
     */
    public LockedTool getLockedTool(Player player) {
        if (player == null) return null;
        return lockedTools.get(player.getUniqueId());
    }

    public void reloadAllConfigs() {
        loadConfiguration(); // Reload main GUI config

        if (shopGui != null) {
            shopGui.reloadConfig();
        }

        if (randomWinGui != null) {
            randomWinGui.reloadConfig();
        }
    }

    /**
     * Check GUI types by config titles
     */
    public boolean isUpgradeGui(String title) {
        if (title == null) return false;

        FileConfiguration config = plugin.getConfigManager().getGuiConfig();
        if (config == null) return false;

        String configTitle = ChatColor.translateAlternateColorCodes('&',
                config.getString("gui.title", "&6&lGhastTools Upgrade Menu"));

        return title.equals(configTitle);
    }

    /**
     * Check if GUI is random win GUI
     */
    public boolean isRandomWinGui(String title) {
        if (randomWinGui != null) {
            return randomWinGui.isRandomWinGui(title);
        }
        return false;
    }

    /**
     * Check if GUI is shop GUI
     */
    public boolean isShopGui(String title) {
        if (shopGui != null) {
            return shopGui.isShopGui(title);
        }
        return false;
    }

    /**
     * Get RandomWinGui instance
     */
    public RandomWinGui getRandomWinGui() {
        return randomWinGui;
    }

    /**
     * Get ShopGui instance
     */
    public ShopGui getShopGui() {
        return shopGui;
    }

    /**
     * ENHANCED: Complete shutdown with proper cleanup
     */
    public void shutdown() {
        isShuttingDown.set(true);

        if (cleanupTask != null && !cleanupTask.isCancelled()) {
            cleanupTask.cancel();
        }

        // Shutdown all GUI systems
        if (randomWinGui != null) {
            randomWinGui.shutdown();
        }

        if (shopGui != null) {
            shopGui.shutdown();
        }

        lockedTools.clear();
        lastClickTime.clear();

        plugin.getLogger().info("GuiManager shutdown complete");
    }

    /**
     * Inner class for upgrade configuration
     */
    public static class UpgradeConfig {
        private final String type;
        private final int currentTier;
        private final int targetTier;
        private final int level;
        private final long money;

        public UpgradeConfig(String type, int currentTier, int targetTier, int level, long money) {
            this.type = type;
            this.currentTier = currentTier;
            this.targetTier = targetTier;
            this.level = level;
            this.money = money;
        }

        public String getType() {
            return type;
        }

        public int getCurrentTier() {
            return currentTier;
        }

        public int getTargetTier() {
            return targetTier;
        }

        public int getLevel() {
            return level;
        }

        public long getMoney() {
            return money;
        }
    }
}