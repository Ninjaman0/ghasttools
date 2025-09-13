package com.ghasttools.data;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Represents a locked tool with anti-exploit mechanisms
 */
public class LockedTool {

    private final UUID playerId;
    private final String toolSignature;
    private final String toolType;
    private final int toolTier;
    private final String toolId;
    private final long lockTime;
    private final long expirationTime;
    private final int slot; // Slot where tool was when locked

    // Lock duration in ticks (10 ticks = 0.5 seconds)
    private static final long LOCK_DURATION_MS = 500; // 0.5 seconds

    public LockedTool(UUID playerId, ItemStack tool, String toolType, int toolTier,
                      String toolId, int slot) {
        this.playerId = playerId;
        this.toolType = toolType;
        this.toolTier = toolTier;
        this.toolId = toolId;
        this.slot = slot;
        this.lockTime = System.currentTimeMillis();
        this.expirationTime = lockTime + LOCK_DURATION_MS;

        // Create tool signature for validation
        this.toolSignature = generateToolSignature(tool, toolType, toolTier, toolId);
    }

    /**
     * Generate a unique signature for the tool to prevent exploits
     */
    private String generateToolSignature(ItemStack tool, String toolType, int toolTier, String toolId) {
        StringBuilder signature = new StringBuilder();

        signature.append(toolType).append(":");
        signature.append(toolTier).append(":");
        signature.append(toolId).append(":");
        signature.append(tool.getType().name()).append(":");

        if (tool.hasItemMeta() && tool.getItemMeta().hasDisplayName()) {
            signature.append(tool.getItemMeta().getDisplayName()).append(":");
        }

        if (tool.hasItemMeta() && tool.getItemMeta().hasCustomModelData()) {
            signature.append(tool.getItemMeta().getCustomModelData()).append(":");
        }

        signature.append(tool.getAmount());

        return signature.toString();
    }

    /**
     * Validate that a tool matches this locked tool
     */
    public boolean validateTool(ItemStack currentTool, String currentToolType,
                                int currentToolTier, String currentToolId) {
        if (currentTool == null) return false;

        // Check basic properties
        if (!toolType.equals(currentToolType) ||
                toolTier != currentToolTier ||
                !toolId.equals(currentToolId)) {
            return false;
        }

        // Generate signature for current tool and compare
        String currentSignature = generateToolSignature(currentTool, currentToolType,
                currentToolTier, currentToolId);
        return toolSignature.equals(currentSignature);
    }

    /**
     * Check if the lock has expired
     */
    public boolean isExpired() {
        return System.currentTimeMillis() > expirationTime;
    }

    /**
     * Check if enough time has passed since lock creation
     */
    public boolean isLockActive() {
        return System.currentTimeMillis() < expirationTime;
    }

    /**
     * Get remaining lock time in milliseconds
     */
    public long getRemainingLockTime() {
        return Math.max(0, expirationTime - System.currentTimeMillis());
    }

    // Getters
    public UUID getPlayerId() { return playerId; }
    public String getToolSignature() { return toolSignature; }
    public String getToolType() { return toolType; }
    public int getToolTier() { return toolTier; }
    public String getToolId() { return toolId; }
    public long getLockTime() { return lockTime; }
    public long getExpirationTime() { return expirationTime; }
    public int getSlot() { return slot; }

    @Override
    public String toString() {
        return "LockedTool{" +
                "playerId=" + playerId +
                ", toolType='" + toolType + '\'' +
                ", toolTier=" + toolTier +
                ", toolId='" + toolId + '\'' +
                ", slot=" + slot +
                ", lockTime=" + lockTime +
                ", isExpired=" + isExpired() +
                '}';
    }
}