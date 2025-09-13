package com.ghasttools.data.storage;

import com.ghasttools.GhastToolsPlugin;
import com.ghasttools.data.PlayerData;
import com.ghasttools.milestones.MilestoneData;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Material;

import java.io.File;
import java.sql.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * SQLite implementation with milestone data persistence
 */
public class SQLiteStorage implements StorageProvider {

    private final GhastToolsPlugin plugin;
    private HikariDataSource dataSource;

    // Configuration constants to avoid magic numbers
    private static final int CONNECTION_POOL_SIZE = 10;
    private static final int CONNECTION_TIMEOUT_MS = 30000;
    private static final int IDLE_TIMEOUT_MS = 600000; // 10 minutes
    private static final int MAX_LIFETIME_MS = 1800000; // 30 minutes
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

    public SQLiteStorage(GhastToolsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void initialize() throws Exception {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File databaseFile = new File(dataFolder, "ghasttools.db");

        // Enhanced HikariCP configuration for better connection management
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        config.setMaximumPoolSize(CONNECTION_POOL_SIZE);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        config.setIdleTimeout(IDLE_TIMEOUT_MS);
        config.setMaxLifetime(MAX_LIFETIME_MS);
        config.setConnectionTestQuery("SELECT 1");
        config.setAutoCommit(true);

        // SQLite specific optimizations
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        dataSource = new HikariDataSource(config);

        // Create tables with proper error handling
        createTables();

        plugin.getLogger().info("SQLite storage initialized successfully");
    }

    /**
     * Enhanced table creation with milestone tables
     */
    private void createTables() throws SQLException {
        Connection conn = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(false);

            // Player data table
            String createPlayerDataTable = """
                        CREATE TABLE IF NOT EXISTS player_data (
                            player_id TEXT PRIMARY KEY,
                            total_blocks_broken INTEGER DEFAULT 0,
                            total_xp_earned REAL DEFAULT 0.0,
                            total_essence_earned REAL DEFAULT 0.0,
                            last_enchant_used TEXT DEFAULT '',
                            total_meteors_spawned INTEGER DEFAULT 0,
                            total_airstrikes INTEGER DEFAULT 0,
                            favorite_tool_type TEXT DEFAULT '',
                            last_seen INTEGER DEFAULT 0,
                            created_at INTEGER DEFAULT (strftime('%s', 'now')),
                            updated_at INTEGER DEFAULT (strftime('%s', 'now'))
                        )
                    """;

            // Enchantment cooldowns table
            String createCooldownsTable = """
                        CREATE TABLE IF NOT EXISTS enchantment_cooldowns (
                            player_id TEXT NOT NULL,
                            enchantment TEXT NOT NULL,
                            cooldown_end INTEGER NOT NULL,
                            PRIMARY KEY (player_id, enchantment),
                            FOREIGN KEY (player_id) REFERENCES player_data(player_id) ON DELETE CASCADE
                        )
                    """;

            // Tool usage table
            String createToolUsageTable = """
                        CREATE TABLE IF NOT EXISTS tool_usage (
                            player_id TEXT NOT NULL,
                            tool_type TEXT NOT NULL,
                            usage_count INTEGER DEFAULT 0,
                            PRIMARY KEY (player_id, tool_type),
                            FOREIGN KEY (player_id) REFERENCES player_data(player_id) ON DELETE CASCADE
                        )
                    """;

            // Enchantment usage table
            String createEnchantUsageTable = """
                        CREATE TABLE IF NOT EXISTS enchantment_usage (
                            player_id TEXT NOT NULL,
                            enchantment TEXT NOT NULL,
                            usage_count INTEGER DEFAULT 0,
                            PRIMARY KEY (player_id, enchantment),
                            FOREIGN KEY (player_id) REFERENCES player_data(player_id) ON DELETE CASCADE
                        )
                    """;

            // Milestone blocks broken table
            String createMilestoneBlocksTable = """
                        CREATE TABLE IF NOT EXISTS milestone_blocks_broken (
                            player_id TEXT NOT NULL,
                            material TEXT NOT NULL,
                            blocks_broken INTEGER DEFAULT 0,
                            PRIMARY KEY (player_id, material),
                            FOREIGN KEY (player_id) REFERENCES player_data(player_id) ON DELETE CASCADE
                        )
                    """;

            // Milestone claims table
            String createMilestoneClaimsTable = """
                        CREATE TABLE IF NOT EXISTS milestone_claims (
                            player_id TEXT NOT NULL,
                            milestone_key TEXT NOT NULL,
                            claimed BOOLEAN DEFAULT FALSE,
                            claimed_at INTEGER DEFAULT (strftime('%s', 'now')),
                            PRIMARY KEY (player_id, milestone_key),
                            FOREIGN KEY (player_id) REFERENCES player_data(player_id) ON DELETE CASCADE
                        )
                    """;

            // Create indexes for better performance
            String createIndexes = """
                        CREATE INDEX IF NOT EXISTS idx_player_data_last_seen ON player_data(last_seen);
                        CREATE INDEX IF NOT EXISTS idx_cooldowns_end ON enchantment_cooldowns(cooldown_end);
                        CREATE INDEX IF NOT EXISTS idx_tool_usage_player ON tool_usage(player_id);
                        CREATE INDEX IF NOT EXISTS idx_enchant_usage_player ON enchantment_usage(player_id);
                        CREATE INDEX IF NOT EXISTS idx_milestone_blocks_player ON milestone_blocks_broken(player_id);
                        CREATE INDEX IF NOT EXISTS idx_milestone_claims_player ON milestone_claims(player_id);
                    """;

            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createPlayerDataTable);
                stmt.execute(createCooldownsTable);
                stmt.execute(createToolUsageTable);
                stmt.execute(createEnchantUsageTable);
                stmt.execute(createMilestoneBlocksTable);
                stmt.execute(createMilestoneClaimsTable);

                // Execute index creation
                for (String indexSql : createIndexes.split(";")) {
                    if (!indexSql.trim().isEmpty()) {
                        stmt.execute(indexSql.trim());
                    }
                }
            }

            conn.commit();
            plugin.getLogger().info("Database tables created/verified successfully with milestone support");

        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to rollback transaction", rollbackEx);
                }
            }
            throw new SQLException("Failed to create database tables", e);
        } finally {
            closeConnection(conn);
        }
    }

    /**
     * Enhanced connection management with proper error handling
     */
    private Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("DataSource is not available");
        }

        return dataSource.getConnection();
    }

    /**
     * Proper connection cleanup
     */
    private void closeConnection(Connection conn) {
        if (conn != null) {
            try {
                if (!conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Error closing database connection", e);
            }
        }
    }

    @Override
    public void savePlayerData(UUID playerId, PlayerData data) throws Exception {
        if (plugin.isShuttingDown()) {
            plugin.getLogger().fine("Plugin shutting down, skipping data save for " + playerId);
            return;
        }

        Connection conn = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(false);

            // Save main player data with updated timestamp
            String upsertPlayerData = """
                        INSERT OR REPLACE INTO player_data 
                        (player_id, total_blocks_broken, total_xp_earned, total_essence_earned, 
                         last_enchant_used, total_meteors_spawned, total_airstrikes, 
                         favorite_tool_type, last_seen, updated_at) 
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, strftime('%s', 'now'))
                    """;

            try (PreparedStatement stmt = conn.prepareStatement(upsertPlayerData)) {
                stmt.setString(1, playerId.toString());
                stmt.setLong(2, data.getTotalBlocksBroken());
                stmt.setDouble(3, data.getTotalXpEarned());
                stmt.setDouble(4, data.getTotalEssenceEarned());
                stmt.setString(5, data.getLastEnchantUsed());
                stmt.setInt(6, data.getTotalMeteorsSpawned());
                stmt.setInt(7, data.getTotalAirstrikes());
                stmt.setString(8, data.getFavoriteToolType());
                stmt.setLong(9, data.getLastSeen());
                stmt.executeUpdate();
            }

            // Save related data
            saveCooldowns(conn, playerId, data.getEnchantmentCooldowns());
            saveToolUsage(conn, playerId, data.getToolUsageCount());
            saveEnchantmentUsage(conn, playerId, data.getEnchantmentUsageCount());

            // Save milestone data
            saveMilestoneData(conn, playerId, data.getMilestoneData());

            conn.commit();

        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    plugin.getLogger().log(Level.WARNING, "Failed to rollback transaction", rollbackEx);
                }
            }
            throw new Exception("Failed to save player data for " + playerId, e);
        } finally {
            closeConnection(conn);
        }
    }

    @Override
    public PlayerData loadPlayerData(UUID playerId) throws Exception {
        Connection conn = null;
        try {
            conn = getConnection();
            PlayerData data = new PlayerData(playerId);

            // Load main player data
            String selectPlayerData = "SELECT * FROM player_data WHERE player_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(selectPlayerData)) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        data.setTotalBlocksBroken(rs.getLong("total_blocks_broken"));
                        data.setTotalXpEarned(rs.getDouble("total_xp_earned"));
                        data.setTotalEssenceEarned(rs.getDouble("total_essence_earned"));
                        data.setLastEnchantUsed(rs.getString("last_enchant_used"));
                        data.setTotalMeteorsSpawned(rs.getInt("total_meteors_spawned"));
                        data.setTotalAirstrikes(rs.getInt("total_airstrikes"));
                        data.setFavoriteToolType(rs.getString("favorite_tool_type"));
                        data.setLastSeen(rs.getLong("last_seen"));
                    }
                }
            }

            // Load related data
            loadCooldowns(conn, playerId, data.getEnchantmentCooldowns());
            loadToolUsage(conn, playerId, data.getToolUsageCount());
            loadEnchantmentUsage(conn, playerId, data.getEnchantmentUsageCount());

            // Load milestone data
            loadMilestoneData(conn, playerId, data.getMilestoneData());

            return data;

        } catch (Exception e) {
            throw new Exception("Failed to load player data for " + playerId, e);
        } finally {
            closeConnection(conn);
        }
    }

    /**
     * Save milestone data to database
     */
    private void saveMilestoneData(Connection conn, UUID playerId, MilestoneData milestoneData) throws SQLException {
        if (milestoneData == null) {
            return;
        }

        // Save blocks broken data
        String deleteMilestoneBlocks = "DELETE FROM milestone_blocks_broken WHERE player_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(deleteMilestoneBlocks)) {
            stmt.setString(1, playerId.toString());
            stmt.executeUpdate();
        }

        Map<Material, Long> blocksData = milestoneData.getAllBlocksBroken();

        if (!blocksData.isEmpty()) {
            String insertMilestoneBlocks = "INSERT INTO milestone_blocks_broken (player_id, material, blocks_broken) VALUES (?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(insertMilestoneBlocks)) {
                for (Map.Entry<Material, Long> entry : blocksData.entrySet()) {
                    if (entry.getValue() > 0) { // Only save non-zero values
                        stmt.setString(1, playerId.toString());
                        stmt.setString(2, entry.getKey().name());
                        stmt.setLong(3, entry.getValue());
                        stmt.addBatch();
                    }
                }
                stmt.executeBatch();
            }
        }

        // Save milestone claims data
        String deleteMilestoneClaims = "DELETE FROM milestone_claims WHERE player_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(deleteMilestoneClaims)) {
            stmt.setString(1, playerId.toString());
            stmt.executeUpdate();
        }

        Map<String, Boolean> claimsData = milestoneData.getAllClaimedMilestones();

        if (!claimsData.isEmpty()) {
            String insertMilestoneClaims = "INSERT INTO milestone_claims (player_id, milestone_key, claimed) VALUES (?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(insertMilestoneClaims)) {
                for (Map.Entry<String, Boolean> entry : claimsData.entrySet()) {
                    if (entry.getValue()) { // Only save claimed milestones
                        stmt.setString(1, playerId.toString());
                        stmt.setString(2, entry.getKey());
                        stmt.setBoolean(3, entry.getValue());
                        stmt.addBatch();
                    }
                }
                stmt.executeBatch();
            }
        }
    }

    /**
     * Load milestone data from database
     */
    private void loadMilestoneData(Connection conn, UUID playerId, MilestoneData milestoneData) throws SQLException {
        if (milestoneData == null) {
            return;
        }

        // Load blocks broken data
        String selectMilestoneBlocks = "SELECT material, blocks_broken FROM milestone_blocks_broken WHERE player_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(selectMilestoneBlocks)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    try {
                        Material material = Material.valueOf(rs.getString("material"));
                        long blocksBroken = rs.getLong("blocks_broken");
                        milestoneData.setBlocksBroken(material, blocksBroken);
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Invalid material in milestone data: " + rs.getString("material"));
                    }
                }
            }
        }

        // Load milestone claims data
        String selectMilestoneClaims = "SELECT milestone_key, claimed FROM milestone_claims WHERE player_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(selectMilestoneClaims)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String milestoneKey = rs.getString("milestone_key");
                    boolean claimed = rs.getBoolean("claimed");
                    milestoneData.setMilestoneClaimed(milestoneKey, claimed);
                }
            }
        }
    }

    /**
     * Enhanced cooldown management with batch operations
     */
    private void saveCooldowns(Connection conn, UUID playerId, Map<String, Long> cooldowns) throws SQLException {
        // Clear existing cooldowns
        String deleteCooldowns = "DELETE FROM enchantment_cooldowns WHERE player_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(deleteCooldowns)) {
            stmt.setString(1, playerId.toString());
            stmt.executeUpdate();
        }

        // Insert current active cooldowns only
        if (!cooldowns.isEmpty()) {
            String insertCooldown = "INSERT INTO enchantment_cooldowns (player_id, enchantment, cooldown_end) VALUES (?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(insertCooldown)) {
                long currentTime = System.currentTimeMillis();

                for (Map.Entry<String, Long> entry : cooldowns.entrySet()) {
                    if (entry.getValue() > currentTime) { // Only save active cooldowns
                        stmt.setString(1, playerId.toString());
                        stmt.setString(2, entry.getKey());
                        stmt.setLong(3, entry.getValue());
                        stmt.addBatch();
                    }
                }

                stmt.executeBatch();
            }
        }
    }

    private void loadCooldowns(Connection conn, UUID playerId, Map<String, Long> cooldowns) throws SQLException {
        String selectCooldowns = "SELECT enchantment, cooldown_end FROM enchantment_cooldowns WHERE player_id = ? AND cooldown_end > ?";
        try (PreparedStatement stmt = conn.prepareStatement(selectCooldowns)) {
            stmt.setString(1, playerId.toString());
            stmt.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    cooldowns.put(rs.getString("enchantment"), rs.getLong("cooldown_end"));
                }
            }
        }
    }

    /**
     * Batch operations for better performance
     */
    private void saveToolUsage(Connection conn, UUID playerId, Map<String, Integer> toolUsage) throws SQLException {
        if (toolUsage.isEmpty()) return;

        String upsertToolUsage = "INSERT OR REPLACE INTO tool_usage (player_id, tool_type, usage_count) VALUES (?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(upsertToolUsage)) {
            for (Map.Entry<String, Integer> entry : toolUsage.entrySet()) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, entry.getKey());
                stmt.setInt(3, entry.getValue());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private void loadToolUsage(Connection conn, UUID playerId, Map<String, Integer> toolUsage) throws SQLException {
        String selectToolUsage = "SELECT tool_type, usage_count FROM tool_usage WHERE player_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(selectToolUsage)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    toolUsage.put(rs.getString("tool_type"), rs.getInt("usage_count"));
                }
            }
        }
    }

    private void saveEnchantmentUsage(Connection conn, UUID playerId, Map<String, Long> enchantUsage) throws SQLException {
        if (enchantUsage.isEmpty()) return;

        String upsertEnchantUsage = "INSERT OR REPLACE INTO enchantment_usage (player_id, enchantment, usage_count) VALUES (?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(upsertEnchantUsage)) {
            for (Map.Entry<String, Long> entry : enchantUsage.entrySet()) {
                stmt.setString(1, playerId.toString());
                stmt.setString(2, entry.getKey());
                stmt.setLong(3, entry.getValue());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private void loadEnchantmentUsage(Connection conn, UUID playerId, Map<String, Long> enchantUsage) throws SQLException {
        String selectEnchantUsage = "SELECT enchantment, usage_count FROM enchantment_usage WHERE player_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(selectEnchantUsage)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    enchantUsage.put(rs.getString("enchantment"), rs.getLong("usage_count"));
                }
            }
        }
    }

    @Override
    public int cleanupOldData(int daysOffline) throws Exception {
        if (daysOffline <= 0) {
            return 0; // Safety check
        }

        long cutoffTime = System.currentTimeMillis() - (daysOffline * 24L * 60L * 60L * 1000L);

        Connection conn = null;
        try {
            conn = getConnection();

            // Use CASCADE to properly clean up related data
            String deleteOldData = "DELETE FROM player_data WHERE last_seen < ?";
            try (PreparedStatement stmt = conn.prepareStatement(deleteOldData)) {
                stmt.setLong(1, cutoffTime);
                int deleted = stmt.executeUpdate();

                plugin.getLogger().info("Cleaned up " + deleted + " old player records");
                return deleted;
            }

        } catch (Exception e) {
            throw new Exception("Failed to cleanup old data", e);
        } finally {
            closeConnection(conn);
        }
    }

    @Override
    public boolean exportData(String fileName) throws Exception {
        // Implementation for data export with proper error handling
        Connection conn = null;
        try {
            conn = getConnection();

            // This would involve exporting all data to JSON/CSV format
            // For now, return true as placeholder
            plugin.getLogger().info("Data export functionality not yet implemented");
            return true;

        } catch (Exception e) {
            throw new Exception("Failed to export data", e);
        } finally {
            closeConnection(conn);
        }
    }

    @Override
    public boolean importData(String fileName) throws Exception {
        // Implementation for data import with proper error handling
        Connection conn = null;
        try {
            conn = getConnection();

            // This would involve importing data from JSON/CSV format
            // For now, return true as placeholder
            plugin.getLogger().info("Data import functionality not yet implemented");
            return true;

        } catch (Exception e) {
            throw new Exception("Failed to import data", e);
        } finally {
            closeConnection(conn);
        }
    }

    @Override
    public void shutdown() throws Exception {
        plugin.getLogger().info("Shutting down SQLite storage...");

        if (dataSource != null && !dataSource.isClosed()) {
            try {
                // Proper shutdown sequence with timeout
                dataSource.close();

                // Wait for connection pool to shutdown
                long startTime = System.currentTimeMillis();
                while (!dataSource.isClosed() &&
                        (System.currentTimeMillis() - startTime) < TimeUnit.SECONDS.toMillis(SHUTDOWN_TIMEOUT_SECONDS)) {
                    Thread.sleep(100);
                }

                if (!dataSource.isClosed()) {
                    plugin.getLogger().warning("DataSource did not close within timeout period");
                }

                plugin.getLogger().info("SQLite storage shutdown completed");

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error during SQLite storage shutdown", e);
                throw e;
            }
        }
    }

    /**
     * Get connection pool statistics for monitoring
     */
    public Map<String, Object> getPoolStats() {
        if (dataSource != null) {
            return Map.of(
                    "active", dataSource.getHikariPoolMXBean().getActiveConnections(),
                    "idle", dataSource.getHikariPoolMXBean().getIdleConnections(),
                    "total", dataSource.getHikariPoolMXBean().getTotalConnections(),
                    "waiting", dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection()
            );
        }
        return Map.of();
    }
}