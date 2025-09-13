package com.ghasttools;

import com.ghasttools.blocks.BlockBreaker;
import com.ghasttools.commands.GhastToolsCommand;
import com.ghasttools.commands.MilestoneCommand;
import com.ghasttools.commands.ProfileCommand;
import com.ghasttools.config.ConfigManager;
import com.ghasttools.data.DataManager;
import com.ghasttools.enchantments.EnchantmentManager;
import com.ghasttools.essence.EssenceHandler;
import com.ghasttools.gui.GuiManager;
import com.ghasttools.hooks.PlaceholderAPIHook;
import com.ghasttools.hooks.ProtocolLibHook;
import com.ghasttools.hooks.WorldGuardHook;
import com.ghasttools.levelsmanager.levelshandler;
import com.ghasttools.listeners.*;
import com.ghasttools.milestones.MilestoneGui;
import com.ghasttools.milestones.MilestoneManager;
import com.ghasttools.regeneration.BlockRegenerationManager;
import com.ghasttools.tools.ToolManager;
import com.ghasttools.utils.MessageUtil;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;

/**
 * ENHANCED: Main plugin class with Milestone system and Profile system integration
 */
public class GhastToolsPlugin extends JavaPlugin {

    private static volatile GhastToolsPlugin instance;
    private final AtomicBoolean isReloading = new AtomicBoolean(false);
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, BukkitTask> activeTasks = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock reloadLock = new ReentrantReadWriteLock();

    // Proper async operation management
    private ExecutorService asyncExecutor;
    private ExecutorService databaseExecutor;

    // Core managers (volatile for thread safety)
    private volatile ConfigManager configManager;
    private volatile DataManager dataManager;
    private volatile ToolManager toolManager;
    private volatile EnchantmentManager enchantmentManager;
    private volatile MessageUtil messageUtil;
    private volatile BlockBreaker blockBreaker;

    // Enhanced managers
    private volatile BlockRegenerationManager blockRegenerationManager;
    private volatile levelshandler levelsHandler;
    private volatile GuiManager guiManager;
    private volatile EssenceHandler essenceHandler;

    // ADDED: Milestone system components
    private volatile MilestoneManager milestoneManager;
    private volatile MilestoneGui milestoneGui;

    // ADDED: Profile system components
    private volatile ProfileCommand profileCommand;

    // Integration hooks (volatile for thread safety)
    private volatile ProtocolLibHook protocolLibHook;
    private volatile WorldGuardHook worldGuardHook;
    private volatile PlaceholderAPIHook placeholderAPIHook;

    // Listeners for proper cleanup
    private volatile PlayerJoinListener playerJoinListener;
    private volatile GuiClickListener guiClickListener;
    private volatile MilestoneGuiListener milestoneGuiListener;
    private volatile ProfileGuiListener profileGuiListener;

    // Configuration constants to avoid magic numbers
    private static final int ASYNC_THREAD_POOL_SIZE = 4;
    private static final int DATABASE_THREAD_POOL_SIZE = 2;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;
    private static final long CLEANUP_INTERVAL_TICKS = 20 * 60; // 1 minute

    @Override
    public void onEnable() {
        instance = this;

        getLogger().info("Starting GhastTools v" + getDescription().getVersion());

        try {
            // Initialize thread pools first
            initializeThreadPools();

            // Check for required dependencies
            if (!checkDependencies()) {
                getLogger().severe("Required dependencies not found! Disabling plugin.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            // Initialize all components with proper error handling
            if (!initializePlugin()) {
                getLogger().severe("Failed to initialize plugin components!");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            // Start cleanup tasks
            startCleanupTasks();

            getLogger().info("GhastTools has been enabled successfully!");

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to enable GhastTools!", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabling GhastTools...");
        isShuttingDown.set(true);

        reloadLock.writeLock().lock();
        try {
            // Proper shutdown sequence for async operations
            shutdownAsyncOperations();

            // Cancel all active tasks
            cancelAllTasks();

            // Cleanup listeners
            cleanupListeners();

            // ADDED: Shutdown milestone system
            if (milestoneManager != null) {
                try {
                    milestoneManager.shutdown();
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Error during milestone manager shutdown", e);
                }
            }

            if (milestoneGui != null) {
                try {
                    milestoneGui.shutdown();
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Error during milestone GUI shutdown", e);
                }
            }

            // ADDED: Shutdown profile system
            if (profileCommand != null) {
                try {
                    profileCommand.shutdown();
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Error during profile command shutdown", e);
                }
            }

            // Shutdown block regeneration manager
            if (blockRegenerationManager != null) {
                try {
                    blockRegenerationManager.shutdown();
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Error during block regeneration manager shutdown", e);
                }
            }

            // Shutdown GUI manager
            if (guiManager != null) {
                try {
                    guiManager.shutdown();
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Error during GUI manager shutdown", e);
                }
            }

            // Shutdown levels handler
            if (levelsHandler != null) {
                try {
                    levelsHandler.shutdown();
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Error during levels handler shutdown", e);
                }
            }

            // Shutdown essence handler
            if (essenceHandler != null) {
                try {
                    essenceHandler.shutdown();
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Error during essence handler shutdown", e);
                }
            }

            // Save data and cleanup
            if (dataManager != null) {
                try {
                    dataManager.shutdown();
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Error during data manager shutdown", e);
                }
            }

            // Cleanup message util
            if (messageUtil != null) {
                try {
                    messageUtil.cleanup();
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Error during message util cleanup", e);
                }
            }

            // Cleanup hooks
            cleanupHooks();

            // Shutdown thread pools
            shutdownThreadPools();

            instance = null;
            getLogger().info("GhastTools has been disabled.");
        } finally {
            reloadLock.writeLock().unlock();
        }
    }

    /**
     * Initialize thread pools for proper async operation management
     */
    private void initializeThreadPools() {
        asyncExecutor = Executors.newFixedThreadPool(ASYNC_THREAD_POOL_SIZE, r -> {
            Thread t = new Thread(r, "GhastTools-Async");
            t.setDaemon(true);
            return t;
        });

        databaseExecutor = Executors.newFixedThreadPool(DATABASE_THREAD_POOL_SIZE, r -> {
            Thread t = new Thread(r, "GhastTools-Database");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Proper shutdown sequence for async operations
     */
    private void shutdownAsyncOperations() {
        getLogger().info("Shutting down async operations...");

        // Signal all async operations to stop
        isShuttingDown.set(true);

        // Wait for current operations to complete
        try {
            Thread.sleep(1000); // Give operations time to check shutdown flag
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Cleanup listeners to prevent memory leaks
     */
    private void cleanupListeners() {
        if (playerJoinListener != null) {
            try {
                playerJoinListener.cleanup();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error during player join listener cleanup", e);
            }
        }
    }

    /**
     * Proper thread pool shutdown
     */
    private void shutdownThreadPools() {
        if (asyncExecutor != null && !asyncExecutor.isShutdown()) {
            asyncExecutor.shutdown();
            try {
                if (!asyncExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    asyncExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                asyncExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (databaseExecutor != null && !databaseExecutor.isShutdown()) {
            databaseExecutor.shutdown();
            try {
                if (!databaseExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    databaseExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                databaseExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Start cleanup tasks for resource management
     */
    private void startCleanupTasks() {
        BukkitTask cleanupTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (isShuttingDown.get()) return;

            try {
                // Cleanup expired cooldowns and cache
                if (messageUtil != null) {
                    messageUtil.clearExpiredCache();
                }

                // Cleanup offline player data from memory
                cleanupOfflinePlayerData();

            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error during cleanup task", e);
            }

        }, CLEANUP_INTERVAL_TICKS, CLEANUP_INTERVAL_TICKS);

        registerTask("cleanup", cleanupTask);
    }

    /**
     * Cleanup offline player data to prevent memory leaks
     */
    private void cleanupOfflinePlayerData() {
        // This will be called by the cleanup task to remove offline player data from memory
        if (dataManager != null) {
            // Cleanup will be handled by individual managers
        }
    }

    /**
     * Enhanced reload functionality with proper locking and ALL config reloading
     */
    public boolean reloadPlugin() {
        if (isReloading.getAndSet(true)) {
            getLogger().warning("Plugin is already reloading!");
            return false;
        }

        reloadLock.writeLock().lock();
        try {
            getLogger().info("Reloading GhastTools...");

            // Cancel all active tasks
            cancelAllTasks();

            // Reload ALL configurations properly including milestone config
            if (configManager != null) {
                configManager.reloadAllConfigs();
                getLogger().info("All configuration files reloaded");
            }

            // Reload block regeneration manager
            if (blockRegenerationManager != null) {
                blockRegenerationManager.loadConfigurations();
                getLogger().info("Block regeneration configurations reloaded");
            }

            // ADDED: Reload milestone system
            if (milestoneManager != null) {
                milestoneManager.loadMilestoneConfiguration();
                getLogger().info("Milestone configurations reloaded");
            }

            // Reinitialize managers with error handling
            try {
                if (toolManager != null) {
                    toolManager.loadTools();
                    getLogger().info("Tool configurations reloaded");
                }

                if (enchantmentManager != null) {
                    enchantmentManager.loadEnchantments();
                    getLogger().info("Enchantment configurations reloaded");
                }

                if (messageUtil != null) {
                    messageUtil.reloadMessages();
                    getLogger().info("Message configurations reloaded");
                }

                // Reload GUI manager configuration
                if (guiManager != null) {
                    // GUI manager reloads configuration internally
                    getLogger().info("GUI configurations reloaded");
                }

                // Reinitialize essence handler
                if (essenceHandler != null) {
                    essenceHandler.reinitialize();
                    getLogger().info("Essence handler reinitialized");
                }

                // Reinitialize hooks if needed
                reinitializeHooks();

                // Restart cleanup tasks
                startCleanupTasks();
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Error during component reinitialization", e);
                return false;
            }

            getLogger().info("GhastTools reloaded successfully!");
            return true;

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to reload GhastTools!", e);
            return false;
        } finally {
            isReloading.set(false);
            reloadLock.writeLock().unlock();
        }
    }

    /**
     * Enhanced dependency checking with proper null validation
     */
    private boolean checkDependencies() {
        // ProtocolLib is required
        if (getServer().getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().severe("ProtocolLib is required but not found!");
            return false;
        }

        // Log optional dependencies status
        if (getServer().getPluginManager().getPlugin("WorldGuard") != null) {
            getLogger().info("WorldGuard found - Integration will be enabled");
        } else {
            getLogger().info("WorldGuard not found - Integration will be disabled");
        }

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            getLogger().info("PlaceholderAPI found - Integration will be enabled");
        } else {
            getLogger().info("PlaceholderAPI not found - Integration will be disabled");
        }

        // Check for GhastLevels dependency
        if (getServer().getPluginManager().getPlugin("GhastLevels") != null) {
            getLogger().info("GhastLevels found - Level requirements will be enabled");
        } else {
            getLogger().warning("GhastLevels not found - Level requirements will be disabled");
        }

        // Check for GhastEssence dependency
        if (getServer().getPluginManager().getPlugin("GhastEssence") != null) {
            getLogger().info("GhastEssence found - Essence level currency will be enabled");
        } else {
            getLogger().warning("GhastEssence not found - Essence level currency will be disabled");
        }

        return true;
    }

    /**
     * Initialize all plugin components with proper error handling and proper order
     */
    private boolean initializePlugin() {
        try {
            // Initialize core managers in proper order
            initializeManagers();

            // Initialize hooks
            initializeHooks();

            // Initialize levels handler, essence handler, and GUI manager
            initializeLevelsEssenceAndGui();

            // Initialize block regeneration manager
            initializeBlockRegeneration();

            // ADDED: Initialize milestone system
            initializeMilestoneSystem();

            // ADDED: Initialize profile system
            initializeProfileSystem();

            // Register listeners
            registerListeners();

            // Register commands
            registerCommands();

            return true;

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error during plugin initialization", e);
            return false;
        }
    }

    /**
     * Initialize core managers with proper error handling and order
     */
    private void initializeManagers() {
        getLogger().info("Initializing core managers...");

        // Config manager first
        configManager = new ConfigManager(this);
        configManager.loadConfigs();

        // Message utility
        messageUtil = new MessageUtil(this);

        // Data manager
        dataManager = new DataManager(this);
        dataManager.initialize();

        // Enchantment manager (before tool manager)
        enchantmentManager = new EnchantmentManager(this);
        enchantmentManager.loadEnchantments();

        // Tool manager (after enchantment manager)
        toolManager = new ToolManager(this);
        toolManager.loadTools();

        // Block breaker
        blockBreaker = new BlockBreaker(this);

        getLogger().info("Core managers initialized successfully");
    }

    /**
     * Initialize block regeneration manager
     */
    private void initializeBlockRegeneration() {
        getLogger().info("Initializing block regeneration manager...");

        try {
            blockRegenerationManager = new BlockRegenerationManager(this);
            blockRegenerationManager.loadConfigurations();
            getLogger().info("Block regeneration manager initialized successfully");

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialize block regeneration manager", e);
            throw e;
        }
    }

    /**
     * ADDED: Initialize milestone system
     */
    private void initializeMilestoneSystem() {
        getLogger().info("Initializing milestone system...");

        try {
            // Initialize milestone manager
            milestoneManager = new MilestoneManager(this);
            getLogger().info("Milestone manager initialized successfully");

            // Initialize milestone GUI
            milestoneGui = new MilestoneGui(this, milestoneManager);
            getLogger().info("Milestone GUI initialized successfully");

        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to initialize milestone system", e);
            // Don't fail the entire plugin if milestone system fails
            milestoneManager = null;
            milestoneGui = null;
        }
    }

    /**
     * ADDED: Initialize profile system
     */
    private void initializeProfileSystem() {
        getLogger().info("Initializing profile system...");

        try {
            // Initialize profile command
            profileCommand = new ProfileCommand(this);
            getLogger().info("Profile system initialized successfully");

        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to initialize profile system", e);
            // Don't fail the entire plugin if profile system fails
            profileCommand = null;
        }
    }

    /**
     * Initialize levels handler, essence handler, and GUI manager
     */
    private void initializeLevelsEssenceAndGui() {
        getLogger().info("Initializing levels handler, essence handler, and GUI manager...");

        try {
            // Initialize levels handler
            levelsHandler = new levelshandler(this);
            getLogger().info("Levels handler initialized successfully");

            // Initialize essence handler
            essenceHandler = new EssenceHandler(this);
            getLogger().info("Essence handler initialized successfully");

            // Initialize GUI manager (depends on levels handler and essence handler)
            guiManager = new GuiManager(this, levelsHandler);
            getLogger().info("GUI manager initialized successfully");

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialize levels/essence handler or GUI manager", e);
            throw e;
        }
    }

    /**
     * Initialize integration hooks with proper null checks
     */
    private void initializeHooks() {
        getLogger().info("Initializing integration hooks...");

        // ProtocolLib (required)
        try {
            protocolLibHook = new ProtocolLibHook(this);
            protocolLibHook.initialize();
            getLogger().info("ProtocolLib integration enabled!");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialize ProtocolLib hook!", e);
            throw new RuntimeException("ProtocolLib initialization failed", e);
        }

        // WorldGuard (optional) with proper null checks
        if (getServer().getPluginManager().getPlugin("WorldGuard") != null) {
            try {
                worldGuardHook = new WorldGuardHook(this);
                worldGuardHook.initialize();
                getLogger().info("WorldGuard integration enabled!");
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to initialize WorldGuard hook", e);
                worldGuardHook = null;
            }
        } else {
            getLogger().info("WorldGuard not found - skipping integration");
        }

        // PlaceholderAPI (optional) with proper null checks
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                placeholderAPIHook = new PlaceholderAPIHook(this);
                if (placeholderAPIHook.register()) {
                    getLogger().info("PlaceholderAPI integration enabled!");
                } else {
                    getLogger().warning("Failed to register PlaceholderAPI expansion");
                    placeholderAPIHook = null;
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to initialize PlaceholderAPI hook", e);
                placeholderAPIHook = null;
            }
        } else {
            getLogger().info("PlaceholderAPI not found - skipping integration");
        }
    }

    /**
     * Reinitialize hooks during reload
     */
    private void reinitializeHooks() {
        // Reinitialize WorldGuard if available
        if (getServer().getPluginManager().getPlugin("WorldGuard") != null && worldGuardHook != null) {
            try {
                worldGuardHook.initialize();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to reinitialize WorldGuard hook", e);
            }
        }

        // PlaceholderAPI doesn't need reinitialization as it's event-driven
        if (placeholderAPIHook != null) {
            placeholderAPIHook.clearCache();
        }
    }

    /**
     * Enhanced cleanup with proper null checks
     */
    private void cleanupHooks() {
        if (protocolLibHook != null) {
            try {
                protocolLibHook.cleanup();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error during ProtocolLib cleanup", e);
            }
        }

        if (placeholderAPIHook != null) {
            try {
                placeholderAPIHook.unregister();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error during PlaceholderAPI cleanup", e);
            }
        }

        if (worldGuardHook != null) {
            try {
                // WorldGuard doesn't need special cleanup
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error during WorldGuard cleanup", e);
            }
        }
    }

    /**
     * Register event listeners with proper tracking including milestone and profile GUI listeners
     */
    private void registerListeners() {
        getLogger().info("Registering event listeners...");

        getServer().getPluginManager().registerEvents(new BlockBreakListener(this), this);

        // Register PlayerInteractListener with GuiManager
        getServer().getPluginManager().registerEvents(new PlayerInteractListener(this, guiManager), this);

        // Register GUI click listener
        guiClickListener = new GuiClickListener(this, guiManager);
        getServer().getPluginManager().registerEvents(guiClickListener, this);

        // FIXED: Register milestone GUI listener with correct parameters
        if (milestoneGui != null) {
            milestoneGuiListener = new MilestoneGuiListener(this, milestoneGui);
            getServer().getPluginManager().registerEvents(milestoneGuiListener, this);
        }

        // ADDED: Register profile GUI listener
        if (profileCommand != null) {
            profileGuiListener = new ProfileGuiListener(this, profileCommand);
            getServer().getPluginManager().registerEvents(profileGuiListener, this);
        }

        // Store player join listener for cleanup
        playerJoinListener = new PlayerJoinListener(this);
        getServer().getPluginManager().registerEvents(playerJoinListener, this);
    }

    /**
     * Register commands including milestone and profile commands
     */
    private void registerCommands() {
        getLogger().info("Registering commands...");

        GhastToolsCommand mainCommand = new GhastToolsCommand(this);
        if (getCommand("ghasttools") != null) {
            getCommand("ghasttools").setExecutor(mainCommand);
            getCommand("ghasttools").setTabCompleter(mainCommand);
        } else {
            getLogger().warning("Command 'ghasttools' not found in plugin.yml!");
        }

        // ADDED: Register milestone commands
        if (milestoneManager != null && milestoneGui != null) {
            MilestoneCommand milestoneCommand = new MilestoneCommand(this, milestoneManager, milestoneGui);

            if (getCommand("milestone") != null) {
                getCommand("milestone").setExecutor(milestoneCommand);
                getCommand("milestone").setTabCompleter(milestoneCommand);
            } else {
                getLogger().warning("Command 'milestone' not found in plugin.yml!");
            }

            if (getCommand("ms") != null) {
                getCommand("ms").setExecutor(milestoneCommand);
                getCommand("ms").setTabCompleter(milestoneCommand);
            } else {
                getLogger().warning("Command 'ms' not found in plugin.yml!");
            }
        }

        // ADDED: Register profile command
        if (profileCommand != null) {
            if (getCommand("profile") != null) {
                getCommand("profile").setExecutor(profileCommand);
                getCommand("profile").setTabCompleter(profileCommand);
            } else {
                getLogger().warning("Command 'profile' not found in plugin.yml!");
            }
        }
    }

    /**
     * Cancel all active tasks
     */
    private void cancelAllTasks() {
        activeTasks.values().forEach(task -> {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        });
        activeTasks.clear();
    }

    /**
     * Register a task for tracking
     */
    public void registerTask(String taskId, BukkitTask task) {
        if (task != null) {
            activeTasks.put(taskId, task);
        }
    }

    /**
     * Unregister a task
     */
    public void unregisterTask(String taskId) {
        activeTasks.remove(taskId);
    }

    /**
     * Get task count for monitoring
     */
    public int getActiveTaskCount() {
        return activeTasks.size();
    }

    // Thread-safe getters with proper null checks
    public static GhastToolsPlugin getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        reloadLock.readLock().lock();
        try {
            return configManager;
        } finally {
            reloadLock.readLock().unlock();
        }
    }

    public DataManager getDataManager() {
        reloadLock.readLock().lock();
        try {
            return dataManager;
        } finally {
            reloadLock.readLock().unlock();
        }
    }

    public ToolManager getToolManager() {
        reloadLock.readLock().lock();
        try {
            return toolManager;
        } finally {
            reloadLock.readLock().unlock();
        }
    }

    public EnchantmentManager getEnchantmentManager() {
        reloadLock.readLock().lock();
        try {
            return enchantmentManager;
        } finally {
            reloadLock.readLock().unlock();
        }
    }

    public MessageUtil getMessageUtil() {
        reloadLock.readLock().lock();
        try {
            return messageUtil;
        } finally {
            reloadLock.readLock().unlock();
        }
    }

    public BlockBreaker getBlockBreaker() {
        reloadLock.readLock().lock();
        try {
            return blockBreaker;
        } finally {
            reloadLock.readLock().unlock();
        }
    }

    public BlockRegenerationManager getBlockRegenerationManager() {
        reloadLock.readLock().lock();
        try {
            return blockRegenerationManager;
        } finally {
            reloadLock.readLock().unlock();
        }
    }

    // Add getters for new managers
    public levelshandler getLevelsHandler() {
        reloadLock.readLock().lock();
        try {
            return levelsHandler;
        } finally {
            reloadLock.readLock().unlock();
        }
    }

    public GuiManager getGuiManager() {
        reloadLock.readLock().lock();
        try {
            return guiManager;
        } finally {
            reloadLock.readLock().unlock();
        }
    }

    public EssenceHandler getEssenceHandler() {
        reloadLock.readLock().lock();
        try {
            return essenceHandler;
        } finally {
            reloadLock.readLock().unlock();
        }
    }

    // ADDED: Milestone system getters
    public MilestoneManager getMilestoneManager() {
        reloadLock.readLock().lock();
        try {
            return milestoneManager;
        } finally {
            reloadLock.readLock().unlock();
        }
    }

    public MilestoneGui getMilestoneGui() {
        reloadLock.readLock().lock();
        try {
            return milestoneGui;
        } finally {
            reloadLock.readLock().unlock();
        }
    }

    // ADDED: Profile system getter
    public ProfileCommand getProfileCommand() {
        reloadLock.readLock().lock();
        try {
            return profileCommand;
        } finally {
            reloadLock.readLock().unlock();
        }
    }

    public ProtocolLibHook getProtocolLibHook() {
        reloadLock.readLock().lock();
        try {
            return protocolLibHook;
        } finally {
            reloadLock.readLock().unlock();
        }
    }

    public WorldGuardHook getWorldGuardHook() {
        reloadLock.readLock().lock();
        try {
            return worldGuardHook;
        } finally {
            reloadLock.readLock().unlock();
        }
    }

    public PlaceholderAPIHook getPlaceholderAPIHook() {
        reloadLock.readLock().lock();
        try {
            return placeholderAPIHook;
        } finally {
            reloadLock.readLock().unlock();
        }
    }

    public boolean isReloading() {
        return isReloading.get();
    }

    public boolean isShuttingDown() {
        return isShuttingDown.get();
    }

    // Provide access to thread pools for proper async operations
    public ExecutorService getAsyncExecutor() {
        return asyncExecutor;
    }

    public ExecutorService getDatabaseExecutor() {
        return databaseExecutor;
    }
}