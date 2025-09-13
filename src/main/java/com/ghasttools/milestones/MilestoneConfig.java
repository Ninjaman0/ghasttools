package com.ghasttools.milestones;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration class for milestone settings
 */
public class MilestoneConfig {

    private final Material material;
    private final Material guiMaterial;
    private final String guiName;
    private final List<String> guiLore;
    private final int slot; // Slot in main milestone GUI
    private final Map<Integer, MilestoneLevel> levels;

    public MilestoneConfig(Material material, Material guiMaterial, String guiName, List<String> guiLore, int slot) {
        this.material = material;
        this.guiMaterial = guiMaterial;
        this.guiName = guiName;
        this.guiLore = new ArrayList<>(guiLore);
        this.slot = slot;
        this.levels = new HashMap<>();
    }

    public Material getMaterial() {
        return material;
    }

    public Material getGuiMaterial() {
        return guiMaterial;
    }

    public String getGuiName() {
        return guiName;
    }

    public List<String> getGuiLore() {
        return new ArrayList<>(guiLore);
    }

    public int getSlot() {
        return slot;
    }

    public void addLevel(int levelNumber, MilestoneLevel level) {
        levels.put(levelNumber, level);
    }

    public MilestoneLevel getLevel(int levelNumber) {
        return levels.get(levelNumber);
    }

    public Map<Integer, MilestoneLevel> getAllLevels() {
        return new HashMap<>(levels);
    }

    public boolean hasLevel(int levelNumber) {
        return levels.containsKey(levelNumber);
    }

    public int getMaxLevel() {
        return levels.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    /**
     * Milestone level configuration
     */
    public static class MilestoneLevel {
        private final long amount;
        private final String onReachCommand;
        private final String guiCommand;
        private final String guiName;
        private final List<String> guiLore;
        private final Material guiItemReady;
        private final Material guiItemNotReady;
        private final int slot; // Slot in sub milestone GUI

        public MilestoneLevel(long amount, String onReachCommand, String guiCommand,
                              String guiName, List<String> guiLore,
                              Material guiItemReady, Material guiItemNotReady, int slot) {
            this.amount = amount;
            this.onReachCommand = onReachCommand;
            this.guiCommand = guiCommand;
            this.guiName = guiName;
            this.guiLore = new ArrayList<>(guiLore);
            this.guiItemReady = guiItemReady;
            this.guiItemNotReady = guiItemNotReady;
            this.slot = slot;
        }

        public long getAmount() {
            return amount;
        }

        public String getOnReachCommand() {
            return onReachCommand;
        }

        public String getGuiCommand() {
            return guiCommand;
        }

        public String getGuiName() {
            return guiName;
        }

        public List<String> getGuiLore() {
            return new ArrayList<>(guiLore);
        }

        public Material getGuiItemReady() {
            return guiItemReady;
        }

        public Material getGuiItemNotReady() {
            return guiItemNotReady;
        }

        public int getSlot() {
            return slot;
        }
    }
}