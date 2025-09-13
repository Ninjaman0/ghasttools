package com.ghasttools.data.storage;

import com.ghasttools.data.PlayerData;

import java.util.UUID;

/**
 * Interface for data storage providers
 */
public interface StorageProvider {

    /**
     * Initialize the storage system
     */
    void initialize() throws Exception;

    /**
     * Save player data
     */
    void savePlayerData(UUID playerId, PlayerData data) throws Exception;

    /**
     * Load player data
     */
    PlayerData loadPlayerData(UUID playerId) throws Exception;

    /**
     * Clean up old player data
     * @param daysOffline Number of days a player must be offline before their data is cleaned
     * @return Number of records cleaned up
     */
    int cleanupOldData(int daysOffline) throws Exception;

    /**
     * Export all data to a file
     */
    boolean exportData(String fileName) throws Exception;

    /**
     * Import data from a file
     */
    boolean importData(String fileName) throws Exception;

    /**
     * Shutdown the storage system
     */
    void shutdown() throws Exception;
}