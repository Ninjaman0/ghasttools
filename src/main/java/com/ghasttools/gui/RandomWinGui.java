package com.ghasttools.gui;

import com.ghasttools.GhastToolsPlugin;
import com.ghasttools.levelsmanager.levelshandler;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * FIXED: Random Win GUI with proper animation stopping at winning slot and enhanced thread safety
 * FIXED: Double enchantment issue when player closes GUI during pause after landing
 */
public class RandomWinGui {

    private final GhastToolsPlugin plugin;
    private final levelshandler levelsHandler;
    private final Random random = new Random();
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);

    // ENHANCED: Thread-safe player blocking during animation
    private final ConcurrentHashMap<UUID, Long> blockedPlayers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AnimationSession> activeAnimations = new ConcurrentHashMap<>();

    // Configuration cache with thread safety
    private volatile FileConfiguration randomWinConfig;
    private volatile int animationSpeed;
    private volatile String animationType;
    private volatile boolean canEscape;
    private volatile int winningSlot;
    private volatile int pauseTicks;
    private volatile int totalTimeSeconds;

    public RandomWinGui(GhastToolsPlugin plugin, levelshandler levelsHandler) {
        this.plugin = plugin;
        this.levelsHandler = levelsHandler;
        loadConfiguration();
    }

    /**
     * FIXED: Thread-safe configuration loading
     */
    private void loadConfiguration() {
        try {
            randomWinConfig = plugin.getConfigManager().getConfig("random_win.yml");
            if (randomWinConfig == null) {
                plugin.getLogger().severe("random_win.yml configuration not found!");
                setDefaultConfiguration();
                return;
            }

            this.animationSpeed = Math.max(1, randomWinConfig.getInt("animation.speed", 13));
            this.animationType = randomWinConfig.getString("animation.type", "csgo");
            this.canEscape = randomWinConfig.getBoolean("animation.can_escape", true);
            this.winningSlot = randomWinConfig.getInt("animation.winning_slot", 14);
            this.pauseTicks = Math.max(1, randomWinConfig.getInt("animation.pause", 40));
            this.totalTimeSeconds = Math.max(1, randomWinConfig.getInt("animation.total_time", 7));

            plugin.getLogger().info("Random win configuration loaded - Speed: " + animationSpeed + " ticks");

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error loading random win configuration", e);
            setDefaultConfiguration();
        }
    }

    /**
     * Set safe default configuration values
     */
    private void setDefaultConfiguration() {
        this.animationSpeed = 13;
        this.animationType = "csgo";
        this.canEscape = true;
        this.winningSlot = 14;
        this.pauseTicks = 40;
        this.totalTimeSeconds = 7;
    }

    /**
     * ENHANCED: Open random win GUI with comprehensive validation and essence level checking
     */
    public boolean openRandomWinGui(Player player) {
        if (player == null || randomWinConfig == null) {
            return false;
        }

        try {
            // Check if player is already in animation
            if (isPlayerBlocked(player)) {
                player.sendMessage("§cYou are already in a random enchantment animation!");
                return false;
            }

            // Get and validate held tool
            ItemStack heldTool = getCurrentHeldTool(player);
            if (heldTool == null) {
                player.sendMessage("§cYou must be holding a Tool!");
                return false;
            }

            String toolType = plugin.getToolManager().getToolType(heldTool);
            int toolTier = plugin.getToolManager().getToolTier(heldTool);

            if (toolType == null || toolTier <= 0) {
                player.sendMessage("§cInvalid tool data!");
                return false;
            }

            // ENHANCED: Level requirement validation
            if (!validateLevelRequirement(player, toolType, toolTier)) {
                return false;
            }

            // Get available items for the tool
            List<RandomWinItem> availableItems = getAvailableItems(toolType, toolTier);
            if (availableItems.isEmpty()) {
                player.sendMessage("§cNo enchantments available for your tool!");
                return false;
            }

            // Select winning item based on weighted chances
            RandomWinItem winningItem = selectWinningItem(availableItems);
            if (winningItem == null) {
                player.sendMessage("§cFailed to select winning item!");
                return false;
            }

            // Create and open GUI
            Inventory gui = createRandomWinGui(player, availableItems, winningItem);

            // Open on main thread
            if (Bukkit.isPrimaryThread()) {
                player.openInventory(gui);
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> player.openInventory(gui));
            }

            // FIXED: Start animation with proper winning slot targeting
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && gui.equals(player.getOpenInventory().getTopInventory())) {
                    startEnhancedAnimation(player, gui, availableItems, winningItem, heldTool);
                }
            }, 5L);

            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error opening random win GUI for " + player.getName(), e);
            return false;
        }
    }

    /**
     * FIXED: Enhanced animation system with proper winning slot targeting and double enchantment prevention
     */
    private void startEnhancedAnimation(Player player, Inventory gui, List<RandomWinItem> items,
                                        RandomWinItem winningItem, ItemStack tool) {

        // Block player interactions
        blockedPlayers.put(player.getUniqueId(), System.currentTimeMillis());

        // Get animation slots
        List<Integer> itemSlots = randomWinConfig.getIntegerList("gui.items_slots");
        if (itemSlots.isEmpty()) {
            plugin.getLogger().warning("No item slots defined for animation!");
            giveWinningItem(player, tool, winningItem);
            return;
        }

        // FIXED: Calculate proper animation timing to land on winning slot
        final int totalTicks = this.totalTimeSeconds * 20; // Total animation duration in ticks
        final int slowdownPoint = (int) (totalTicks * 0.7); // Start slowing at 70%
        final int landingPoint = (int) (totalTicks * 0.9); // Start landing sequence at 90%

        AnimationSession session = new AnimationSession(player, gui, items, winningItem, tool, itemSlots);
        activeAnimations.put(player.getUniqueId(), session);

        BukkitTask animationTask = new BukkitRunnable() {
            private int currentTick = 0;
            private int currentSpeed = animationSpeed;
            private boolean isSlowingDown = false;
            private boolean isLanding = false;
            private boolean hasLanded = false;
            private boolean winningItemScheduled = false; // FIXED: Track if winning item is scheduled
            private boolean rewardGiven = false; // FIXED: Track if any reward has been given
            private int itemIndex = 0; // Current item being shown in winning slot

            @Override
            public void run() {
                // Safety checks
                if (isShuttingDown.get() || plugin.isShuttingDown()) {
                    cleanup();
                    return;
                }

                if (!player.isOnline()) {
                    cleanup();
                    return;
                }

                // FIXED: Check if player closed inventory (escape) - but only give reward if not already given/scheduled
                if (canEscape && !rewardGiven && !winningItemScheduled) {
                    try {
                        Inventory currentInventory = player.getOpenInventory().getTopInventory();
                        if (!gui.equals(currentInventory)) {
                            giveRandomItem(player, tool, items);
                            rewardGiven = true; // FIXED: Mark reward as given
                            cleanup();
                            return;
                        }
                    } catch (Exception e) {
                        giveRandomItem(player, tool, items);
                        rewardGiven = true; // FIXED: Mark reward as given
                        cleanup();
                        return;
                    }
                }

                try {
                    // FIXED: Enhanced animation phases
                    if (currentTick >= landingPoint && !isLanding) {
                        // LANDING PHASE: Slow down significantly and target winning item
                        isLanding = true;
                        currentSpeed = Math.max(animationSpeed * 3, 20); // Much slower

                    } else if (currentTick >= slowdownPoint && !isSlowingDown) {
                        // SLOWDOWN PHASE: Gradually reduce speed
                        isSlowingDown = true;
                        currentSpeed = Math.max(animationSpeed * 2, currentSpeed + 5);

                    }

                    // Update animation only at appropriate intervals
                    if (currentTick % Math.max(1, currentSpeed / 4) == 0) {
                        if (isLanding && !hasLanded) {
                            // FIXED: Landing sequence - ensure winning item lands in winning slot
                            landWinningItem(gui, winningItem, items, itemSlots);
                            hasLanded = true;

                            // Play landing sound
                            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                            player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 0.8f, 1.2f);

                            // FIXED: Schedule completion and mark as scheduled to prevent escape reward
                            winningItemScheduled = true;
                            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                                if (!rewardGiven) { // FIXED: Only give if no reward given yet
                                    giveWinningItem(player, tool, winningItem);
                                    rewardGiven = true;
                                }
                                cleanup();

                                // Close GUI after delay
                                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                                    if (player.isOnline()) {
                                        player.closeInventory();
                                    }
                                }, 20L);
                            }, pauseTicks);

                        } else if (!isLanding) {
                            // NORMAL ANIMATION: Update all slots
                            updateNormalAnimation(gui, items, itemSlots);

                            // Play rolling sound
                            if (currentTick % (animationSpeed * 2) == 0) {
                                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.0f + (float) (Math.random() * 0.4 - 0.2));
                            }
                        }
                    }

                    currentTick++;

                    // Safety timeout
                    if (currentTick > totalTicks + 100) { // +100 ticks safety margin
                        if (!hasLanded && !rewardGiven) {
                            landWinningItem(gui, winningItem, items, itemSlots);
                            giveWinningItem(player, tool, winningItem);
                            rewardGiven = true;
                        }
                        cleanup();
                    }

                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Error in animation update", e);
                    cleanup();
                }
            }

            private void cleanup() {
                blockedPlayers.remove(player.getUniqueId());
                activeAnimations.remove(player.getUniqueId());
                this.cancel();
                plugin.getLogger().fine("Animation cleaned up for " + player.getName());
            }

        }.runTaskTimer(plugin, 0L, 1L); // Run every tick for smooth animation

        session.setAnimationTask(animationTask);
    }

    /**
     * FIXED: Landing sequence to ensure winning item appears in winning slot
     */
    private void landWinningItem(Inventory gui, RandomWinItem winningItem, List<RandomWinItem> items, List<Integer> itemSlots) {
        try {
            // Place winning item in the designated winning slot
            gui.setItem(winningSlot, createDisplayItem(winningItem));

            // Dim other slots to highlight the winner
            for (int slot : itemSlots) {
                if (slot != winningSlot && slot >= 0 && slot < gui.getSize()) {
                    ItemStack dimmedItem = createDimmedItem();
                    gui.setItem(slot, dimmedItem);
                }
            }

            // Add visual effects to all viewers
            for (HumanEntity viewer : gui.getViewers()) {
                if (viewer instanceof Player) {
                    Player p = (Player) viewer;
                    // Celebration sounds
                    p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.5f, 1.0f);
                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.5f);
                }
            }

            plugin.getLogger().info("Winning item " + winningItem.getName() + " landed in slot " + winningSlot);

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error in landing sequence", e);
        }
    }

    /**
     * FIXED: Normal animation with smooth item rotation
     */
    private void updateNormalAnimation(Inventory gui, List<RandomWinItem> items, List<Integer> itemSlots) {
        try {
            // Smooth rolling effect - shift items left and add new one on right
            if (itemSlots.size() > 1) {
                // Save the leftmost item to move it to the right
                ItemStack leftmostItem = gui.getItem(itemSlots.get(0));

                // Shift all items one position to the left
                for (int i = 0; i < itemSlots.size() - 1; i++) {
                    int currentSlot = itemSlots.get(i);
                    int nextSlot = itemSlots.get(i + 1);

                    ItemStack nextItem = gui.getItem(nextSlot);
                    gui.setItem(currentSlot, nextItem);
                }

                // Add a new random item to the rightmost slot
                RandomWinItem randomItem = items.get(random.nextInt(items.size()));
                gui.setItem(itemSlots.get(itemSlots.size() - 1), createDisplayItem(randomItem));
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error in normal animation", e);
        }
    }

    /**
     * ENHANCED: Level requirement validation
     */
    private boolean validateLevelRequirement(Player player, String toolType, int toolTier) {
        if (levelsHandler == null) {
            return true; // Allow if levels handler not available
        }

        int requiredLevel = getRequiredLevelForTool(toolType, toolTier);
        return levelsHandler.canPerformAction(player, "use random enchantments with this tool", requiredLevel);
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
        return 1;
    }

    /**
     * Create the random win GUI
     */
    private Inventory createRandomWinGui(Player player, List<RandomWinItem> items, RandomWinItem winningItem) {
        String title = ChatColor.translateAlternateColorCodes('&',
                randomWinConfig.getString("gui.title", "&0Opening Random Enchantment"));
        int size = randomWinConfig.getInt("gui.size", 27);

        Inventory gui = Bukkit.createInventory(null, size, title);

        // Add filler items first
        addFillerItems(gui);

        // Add initial items to animation slots
        populateInitialItems(gui, items);

        return gui;
    }

    /**
     * FIXED: Give winning item with proper tool validation and success feedback
     */
    private void giveWinningItem(Player player, ItemStack tool, RandomWinItem winningItem) {
        try {
            // Validate tool hasn't changed
            ItemStack currentTool = getCurrentHeldTool(player);
            if (currentTool == null || !isSameTool(tool, currentTool)) {
                player.sendMessage("§cTool has changed! Enchantment cancelled for security.");
                return;
            }

            // Apply enchantment
            plugin.getEnchantmentManager().applyEnchantment(currentTool,
                    winningItem.getEnchantment(), winningItem.getLevel());

            // Enhanced success feedback
            player.sendMessage("§a§l✓ CONGRATULATIONS!");
            player.sendMessage("§e⭐ You won: " + winningItem.getName());


            // Success sounds with slight delay for better effect
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.8f);
                }
            }, 10L);

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error giving winning item to " + player.getName(), e);
            player.sendMessage("§cError applying enchantment! Please contact an administrator.");
        }
    }

    /**
     * Give random item when player escapes
     */
    private void giveRandomItem(Player player, ItemStack tool, List<RandomWinItem> availableItems) {
        if (availableItems.isEmpty()) return;

        try {
            RandomWinItem randomItem = availableItems.get(random.nextInt(availableItems.size()));
            giveWinningItem(player, tool, randomItem);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error giving random item", e);
        }
    }

    /**
     * FIXED: Get available items with proper tool tier validation
     */
    private List<RandomWinItem> getAvailableItems(String toolType, int toolTier) {
        List<RandomWinItem> availableItems = new ArrayList<>();

        ConfigurationSection itemsSection = randomWinConfig.getConfigurationSection("items");
        if (itemsSection == null) return availableItems;

        for (String itemKey : itemsSection.getKeys(false)) {
            ConfigurationSection itemConfig = itemsSection.getConfigurationSection(itemKey);
            if (itemConfig == null) continue;

            try {
                RandomWinItem item = new RandomWinItem(
                        itemConfig.getString("name", "Unknown"),
                        Material.valueOf(itemConfig.getString("material", "BOOK")),
                        itemConfig.getStringList("lore"),
                        itemConfig.getInt("chance", 1),
                        itemConfig.getStringList("can_apply"),
                        itemConfig.getString("enchantment", ""),
                        itemConfig.getInt("level", 1)
                );

                // Check if item can be applied to current tool
                if (item.canApplyTo(toolType, toolTier)) {
                    availableItems.add(item);
                }

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error loading random win item: " + itemKey, e);
            }
        }

        return availableItems;
    }

    /**
     * FIXED: Select winning item with proper weighted chance calculation
     */
    private RandomWinItem selectWinningItem(List<RandomWinItem> items) {
        if (items.isEmpty()) return null;

        int totalWeight = items.stream().mapToInt(RandomWinItem::getChance).sum();
        if (totalWeight <= 0) return items.get(0); // Fallback

        int randomValue = ThreadLocalRandom.current().nextInt(totalWeight);
        int currentWeight = 0;

        for (RandomWinItem item : items) {
            currentWeight += item.getChance();
            if (randomValue < currentWeight) {
                return item;
            }
        }

        return items.get(items.size() - 1); // Fallback to last item
    }

    /**
     * Create display item for GUI
     */
    private ItemStack createDisplayItem(RandomWinItem winItem) {
        ItemStack item = new ItemStack(winItem.getMaterial());
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', winItem.getName()));

            List<String> lore = new ArrayList<>();
            for (String line : winItem.getLore()) {
                lore.add(ChatColor.translateAlternateColorCodes('&', line));
            }
            meta.setLore(lore);

            item.setItemMeta(meta);
        }

        return item;
    }

    /**
     * Create dimmed item for non-winning slots
     */
    private ItemStack createDimmedItem() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§8...");
            List<String> lore = new ArrayList<>();
            lore.add("§7§o(Not selected)");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Add filler items to GUI
     */
    private void addFillerItems(Inventory gui) {
        if (!randomWinConfig.getBoolean("gui.filler.enabled", true)) return;

        try {
            List<Integer> fillerSlots = randomWinConfig.getIntegerList("gui.filler.slots");

            // If no specific slots defined, fill all slots initially
            if (fillerSlots.isEmpty()) {
                for (int i = 0; i < gui.getSize(); i++) {
                    fillerSlots.add(i);
                }
            }

            Material fillerMaterial = Material.valueOf(
                    randomWinConfig.getString("gui.filler.material", "BLACK_STAINED_GLASS_PANE"));
            String fillerName = randomWinConfig.getString("gui.filler.name", " ");

            ItemStack filler = new ItemStack(fillerMaterial);
            ItemMeta meta = filler.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(fillerName);
                filler.setItemMeta(meta);
            }

            for (int slot : fillerSlots) {
                if (slot >= 0 && slot < gui.getSize()) {
                    gui.setItem(slot, filler);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error adding filler items", e);
        }
    }

    /**
     * Populate initial items in animation slots
     */
    private void populateInitialItems(Inventory gui, List<RandomWinItem> items) {
        List<Integer> itemSlots = randomWinConfig.getIntegerList("gui.items_slots");
        if (itemSlots.isEmpty() || items.isEmpty()) return;

        try {
            for (int i = 0; i < itemSlots.size(); i++) {
                int slot = itemSlots.get(i);
                if (slot >= 0 && slot < gui.getSize()) {
                    RandomWinItem item = items.get(i % items.size());
                    gui.setItem(slot, createDisplayItem(item));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error populating initial items", e);
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
     * Check if two tools are the same
     */
    private boolean isSameTool(ItemStack tool1, ItemStack tool2) {
        if (tool1 == null || tool2 == null) return false;

        String id1 = plugin.getToolManager().getToolId(tool1);
        String id2 = plugin.getToolManager().getToolId(tool2);

        return id1 != null && id1.equals(id2);
    }

    /**
     * Check if player is blocked from inventory interactions
     */
    public boolean isPlayerBlocked(Player player) {
        return blockedPlayers.containsKey(player.getUniqueId());
    }

    /**
     * Check if GUI title matches random win GUI
     */
    public boolean isRandomWinGui(String title) {
        if (title == null || randomWinConfig == null) return false;
        String configTitle = ChatColor.translateAlternateColorCodes('&',
                randomWinConfig.getString("gui.title", "&0Opening Random Enchantment"));
        return ChatColor.stripColor(title).equalsIgnoreCase(ChatColor.stripColor(configTitle));
    }

    /**
     * Utility methods
     */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private String getRomanNumeral(int number) {
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
            default:
                return String.valueOf(number);
        }
    }

    /**
     * FIXED: Complete shutdown with proper cleanup
     */
    public void shutdown() {
        isShuttingDown.set(true);

        // Cancel all active animations
        for (Map.Entry<UUID, AnimationSession> entry : activeAnimations.entrySet()) {
            AnimationSession session = entry.getValue();
            if (session != null && session.getAnimationTask() != null) {
                try {
                    session.getAnimationTask().cancel();
                } catch (Exception e) {
                    plugin.getLogger().fine("Error cancelling animation task: " + e.getMessage());
                }
            }
        }

        activeAnimations.clear();
        blockedPlayers.clear();

        plugin.getLogger().info("RandomWinGui shutdown complete");
    }

    /**
     * ENHANCED: Animation session class with better tracking
     */
    private static class AnimationSession {
        private final Player player;
        private final Inventory gui;
        private final List<RandomWinItem> items;
        private final RandomWinItem winningItem;
        private final ItemStack tool;
        private final List<Integer> itemSlots;
        private BukkitTask animationTask;

        public AnimationSession(Player player, Inventory gui, List<RandomWinItem> items,
                                RandomWinItem winningItem, ItemStack tool, List<Integer> itemSlots) {
            this.player = player;
            this.gui = gui;
            this.items = items;
            this.winningItem = winningItem;
            this.tool = tool;
            this.itemSlots = itemSlots;
        }

        // Getters and setters
        public Player getPlayer() {
            return player;
        }

        public Inventory getGui() {
            return gui;
        }

        public List<RandomWinItem> getItems() {
            return items;
        }

        public RandomWinItem getWinningItem() {
            return winningItem;
        }

        public ItemStack getTool() {
            return tool;
        }

        public List<Integer> getItemSlots() {
            return itemSlots;
        }

        public BukkitTask getAnimationTask() {
            return animationTask;
        }

        public void setAnimationTask(BukkitTask animationTask) {
            this.animationTask = animationTask;
        }
    }

    public void reloadConfig() {
        loadConfiguration();
    }

    /**
     * Inner class for random win items
     */
    private static class RandomWinItem {
        private final String name;
        private final Material material;
        private final List<String> lore;
        private final int chance;
        private final List<String> canApply;
        private final String enchantment;
        private final int level;

        public RandomWinItem(String name, Material material, List<String> lore, int chance,
                             List<String> canApply, String enchantment, int level) {
            this.name = name;
            this.material = material;
            this.lore = lore;
            this.chance = chance;
            this.canApply = canApply;
            this.enchantment = enchantment;
            this.level = level;
        }

        /**
         * FIXED: Enhanced tool compatibility checking
         */
        public boolean canApplyTo(String toolType, int toolTier) {
            for (String rule : canApply) {
                if (rule.contains(":")) {
                    String[] parts = rule.split(":");
                    if (parts.length == 2) {
                        String type = parts[0];
                        String tierRange = parts[1];

                        if (type.equals(toolType)) {
                            try {
                                if (tierRange.contains("-")) {
                                    String[] range = tierRange.split("-");
                                    int minTier = Integer.parseInt(range[0]);
                                    int maxTier = Integer.parseInt(range[1]);
                                    return toolTier >= minTier && toolTier <= maxTier;
                                } else {
                                    int requiredTier = Integer.parseInt(tierRange);
                                    return toolTier == requiredTier;
                                }
                            } catch (NumberFormatException e) {
                                // Invalid format, skip this rule
                                continue;
                            }
                        }
                    }
                }
            }
            return false;
        }

        // Getters
        public String getName() {
            return name;
        }

        public Material getMaterial() {
            return material;
        }

        public List<String> getLore() {
            return lore;
        }

        public int getChance() {
            return chance;
        }

        public String getEnchantment() {
            return enchantment;
        }

        public int getLevel() {
            return level;
        }
    }
}