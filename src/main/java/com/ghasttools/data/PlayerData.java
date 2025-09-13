package com.ghasttools.data;

import com.ghasttools.milestones.MilestoneData;
import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe player data class with milestone tracking support
 */
public class PlayerData {

    private final UUID playerId;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // Basic statistics
    private volatile long totalBlocksBroken;
    private volatile double totalXpEarned;
    private volatile double totalEssenceEarned;
    private volatile String lastEnchantUsed;
    private volatile int totalMeteorsSpawned;
    private volatile int totalAirstrikes;
    private volatile String favoriteToolType;
    private volatile long lastSeen;

    // Thread-safe collections for concurrent access
    private final ConcurrentHashMap<String, Long> enchantmentCooldowns;
    private final ConcurrentHashMap<String, Integer> toolUsageCount;
    private final ConcurrentHashMap<String, Long> enchantmentUsageCount;

    // Milestone data support
    private volatile MilestoneData milestoneData;

    public PlayerData(UUID playerId) {
        this.playerId = playerId;
        this.totalBlocksBroken = 0;
        this.totalXpEarned = 0.0;
        this.totalEssenceEarned = 0.0;
        this.lastEnchantUsed = "";
        this.totalMeteorsSpawned = 0;
        this.totalAirstrikes = 0;
        this.favoriteToolType = "";
        this.enchantmentCooldowns = new ConcurrentHashMap<>();
        this.toolUsageCount = new ConcurrentHashMap<>();
        this.enchantmentUsageCount = new ConcurrentHashMap<>();
        this.lastSeen = System.currentTimeMillis();

        // Initialize milestone data
        this.milestoneData = new MilestoneData();
    }

    // Thread-safe getters and setters with proper validation
    public UUID getPlayerId() {
        return playerId;
    }

    public long getTotalBlocksBroken() {
        return totalBlocksBroken;
    }

    public void setTotalBlocksBroken(long totalBlocksBroken) {
        this.totalBlocksBroken = Math.max(0, totalBlocksBroken);
        updateLastSeen();
    }

    public void addBlocksBroken(long blocks) {
        if (blocks > 0) {
            lock.writeLock().lock();
            try {
                this.totalBlocksBroken += blocks;
                updateLastSeen();
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    public double getTotalXpEarned() {
        return totalXpEarned;
    }

    public void setTotalXpEarned(double totalXpEarned) {
        this.totalXpEarned = Math.max(0.0, totalXpEarned);
        updateLastSeen();
    }

    public void addXpEarned(double xp) {
        if (xp > 0) {
            lock.writeLock().lock();
            try {
                this.totalXpEarned += xp;
                updateLastSeen();
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    public double getTotalEssenceEarned() {
        return totalEssenceEarned;
    }

    public void setTotalEssenceEarned(double totalEssenceEarned) {
        this.totalEssenceEarned = Math.max(0.0, totalEssenceEarned);
        updateLastSeen();
    }

    public void addEssenceEarned(double essence) {
        if (essence > 0) {
            lock.writeLock().lock();
            try {
                this.totalEssenceEarned += essence;
                updateLastSeen();
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    public String getLastEnchantUsed() {
        return lastEnchantUsed != null ? lastEnchantUsed : "";
    }

    public void setLastEnchantUsed(String lastEnchantUsed) {
        this.lastEnchantUsed = lastEnchantUsed != null ? lastEnchantUsed : "";
        updateLastSeen();
    }

    public int getTotalMeteorsSpawned() {
        return totalMeteorsSpawned;
    }

    public void setTotalMeteorsSpawned(int totalMeteorsSpawned) {
        this.totalMeteorsSpawned = Math.max(0, totalMeteorsSpawned);
        updateLastSeen();
    }

    public void addMeteorSpawned() {
        lock.writeLock().lock();
        try {
            this.totalMeteorsSpawned++;
            updateLastSeen();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int getTotalAirstrikes() {
        return totalAirstrikes;
    }

    public void setTotalAirstrikes(int totalAirstrikes) {
        this.totalAirstrikes = Math.max(0, totalAirstrikes);
        updateLastSeen();
    }

    public void addAirstrike() {
        lock.writeLock().lock();
        try {
            this.totalAirstrikes++;
            updateLastSeen();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String getFavoriteToolType() {
        return favoriteToolType != null ? favoriteToolType : "";
    }

    public void setFavoriteToolType(String favoriteToolType) {
        this.favoriteToolType = favoriteToolType != null ? favoriteToolType : "";
        updateLastSeen();
    }

    // Thread-safe cooldown management with automatic cleanup
    public Map<String, Long> getEnchantmentCooldowns() {
        // Clean up expired cooldowns before returning
        cleanupExpiredCooldowns();
        // Return a copy to prevent external modification
        return new HashMap<>(enchantmentCooldowns);
    }

    public boolean isOnCooldown(String enchantment) {
        if (enchantment == null) return false;

        Long cooldownEnd = enchantmentCooldowns.get(enchantment);
        if (cooldownEnd == null) return false;

        boolean onCooldown = System.currentTimeMillis() < cooldownEnd;

        // Clean up expired cooldown immediately
        if (!onCooldown) {
            enchantmentCooldowns.remove(enchantment);
        }

        return onCooldown;
    }

    public void setCooldown(String enchantment, long durationMs) {
        if (enchantment != null && durationMs > 0) {
            enchantmentCooldowns.put(enchantment, System.currentTimeMillis() + durationMs);
            updateLastSeen();
        }
    }

    public long getRemainingCooldown(String enchantment) {
        if (enchantment == null) return 0;

        Long cooldownEnd = enchantmentCooldowns.get(enchantment);
        if (cooldownEnd == null) return 0;

        long remaining = Math.max(0, cooldownEnd - System.currentTimeMillis());

        // Clean up expired cooldown
        if (remaining == 0) {
            enchantmentCooldowns.remove(enchantment);
        }

        return remaining;
    }

    // Thread-safe tool usage tracking
    public Map<String, Integer> getToolUsageCount() {
        // Return a copy to prevent external modification
        return new HashMap<>(toolUsageCount);
    }

    public void incrementToolUsage(String toolType) {
        if (toolType != null && !toolType.trim().isEmpty()) {
            toolUsageCount.merge(toolType, 1, Integer::sum);
            updateFavoriteToolType();
            updateLastSeen();
        }
    }

    // Thread-safe enchantment usage tracking
    public Map<String, Long> getEnchantmentUsageCount() {
        // Return a copy to prevent external modification
        return new HashMap<>(enchantmentUsageCount);
    }

    public void incrementEnchantmentUsage(String enchantment) {
        if (enchantment != null && !enchantment.trim().isEmpty()) {
            enchantmentUsageCount.merge(enchantment, 1L, Long::sum);
            updateLastSeen();
        }
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(long lastSeen) {
        this.lastSeen = Math.max(0, lastSeen);
    }

    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
    }

    // Milestone data methods
    public MilestoneData getMilestoneData() {
        return milestoneData;
    }

    public void setMilestoneData(MilestoneData milestoneData) {
        if (milestoneData != null) {
            this.milestoneData = milestoneData;
            updateLastSeen();
        }
    }

    /**
     * Track milestone block breaking
     */
    public void addMilestoneBlocksBroken(Material material, long amount) {
        if (material != null && amount > 0 && milestoneData != null) {
            milestoneData.addBlocksBroken(material, amount);
            updateLastSeen();
        }
    }

    /**
     * Get milestone blocks broken for a material
     */
    public long getMilestoneBlocksBroken(Material material) {
        if (material != null && milestoneData != null) {
            return milestoneData.getBlocksBroken(material);
        }
        return 0;
    }

    /**
     * Check if milestone is claimed
     */
    public boolean isMilestoneClaimed(Material material, int milestoneNumber) {
        if (material != null && milestoneData != null) {
            return milestoneData.isMilestoneClaimed(material, milestoneNumber);
        }
        return false;
    }

    /**
     * Claim milestone
     */
    public void claimMilestone(Material material, int milestoneNumber) {
        if (material != null && milestoneData != null) {
            milestoneData.claimMilestone(material, milestoneNumber);
            updateLastSeen();
        }
    }

    /**
     * Thread-safe favorite tool type calculation
     */
    private void updateFavoriteToolType() {
        lock.writeLock().lock();
        try {
            String mostUsed = "";
            int maxCount = 0;

            for (Map.Entry<String, Integer> entry : toolUsageCount.entrySet()) {
                if (entry.getValue() > maxCount) {
                    maxCount = entry.getValue();
                    mostUsed = entry.getKey();
                }
            }

            this.favoriteToolType = mostUsed;

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Cleanup expired cooldowns to prevent memory leaks
     */
    public void cleanupExpiredCooldowns() {
        long currentTime = System.currentTimeMillis();
        enchantmentCooldowns.entrySet().removeIf(entry -> entry.getValue() < currentTime);
    }

    /**
     * Get total usage count across all tools
     */
    public int getTotalToolUsage() {
        return toolUsageCount.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Get total enchantment usage count
     */
    public long getTotalEnchantmentUsage() {
        return enchantmentUsageCount.values().stream().mapToLong(Long::longValue).sum();
    }

    /**
     * Check if player data is valid
     */
    public boolean isValid() {
        return playerId != null && lastSeen > 0;
    }

    /**
     * Clean up all collections to prevent memory leaks
     */
    public void cleanup() {
        cleanupExpiredCooldowns();

        // Remove any empty or invalid entries
        toolUsageCount.entrySet().removeIf(entry ->
                entry.getKey() == null || entry.getKey().trim().isEmpty() || entry.getValue() <= 0);

        enchantmentUsageCount.entrySet().removeIf(entry ->
                entry.getKey() == null || entry.getKey().trim().isEmpty() || entry.getValue() <= 0);
    }

    @Override
    public String toString() {
        return "PlayerData{" +
                "playerId=" + playerId +
                ", totalBlocksBroken=" + totalBlocksBroken +
                ", totalXpEarned=" + totalXpEarned +
                ", totalEssenceEarned=" + totalEssenceEarned +
                ", lastEnchantUsed='" + lastEnchantUsed + '\'' +
                ", totalMeteorsSpawned=" + totalMeteorsSpawned +
                ", totalAirstrikes=" + totalAirstrikes +
                ", favoriteToolType='" + favoriteToolType + '\'' +
                ", lastSeen=" + lastSeen +
                ", hasMilestoneData=" + (milestoneData != null) +
                '}';
    }
}