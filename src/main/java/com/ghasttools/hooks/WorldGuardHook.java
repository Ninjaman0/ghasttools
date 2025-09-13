package com.ghasttools.hooks;

import com.ghasttools.GhastToolsPlugin;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * FIXED: Enhanced thread-safe WorldGuard integration with proper error handling
 */
public class WorldGuardHook {

    private final GhastToolsPlugin plugin;
    private final ConcurrentHashMap<String, StateFlag> flags = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock initLock = new ReentrantReadWriteLock();
    private volatile boolean initialized = false;
    private volatile boolean worldGuardAvailable = false;

    public WorldGuardHook(GhastToolsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * FIXED: Enhanced initialization with proper availability checking
     */
    public synchronized void initialize() {
        initLock.writeLock().lock();
        try {
            if (initialized) {
                plugin.getLogger().fine("WorldGuard hook already initialized");
                return;
            }

            // FIXED: Check if WorldGuard is actually available
            if (!isWorldGuardAvailable()) {
                plugin.getLogger().warning("WorldGuard is not available, hook will be disabled");
                return;
            }

            try {
                FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();

                // Register custom flags with proper error handling
                registerFlag(registry, "ghasttools-use", true);
                registerFlag(registry, "ghasttools-enchant-haste", true);
                registerFlag(registry, "ghasttools-enchant-explosive", true);
                registerFlag(registry, "ghasttools-enchant-speed", true);
                registerFlag(registry, "ghasttools-enchant-xpboost", true);
                registerFlag(registry, "ghasttools-enchant-essenceboost", true);
                registerFlag(registry, "ghasttools-enchant-meteor", true);
                registerFlag(registry, "ghasttools-enchant-airstrike", true);

                worldGuardAvailable = true;
                initialized = true;
                plugin.getLogger().info("WorldGuard flags registered successfully! (" + flags.size() + " flags)");

            } catch (Exception e) {
                plugin.getLogger().warning("Failed to register WorldGuard flags: " + e.getMessage());
                worldGuardAvailable = false;
            }

        } finally {
            initLock.writeLock().unlock();
        }
    }

    /**
     * FIXED: Check if WorldGuard is available and functional
     */
    private boolean isWorldGuardAvailable() {
        try {
            // Check if WorldGuard plugin is loaded
            if (plugin.getServer().getPluginManager().getPlugin("WorldGuard") == null) {
                return false;
            }

            // Check if WorldGuard API is accessible
            WorldGuard.getInstance();
            return true;
        } catch (Exception | NoClassDefFoundError e) {
            plugin.getLogger().fine("WorldGuard API not available: " + e.getMessage());
            return false;
        }
    }

    /**
     * FIXED: Enhanced flag registration with better conflict handling
     */
    private void registerFlag(FlagRegistry registry, String flagName, boolean defaultValue) {
        try {
            StateFlag flag = new StateFlag(flagName, defaultValue);
            registry.register(flag);
            flags.put(flagName, flag);
            plugin.getLogger().fine("Registered WorldGuard flag: " + flagName);
        } catch (FlagConflictException e) {
            // Flag already exists, try to get it
            try {
                StateFlag existingFlag = (StateFlag) registry.get(flagName);
                if (existingFlag != null) {
                    flags.put(flagName, existingFlag);
                    plugin.getLogger().fine("Using existing WorldGuard flag: " + flagName);
                } else {
                    plugin.getLogger().warning("Flag conflict for " + flagName + " but couldn't retrieve existing flag");
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to handle flag conflict for " + flagName + ": " + ex.getMessage());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Unexpected error registering flag " + flagName + ": " + e.getMessage());
        }
    }

    /**
     * FIXED: Enhanced tool usage checking with proper fallbacks
     */
    public boolean canUseTools(Player player, Location location) {
        initLock.readLock().lock();
        try {
            // FIXED: Return true if WorldGuard is not available
            if (!worldGuardAvailable || !initialized) {
                return getDefaultBehavior();
            }

            // Bypass permission check
            if (player.hasPermission("ghasttools.bypass.worldguard")) {
                return true;
            }

            // Check world whitelist/blacklist first
            if (!isWorldAllowed(location.getWorld().getName())) {
                return false;
            }

            StateFlag flag = flags.get("ghasttools-use");
            if (flag == null) {
                return getDefaultBehavior();
            }

            return checkFlag(player, location, flag);
        } catch (Exception e) {
            plugin.getLogger().fine("Error checking tool usage permissions: " + e.getMessage());
            return getDefaultBehavior();
        } finally {
            initLock.readLock().unlock();
        }
    }

    /**
     * FIXED: Enhanced enchantment checking with proper validation
     */
    public boolean canUseEnchantment(Player player, Location location, String enchantment) {
        initLock.readLock().lock();
        try {
            // FIXED: Return true if WorldGuard is not available
            if (!worldGuardAvailable || !initialized) {
                return getDefaultBehavior();
            }

            // Validate parameters
            if (player == null || location == null || enchantment == null) {
                return false;
            }

            // Bypass permission check
            if (player.hasPermission("ghasttools.bypass.worldguard")) {
                return true;
            }

            // Check world whitelist/blacklist first
            if (!isWorldAllowed(location.getWorld().getName())) {
                return false;
            }

            // Check general tool usage first
            if (!canUseTools(player, location)) {
                return false;
            }

            StateFlag flag = getEnchantmentFlag(enchantment);
            if (flag == null) {
                return getDefaultBehavior(); // Allow if flag doesn't exist
            }

            return checkFlag(player, location, flag);
        } catch (Exception e) {
            plugin.getLogger().fine("Error checking enchantment permissions: " + e.getMessage());
            return getDefaultBehavior();
        } finally {
            initLock.readLock().unlock();
        }
    }

    /**
     * FIXED: Enhanced flag checking with proper error handling
     */
    private boolean checkFlag(Player player, Location location, StateFlag flag) {
        try {
            LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
            RegionManager regionManager = WorldGuard.getInstance().getPlatform()
                    .getRegionContainer()
                    .get(BukkitAdapter.adapt(location.getWorld()));

            if (regionManager == null) {
                plugin.getLogger().fine("No region manager found for world: " + location.getWorld().getName());
                return getDefaultBehavior();
            }

            ApplicableRegionSet regions = regionManager.getApplicableRegions(
                    BukkitAdapter.asBlockVector(location)
            );

            StateFlag.State result = regions.queryState(localPlayer, flag);

            // Convert StateFlag.State to boolean properly
            if (result == StateFlag.State.ALLOW) {
                return true;
            } else if (result == StateFlag.State.DENY) {
                return false;
            } else {
                // result is null, use default behavior
                return getDefaultBehavior();
            }

        } catch (Exception e) {
            plugin.getLogger().fine("Error checking WorldGuard flag " + flag.getName() + ": " + e.getMessage());
            return getDefaultBehavior();
        }
    }

    /**
     * Get enchantment-specific flag
     */
    private StateFlag getEnchantmentFlag(String enchantment) {
        String flagName = "ghasttools-enchant-" + enchantment.toLowerCase();
        return flags.get(flagName);
    }

    /**
     * FIXED: Enhanced world checking with proper configuration validation
     */
    private boolean isWorldAllowed(String worldName) {
        if (worldName == null) {
            return false;
        }

        try {
            var config = plugin.getConfigManager().getMainConfig();
            if (config == null) {
                return true; // Allow if config is not available
            }

            List<String> whitelist = config.getStringList("worlds.whitelist");
            List<String> blacklist = config.getStringList("worlds.blacklist");

            // If whitelist is not empty, world must be in whitelist
            if (!whitelist.isEmpty()) {
                return whitelist.contains(worldName);
            }

            // If blacklist is not empty, world must not be in blacklist
            if (!blacklist.isEmpty()) {
                return !blacklist.contains(worldName);
            }

            // If no lists configured, allow all worlds
            return true;
        } catch (Exception e) {
            plugin.getLogger().fine("Error checking world restrictions: " + e.getMessage());
            return true; // Default to allow on error
        }
    }

    /**
     * FIXED: Enhanced default behavior with proper configuration handling
     */
    private boolean getDefaultBehavior() {
        try {
            var config = plugin.getConfigManager().getMainConfig();
            if (config != null) {
                return config.getString("worldguard.default_behavior", "allow")
                        .equalsIgnoreCase("allow");
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Error getting default behavior: " + e.getMessage());
        }
        return true; // Default to allow on error
    }

    /**
     * FIXED: Enhanced deny message sending with proper null checks
     */
    public void sendDenyMessage(Player player, String reason) {
        if (player == null) return;

        try {
            var config = plugin.getConfigManager().getMainConfig();
            boolean notify = config != null ?
                    config.getBoolean("worldguard.notify_on_deny", true) : true;

            if (notify) {
                Map<String, String> placeholders = Map.of("reason", reason != null ? reason : "Unknown");
                plugin.getMessageUtil().sendMessage(player, "worldguard_deny", placeholders);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Error sending deny message: " + e.getMessage());
            // Fallback message
            player.sendMessage("§cThis tool is disabled in this area!");
        }
    }

    /**
     * FIXED: Enhanced specific region checking
     */
    public boolean canUseInRegion(Player player, Location location, String regionId) {
        initLock.readLock().lock();
        try {
            if (!worldGuardAvailable || !initialized || regionId == null) {
                return getDefaultBehavior();
            }

            LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
            RegionManager regionManager = WorldGuard.getInstance().getPlatform()
                    .getRegionContainer()
                    .get(BukkitAdapter.adapt(location.getWorld()));

            if (regionManager == null) {
                return getDefaultBehavior();
            }

            var region = regionManager.getRegion(regionId);

            if (region == null) {
                return getDefaultBehavior();
            }

            StateFlag flag = flags.get("ghasttools-use");
            if (flag == null) {
                return getDefaultBehavior();
            }

            StateFlag.State result = region.getFlag(flag);

            // Convert StateFlag.State to boolean properly
            if (result == StateFlag.State.ALLOW) {
                return true;
            } else if (result == StateFlag.State.DENY) {
                return false;
            } else {
                return getDefaultBehavior();
            }

        } catch (Exception e) {
            plugin.getLogger().fine("Error checking specific region: " + e.getMessage());
            return getDefaultBehavior();
        } finally {
            initLock.readLock().unlock();
        }
    }

    /**
     * Get all registered flags for debugging
     */
    public Map<String, StateFlag> getRegisteredFlags() {
        return new ConcurrentHashMap<>(flags);
    }

    /**
     * Check if hook is properly initialized
     */
    public boolean isInitialized() {
        initLock.readLock().lock();
        try {
            return initialized && worldGuardAvailable;
        } finally {
            initLock.readLock().unlock();
        }
    }

    /**
     * Get flag count for monitoring
     */
    public int getFlagCount() {
        return flags.size();
    }

    /**
     * FIXED: Enhanced reinitialization with proper cleanup
     */
    public void reinitialize() {
        initLock.writeLock().lock();
        try {
            initialized = false;
            worldGuardAvailable = false;
            flags.clear();
            initialize();
        } finally {
            initLock.writeLock().unlock();
        }
    }


}