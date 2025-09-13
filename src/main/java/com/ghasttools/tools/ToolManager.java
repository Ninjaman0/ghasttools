package com.ghasttools.tools;

import com.ghasttools.GhastToolsPlugin;
import com.ghasttools.enchantments.EnchantmentManager;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * FIXED: Tool manager with ALL enchantments compatible with ALL tool types
 */
public class ToolManager {

    private final GhastToolsPlugin plugin;
    private final Map<String, ToolConfig> toolConfigs = new HashMap<>();

    // NBT Keys for tool data storage
    private final NamespacedKey TOOL_TYPE_KEY;
    private final NamespacedKey TOOL_TIER_KEY;
    private final NamespacedKey ENCHANTMENTS_KEY;
    private final NamespacedKey TOOL_ID_KEY;
    private final NamespacedKey CREATION_TIME_KEY;

    public ToolManager(GhastToolsPlugin plugin) {
        this.plugin = plugin;
        this.TOOL_TYPE_KEY = new NamespacedKey(plugin, "tool_type");
        this.TOOL_TIER_KEY = new NamespacedKey(plugin, "tool_tier");
        this.ENCHANTMENTS_KEY = new NamespacedKey(plugin, "enchantments");
        this.TOOL_ID_KEY = new NamespacedKey(plugin, "tool_id");
        this.CREATION_TIME_KEY = new NamespacedKey(plugin, "creation_time");
    }

    /**
     * Load tool configurations from YAML files
     */
    public void loadTools() {
        plugin.getLogger().info("Loading tool configurations...");

        loadToolConfig("pickaxe");
        loadToolConfig("axe");
        loadToolConfig("hoe");

        plugin.getLogger().info("Loaded " + toolConfigs.size() + " tool configurations");
    }

    private void loadToolConfig(String toolType) {
        FileConfiguration config = null;
        EnchantmentManager enchantmentManager = plugin.getEnchantmentManager();
        if (enchantmentManager == null) {
            plugin.getLogger().severe("EnchantmentManager is null! Cannot load tool configurations.");
            return;
        }
        switch (toolType) {
            case "pickaxe":
                config = plugin.getConfigManager().getPickaxeConfig();
                break;
            case "axe":
                config = plugin.getConfigManager().getAxeConfig();
                break;
            case "hoe":
                config = plugin.getConfigManager().getHoeConfig();
                break;
        }

        if (config == null) {
            plugin.getLogger().warning("No configuration found for tool type: " + toolType);
            return;
        }

        ToolConfig toolConfig = new ToolConfig();

        // Load tiers with level requirements
        ConfigurationSection tiersSection = config.getConfigurationSection("tools." + toolType + ".tiers");
        if (tiersSection != null) {
            for (String tierStr : tiersSection.getKeys(false)) {
                int tier = Integer.parseInt(tierStr);
                String materialName = tiersSection.getString(tierStr + ".material");
                int customModelData = tiersSection.getInt(tierStr + ".custom_model_data", 0);
                double durabilityMultiplier = tiersSection.getDouble(tierStr + ".durability_multiplier", 1.0);
                int levelRequirement = tiersSection.getInt(tierStr + ".level_requirement", 1);

                Material material = Material.valueOf(materialName.toUpperCase());
                toolConfig.addTier(tier, material, customModelData, durabilityMultiplier, levelRequirement);
            }
        }

        // FIXED: ALL enchantments are now compatible with ALL tools
        toolConfig.setCompatibleEnchantments(plugin.getEnchantmentManager().getAllEnchantmentNames());

        toolConfigs.put(toolType, toolConfig);
    }

    /**
     * ENHANCED: Check if player can use specific tool tier based on level requirement
     */
    public boolean canUseToolTier(Player player, String toolType, int tier) {
        if (player.hasPermission("ghasttools.bypass.levelcheck")) {
            return true;
        }

        ToolConfig config = toolConfigs.get(toolType);
        if (config == null) return false;

        ToolTier toolTier = config.getTier(tier);
        if (toolTier == null) return false;

        // Check level requirement through levelshandler
        if (plugin.getLevelsHandler() != null) {
            return plugin.getLevelsHandler().meetsLevelRequirement(player, toolTier.getLevelRequirement());
        }

        return true; // Allow if levels handler not available
    }

    /**
     * ENHANCED: Validate tool usage with level requirements
     */
    public boolean validateToolUsage(Player player, ItemStack tool) {
        if (!isGhastTool(tool)) return false;

        String toolType = getToolType(tool);
        int toolTier = getToolTier(tool);

        if (toolType == null || toolTier <= 0) return false;

        // Check tool usage permission
        if (!canUseToolType(player, toolType)) {
            plugin.getMessageUtil().sendMessage(player, "no_permission_tool",
                    Map.of("tool", toolType));
            return false;
        }

        // Check level requirement
        if (!canUseToolTier(player, toolType, toolTier)) {
            ToolConfig config = toolConfigs.get(toolType);
            if (config != null) {
                ToolTier tierConfig = config.getTier(toolTier);
                if (tierConfig != null) {
                    int requiredLevel = tierConfig.getLevelRequirement();
                    int playerLevel = plugin.getLevelsHandler() != null ?
                            plugin.getLevelsHandler().getPlayerLevel(player) : 0;

                    player.sendMessage("§cYou need level " + requiredLevel + " to use this tool! " +
                            "Your level: " + playerLevel);
                    return false;
                }
            }
            return false;
        }

        return true;
    }

    /**
     * Create a tool with specified tier and NBT data
     */
    public ItemStack createTool(String toolType, int tier) {
        ToolConfig config = toolConfigs.get(toolType);
        if (config == null) {
            plugin.getLogger().warning("Unknown tool type: " + toolType);
            return null;
        }

        ToolTier toolTier = config.getTier(tier);
        if (toolTier == null) {
            plugin.getLogger().warning("Unknown tier " + tier + " for tool " + toolType);
            return null;
        }

        ItemStack item = new ItemStack(toolTier.getMaterial());
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            // Set custom model data
            if (toolTier.getCustomModelData() > 0) {
                meta.setCustomModelData(toolTier.getCustomModelData());
            }

            // Make tool unbreakable
            meta.setUnbreakable(true);

            // Set display name from config
            String displayName = getToolDisplayName(toolType, tier);
            meta.setDisplayName(displayName);

            // Set lore from config
            List<String> lore = getToolLore(toolType, tier);
            meta.setLore(lore);

            // Set NBT data with unique tool ID
            PersistentDataContainer container = meta.getPersistentDataContainer();
            container.set(TOOL_TYPE_KEY, PersistentDataType.STRING, toolType);
            container.set(TOOL_TIER_KEY, PersistentDataType.INTEGER, tier);
            container.set(ENCHANTMENTS_KEY, PersistentDataType.STRING, "");
            container.set(TOOL_ID_KEY, PersistentDataType.STRING, generateToolId());
            container.set(CREATION_TIME_KEY, PersistentDataType.LONG, System.currentTimeMillis());

            item.setItemMeta(meta);
        }

        return item;
    }

    /**
     * Create max tool with all enchantments for admin testing
     */
    public ItemStack createMaxTool(String toolType) {
        ToolConfig config = toolConfigs.get(toolType);
        if (config == null) {
            return null;
        }

        // Get highest tier
        int maxTier = config.getTiers().keySet().stream().mapToInt(Integer::intValue).max().orElse(6);

        ItemStack tool = createTool(toolType, maxTier);
        if (tool == null) {
            return null;
        }

        // FIXED: Apply ALL enchantments at max level since they're all compatible now
        for (String enchantmentName : plugin.getEnchantmentManager().getAllEnchantmentNames()) {
            var enchantConfig = plugin.getEnchantmentManager().getEnchantmentConfig(enchantmentName);
            if (enchantConfig != null && enchantConfig.isEnabled()) {
                plugin.getEnchantmentManager().applyEnchantment(tool, enchantmentName, enchantConfig.getMaxLevel());
            }
        }

        return tool;
    }

    /**
     * Get tool display name from config
     */
    private String getToolDisplayName(String toolType, int tier) {
        FileConfiguration config = plugin.getConfigManager().getMainConfig();
        String configPath = "tool_names." + toolType + ".tier_" + tier;
        String defaultName = "§6GhastTools " + capitalize(toolType) + " §7(Tier " + tier + ")";

        return config.getString(configPath, defaultName);
    }

    /**
     * Get tool lore from config
     */
    private List<String> getToolLore(String toolType, int tier) {
        FileConfiguration config = plugin.getConfigManager().getMainConfig();
        String configPath = "tool_lore." + toolType + ".tier_" + tier;

        List<String> defaultLore = new ArrayList<>();


        return config.getStringList(configPath).isEmpty() ? defaultLore : config.getStringList(configPath);
    }

    /**
     * Generate unique tool ID
     */
    private String generateToolId() {
        return "GT-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 10000);
    }

    /**
     * Upgrade a tool's tier
     */
    public void upgradeTool(Player player, ItemStack tool, int newTier) {
        if (!isGhastTool(tool)) {
            return;
        }

        String toolType = getToolType(tool);
        ItemStack newTool = createTool(toolType, newTier);

        if (newTool != null) {
            // Copy enchantments from old tool
            Map<String, Integer> enchantments = getToolEnchantments(tool);
            for (Map.Entry<String, Integer> entry : enchantments.entrySet()) {
                plugin.getEnchantmentManager().applyEnchantment(newTool, entry.getKey(), entry.getValue());
            }

            // Replace the tool in player's hand
            if (player.getInventory().getItemInMainHand().equals(tool)) {
                player.getInventory().setItemInMainHand(newTool);
            } else if (player.getInventory().getItemInOffHand().equals(tool)) {
                player.getInventory().setItemInOffHand(newTool);
            }

            plugin.getMessageUtil().sendMessage(player, "tool_upgraded",
                    Map.of("tier", String.valueOf(newTier)));
        }
    }

    /**
     * Check if an item is a GhastTool
     */
    public boolean isGhastTool(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }

        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();
        return container.has(TOOL_TYPE_KEY, PersistentDataType.STRING);
    }

    /**
     * Get tool type from item
     */
    public String getToolType(ItemStack item) {
        if (!isGhastTool(item)) {
            return null;
        }

        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();
        return container.get(TOOL_TYPE_KEY, PersistentDataType.STRING);
    }

    /**
     * Get tool tier from item
     */
    public int getToolTier(ItemStack item) {
        if (!isGhastTool(item)) {
            return 0;
        }

        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();
        Integer tier = container.get(TOOL_TIER_KEY, PersistentDataType.INTEGER);
        return tier != null ? tier : 0;
    }

    /**
     * Get tool ID from item
     */
    public String getToolId(ItemStack item) {
        if (!isGhastTool(item)) {
            return null;
        }

        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();
        return container.get(TOOL_ID_KEY, PersistentDataType.STRING);
    }

    /**
     * Get enchantments from tool NBT
     */
    public Map<String, Integer> getToolEnchantments(ItemStack item) {
        Map<String, Integer> enchantments = new HashMap<>();

        if (!isGhastTool(item)) {
            return enchantments;
        }

        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();
        String enchantString = container.get(ENCHANTMENTS_KEY, PersistentDataType.STRING);

        if (enchantString != null && !enchantString.isEmpty()) {
            String[] enchantPairs = enchantString.split(";");
            for (String pair : enchantPairs) {
                String[] parts = pair.split(":");
                if (parts.length == 2) {
                    try {
                        enchantments.put(parts[0], Integer.parseInt(parts[1]));
                    } catch (NumberFormatException e) {
                        plugin.getLogger().log(Level.WARNING, "Invalid enchantment data: " + pair, e);
                    }
                }
            }
        }

        return enchantments;
    }

    /**
     * Update enchantments on tool NBT
     */
    public void updateToolEnchantments(ItemStack item, Map<String, Integer> enchantments) {
        if (!isGhastTool(item)) {
            return;
        }

        StringBuilder enchantString = new StringBuilder();
        for (Map.Entry<String, Integer> entry : enchantments.entrySet()) {
            if (enchantString.length() > 0) {
                enchantString.append(";");
            }
            enchantString.append(entry.getKey()).append(":").append(entry.getValue());
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            PersistentDataContainer container = meta.getPersistentDataContainer();
            container.set(ENCHANTMENTS_KEY, PersistentDataType.STRING, enchantString.toString());

            // Update lore to show enchantments with placeholders
            updateToolLore(meta, getToolType(item), getToolTier(item), enchantments);

            item.setItemMeta(meta);
        }
    }

    /**
     * Update tool lore to show enchantments with placeholders
     * ENHANCED: Added placeholder support for enchantment levels
     */
    private void updateToolLore(ItemMeta meta, String toolType, int tier, Map<String, Integer> enchantments) {
        List<String> lore = getToolLore(toolType, tier);

        if (!enchantments.isEmpty()) {
            lore.add("");
            lore.add("§6Enchantments:");
            for (Map.Entry<String, Integer> entry : enchantments.entrySet()) {
                var enchantConfig = plugin.getEnchantmentManager().getEnchantmentConfig(entry.getKey());
                String status = (enchantConfig != null && enchantConfig.isEnabled()) ? "§a" : "§c";
                int maxLevel = enchantConfig != null ? enchantConfig.getMaxLevel() : 6;

                // ENHANCED: Added placeholder-style display for current/max levels
                String enchantmentDisplay = String.format("§8  ➤ %s%s §7Level %d§8/§7%d",
                        status,
                        capitalize(entry.getKey()),
                        entry.getValue(),
                        maxLevel
                );

                lore.add(enchantmentDisplay);

                // Add progress bar for visual representation
                if (enchantConfig != null) {
                    lore.add(createProgressBar(entry.getValue(), maxLevel));
                }
            }
        }

        meta.setLore(lore);
    }

    /**
     * Create a progress bar for enchantment levels
     * ENHANCED: Visual representation of enchantment progress
     */
    private String createProgressBar(int current, int max) {
        int barLength = 10;
        int filled = (int) ((double) current / max * barLength);

        StringBuilder bar = new StringBuilder("§8    [");
        for (int i = 0; i < barLength; i++) {
            if (i < filled) {
                bar.append("§a■");
            } else {
                bar.append("§7■");
            }
        }
        bar.append("§8]");

        return bar.toString();
    }

    /**
     * FIXED: ALL enchantments are now compatible with ALL tools
     */
    public boolean isEnchantmentCompatible(String toolType, String enchantment) {
        return true; // ALL enchantments work on ALL tools now
    }

    /**
     * Validate tool usage permissions
     */
    public boolean canUseToolType(Player player, String toolType) {
        return player.hasPermission("ghasttools.use." + toolType);
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    public Map<String, ToolConfig> getToolConfigs() {
        return toolConfigs;
    }

    // Inner classes
    public static class ToolConfig {
        private final Map<Integer, ToolTier> tiers = new HashMap<>();
        private List<String> compatibleEnchantments = new ArrayList<>();

        public void addTier(int tier, Material material, int customModelData, double durabilityMultiplier, int levelRequirement) {
            tiers.put(tier, new ToolTier(material, customModelData, durabilityMultiplier, levelRequirement));
        }

        public ToolTier getTier(int tier) {
            return tiers.get(tier);
        }

        public Map<Integer, ToolTier> getTiers() {
            return tiers;
        }

        public List<String> getCompatibleEnchantments() {
            return compatibleEnchantments;
        }

        public void setCompatibleEnchantments(List<String> enchantments) {
            this.compatibleEnchantments = enchantments;
        }
    }

    public static class ToolTier {
        private final Material material;
        private final int customModelData;
        private final double durabilityMultiplier;
        private final int levelRequirement;

        public ToolTier(Material material, int customModelData, double durabilityMultiplier, int levelRequirement) {
            this.material = material;
            this.customModelData = customModelData;
            this.durabilityMultiplier = durabilityMultiplier;
            this.levelRequirement = levelRequirement;
        }

        public Material getMaterial() {
            return material;
        }

        public int getCustomModelData() {
            return customModelData;
        }

        public double getDurabilityMultiplier() {
            return durabilityMultiplier;
        }

        public int getLevelRequirement() {
            return levelRequirement;
        }
    }
}