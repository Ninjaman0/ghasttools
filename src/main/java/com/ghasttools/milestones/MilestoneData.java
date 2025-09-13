package com.ghasttools.milestones;

import org.bukkit.Material;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents milestone progress data for a player
 */
public class MilestoneData {

    private final ConcurrentHashMap<Material, Long> blocksBroken;
    private final ConcurrentHashMap<String, Boolean> claimedMilestones;

    public MilestoneData() {
        this.blocksBroken = new ConcurrentHashMap<>();
        this.claimedMilestones = new ConcurrentHashMap<>();
    }

    /**
     * Add blocks broken for a specific material
     */
    public void addBlocksBroken(Material material, long amount) {
        if (material != null && amount > 0) {
            blocksBroken.merge(material, amount, Long::sum);
        }
    }

    /**
     * Get total blocks broken for a material
     */
    public long getBlocksBroken(Material material) {
        return blocksBroken.getOrDefault(material, 0L);
    }

    /**
     * Get all blocks broken data
     */
    public Map<Material, Long> getAllBlocksBroken() {
        return new ConcurrentHashMap<>(blocksBroken);
    }

    /**
     * Check if a milestone has been claimed
     */
    public boolean isMilestoneClaimed(Material material, int milestoneNumber) {
        String key = material.name() + "_" + milestoneNumber;
        return claimedMilestones.getOrDefault(key, false);
    }

    /**
     * Mark a milestone as claimed
     */
    public void claimMilestone(Material material, int milestoneNumber) {
        String key = material.name() + "_" + milestoneNumber;
        claimedMilestones.put(key, true);
    }

    /**
     * Get all claimed milestones
     */
    public Map<String, Boolean> getAllClaimedMilestones() {
        return new ConcurrentHashMap<>(claimedMilestones);
    }

    /**
     * Set blocks broken for a material (for loading from storage)
     */
    public void setBlocksBroken(Material material, long amount) {
        if (material != null && amount >= 0) {
            blocksBroken.put(material, amount);
        }
    }

    /**
     * Set milestone as claimed (for loading from storage)
     */
    public void setMilestoneClaimed(String milestoneKey, boolean claimed) {
        if (milestoneKey != null) {
            claimedMilestones.put(milestoneKey, claimed);
        }
    }

    /**
     * Reset all milestone data
     */
    public void reset() {
        blocksBroken.clear();
        claimedMilestones.clear();
    }

    /**
     * Reset blocks broken for a specific material
     */
    public void resetBlocksBroken(Material material) {
        if (material != null) {
            blocksBroken.remove(material);
        }
    }

    /**
     * Check if player has reached a milestone amount for a material
     */
    public boolean hasReachedMilestone(Material material, long requiredAmount) {
        return getBlocksBroken(material) >= requiredAmount;
    }
}