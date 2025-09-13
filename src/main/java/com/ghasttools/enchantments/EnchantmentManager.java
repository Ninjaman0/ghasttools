package com.ghasttools.enchantments;

import com.ghasttools.GhastToolsPlugin;
import com.ghasttools.data.PlayerData;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;

/**
 * FIXED: Enhanced enchantment manager with GUARANTEED airstrike animations and block breaking
 */
public class EnchantmentManager {

    private final GhastToolsPlugin plugin;
    private final Map<String, EnchantmentConfig> enchantmentConfigs = new HashMap<>();
    private final Random random = new Random();

    // Thread-safe animation and cooldown tracking
    private final ConcurrentHashMap<Player, ConcurrentHashMap<String, AtomicInteger>> activeAnimations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Player, ConcurrentHashMap<String, Long>> enchantmentCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Player, Long> globalCooldowns = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock configLock = new ReentrantReadWriteLock();

    // Enhanced sound system with validation
    private final ConcurrentHashMap<String, Sound> validatedSounds = new ConcurrentHashMap<>();
    private static final long GLOBAL_COOLDOWN_MS = 500;
    private static final int MAX_CONCURRENT_SOUNDS = 5;

    // FIXED: Only these enchantments can play sounds
    private static final java.util.Set<String> SOUND_ENABLED_ENCHANTMENTS = java.util.Set.of(
            "explosive", "meteor", "airstrike"
    );

    public EnchantmentManager(GhastToolsPlugin plugin) {
        this.plugin = plugin;
        initializeSoundCache();
    }

    /**
     * Initialize and validate sound cache for performance and safety
     */
    private void initializeSoundCache() {
        try {
            // Pre-validate common enchantment sounds
            validateAndCacheSound("entity_generic_explode", Sound.ENTITY_GENERIC_EXPLODE);
            validateAndCacheSound("entity_dragon_fireball_explode", Sound.ENTITY_DRAGON_FIREBALL_EXPLODE);
            validateAndCacheSound("entity_tnt_primed", Sound.ENTITY_TNT_PRIMED);
            validateAndCacheSound("entity_wither_shoot", Sound.ENTITY_WITHER_SHOOT);
            validateAndCacheSound("entity_lightning_bolt_thunder", Sound.ENTITY_LIGHTNING_BOLT_THUNDER);
            validateAndCacheSound("block_anvil_land", Sound.BLOCK_ANVIL_LAND);
            validateAndCacheSound("entity_firework_rocket_blast", Sound.ENTITY_FIREWORK_ROCKET_BLAST);

            plugin.getLogger().info("Sound cache initialized with " + validatedSounds.size() + " validated sounds");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error initializing sound cache", e);
        }
    }

    /**
     * Validate and cache sound to prevent runtime errors
     */
    private void validateAndCacheSound(String soundName, Sound defaultSound) {
        try {
            Sound sound = Sound.valueOf(soundName.toUpperCase());
            validatedSounds.put(soundName.toLowerCase(), sound);
        } catch (IllegalArgumentException e) {
            validatedSounds.put(soundName.toLowerCase(), defaultSound);
        }
    }

    /**
     * Load enchantment configurations with thread safety
     */
    public void loadEnchantments() {
        configLock.writeLock().lock();
        try {
            plugin.getLogger().info("Loading enchantment configurations...");

            enchantmentConfigs.clear();

            // Load ALL enchantments from ALL tool configs to make them compatible with ALL tools
            loadAllEnchantmentConfigs("pickaxe");
            loadAllEnchantmentConfigs("axe");
            loadAllEnchantmentConfigs("hoe");

            plugin.getLogger().info("Loaded " + enchantmentConfigs.size() + " enchantment configurations for ALL tools");
        } finally {
            configLock.writeLock().unlock();
        }
    }

    /**
     * Load ALL enchantments to make them compatible with ALL tools
     */
    private void loadAllEnchantmentConfigs(String toolType) {
        FileConfiguration config = null;

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

        if (config == null) return;

        ConfigurationSection enchantSection = config.getConfigurationSection("enchantments");
        if (enchantSection != null) {
            for (String enchantName : enchantSection.getKeys(false)) {
                if (!enchantmentConfigs.containsKey(enchantName)) {
                    EnchantmentConfig enchantConfig = loadSingleEnchantmentConfig(enchantSection, enchantName);
                    if (enchantConfig != null) {
                        enchantmentConfigs.put(enchantName, enchantConfig);
                        plugin.getLogger().info("Loaded enchantment '" + enchantName + "' - now compatible with ALL tools");
                    }
                }
            }
        }
    }

    /**
     * Load single enchantment config with proper validation
     */
    private EnchantmentConfig loadSingleEnchantmentConfig(ConfigurationSection enchantSection, String enchantName) {
        try {
            ConfigurationSection enchantData = enchantSection.getConfigurationSection(enchantName);
            if (enchantData == null) return null;

            EnchantmentConfig enchantConfig = new EnchantmentConfig();

            enchantConfig.setEnabled(enchantData.getBoolean("enabled", true));
            enchantConfig.setMaxLevel(enchantData.getInt("max_level", 1));
            enchantConfig.setChances(enchantData.getDoubleList("chance"));
            enchantConfig.setCooldown(enchantData.getInt("cooldown", 0));
            enchantConfig.setDescription(enchantData.getString("description", "No description"));

            // Load and validate sound with fallback
            String soundName = enchantData.getString("sound", "entity_generic_explode");
            validateAndCacheSound(soundName, Sound.ENTITY_GENERIC_EXPLODE);
            enchantConfig.setSound(soundName);

            // Load specific enchantment configurations
            loadSpecificEnchantmentConfig(enchantConfig, enchantName, enchantData);

            return enchantConfig;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error loading enchantment config for " + enchantName, e);
            return null;
        }
    }

    /**
     * Load specific enchantment configurations with proper validation
     */
    private void loadSpecificEnchantmentConfig(EnchantmentConfig enchantConfig, String enchantName, ConfigurationSection enchantData) {
        switch (enchantName) {
            case "meteor":
            case "airstrike":
                enchantConfig.setRadius(enchantData.getIntegerList("radius"));
                enchantConfig.setEntityType(enchantData.getString("entity_type", "falling_block"));
                enchantConfig.setAnimation(enchantData.getString("animation", "linear"));
                enchantConfig.setMaxActive(enchantData.getInt("max_active", 2));
                enchantConfig.setParticles(enchantData.getString("particles", "explosion"));

                if (enchantName.equals("meteor")) {
                    enchantConfig.setMaterials(enchantData.getStringList("materials"));
                    enchantConfig.setCustomModelData(enchantData.getIntegerList("custom_model_data"));
                    enchantConfig.setMeteorSize(enchantData.getIntegerList("meteor_size"));
                }
                break;

            case "explosive":
                enchantConfig.setRadius(enchantData.getIntegerList("radius"));
                enchantConfig.setParticles(enchantData.getString("particles", "explosion"));
                break;

            case "haste":
                enchantConfig.setHasteEffects(enchantData.getIntegerList("haste_levels"));
                break;

            case "speed":
                enchantConfig.setSpeedDuration(enchantData.getIntegerList("duration"));
                enchantConfig.setSpeedAmplifier(enchantData.getIntegerList("amplifier"));
                break;

            case "xpboost":
            case "essenceboost":
                enchantConfig.setMultiplier(enchantData.getDoubleList("multiplier"));
                break;
        }
    }

    /**
     * Apply enchantment to tool (thread-safe)
     */
    public void applyEnchantment(ItemStack tool, String enchantment, int level) {
        if (!plugin.getToolManager().isGhastTool(tool)) {
            return;
        }

        configLock.readLock().lock();
        try {
            Map<String, Integer> enchantments = plugin.getToolManager().getToolEnchantments(tool);
            enchantments.put(enchantment, level);
            plugin.getToolManager().updateToolEnchantments(tool, enchantments);
        } finally {
            configLock.readLock().unlock();
        }
    }

    /**
     * Remove enchantment from tool (thread-safe)
     */
    public void removeEnchantment(ItemStack tool, String enchantment) {
        if (!plugin.getToolManager().isGhastTool(tool)) {
            return;
        }

        configLock.readLock().lock();
        try {
            Map<String, Integer> enchantments = plugin.getToolManager().getToolEnchantments(tool);
            enchantments.remove(enchantment);
            plugin.getToolManager().updateToolEnchantments(tool, enchantments);
        } finally {
            configLock.readLock().unlock();
        }
    }

    /**
     * Enhanced enchantment triggering with proper block breaking for ALL enchantments
     */
    public boolean triggerEnchantment(Player player, ItemStack tool, String enchantment, BlockBreakEvent event) {
        if (plugin.isShuttingDown()) {
            return false;
        }

        configLock.readLock().lock();
        try {
            if (!hasPermission(player, enchantment)) {
                return false;
            }

            // Check enchantment-specific cooldown with thread safety
            if (isOnEnchantmentCooldown(player, enchantment)) {
                return false;
            }

            EnchantmentConfig config = enchantmentConfigs.get(enchantment);
            if (config == null || !config.isEnabled()) {
                return false;
            }

            Map<String, Integer> enchantments = plugin.getToolManager().getToolEnchantments(tool);
            int level = enchantments.getOrDefault(enchantment, 0);

            if (level <= 0 || level > config.getMaxLevel()) {
                return false;
            }

            // Proper chance calculation from config
            double chance = getChanceForLevel(config, level);
            if (chance <= 0 || random.nextDouble() > chance) {
                return false;
            }

            // Check WorldGuard permissions for specific enchantment
            if (plugin.getWorldGuardHook() != null &&
                    !plugin.getWorldGuardHook().canUseEnchantment(player, event.getBlock().getLocation(), enchantment)) {
                return false;
            }

            // Set enchantment-specific cooldown with thread safety
            setEnchantmentCooldown(player, enchantment, config.getCooldown());

            // Enhanced enchantment triggering with proper block breaking
            boolean triggered = false;
            switch (enchantment) {
                case "explosive":
                    triggered = triggerExplosive(player, event, level, config);
                    break;
                case "speed":
                    triggered = triggerSpeed(player, level, config);
                    break;
                case "xpboost":
                    triggered = triggerXpBoost(player, event, level, config);
                    break;
                case "essenceboost":
                    triggered = triggerEssenceBoost(player, event, level, config);
                    break;
                case "meteor":
                    triggered = triggerMeteor(player, event, level, config);
                    break;
                case "airstrike":
                    triggered = triggerAirstrike(player, event, level, config);
                    break;
                case "haste":
                    // Haste is passive, always trigger when called
                    triggered = true;
                    break;
            }

            if (triggered) {
                // Send feedback and update stats
                updatePlayerStats(player, enchantment);
            }

            return triggered;

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error triggering enchantment " + enchantment + " for " + player.getName(), e);
            return false;
        } finally {
            configLock.readLock().unlock();
        }
    }

    /**
     * Explosive trigger with proper block breaking
     */
    private boolean triggerExplosive(Player player, BlockBreakEvent event, int level, EnchantmentConfig config) {
        try {
            int radius = getRadiusForLevel(config, level);

            // Play explosion sound immediately - FIXED: Lower volume
            playEnchantmentSound(player, config.getSound(), "explosive");

            // Always break blocks in perfect sphere - no dependency on ProtocolLib
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                if (!plugin.isShuttingDown()) {
                    plugin.getBlockBreaker().breakBlocksInRadius(
                            player, event.getBlock().getLocation(), radius, "explosive"
                    );
                }
            });

            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error triggering explosive enchantment", e);
            return false;
        }
    }

    /**
     * Enhanced speed trigger with NO sound (not in allowed list)
     */
    private boolean triggerSpeed(Player player, int level, EnchantmentConfig config) {
        try {
            List<Integer> durations = config.getSpeedDuration();
            List<Integer> amplifiers = config.getSpeedAmplifier();

            int duration = (durations != null && !durations.isEmpty()) ?
                    durations.get(Math.min(level - 1, durations.size() - 1)) * 20 : (5 + level * 2) * 20;

            int amplifier = (amplifiers != null && !amplifiers.isEmpty()) ?
                    amplifiers.get(Math.min(level - 1, amplifiers.size() - 1)) : Math.min(level - 1, 4);

            // Execute on main thread - NO SOUND for speed enchantment
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && !plugin.isShuttingDown()) {
                    player.addPotionEffect(new PotionEffect(
                            PotionEffectType.SPEED,
                            duration,
                            amplifier,
                            true,
                            false
                    ));
                    // NO SOUND - speed is not in SOUND_ENABLED_ENCHANTMENTS
                }
            });

            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error triggering speed enchantment", e);
            return false;
        }
    }

    private boolean triggerXpBoost(Player player, BlockBreakEvent event, int level, EnchantmentConfig config) {
        // This will be handled by the BlockBreakListener - just return true
        return true;
    }

    private boolean triggerEssenceBoost(Player player, BlockBreakEvent event, int level, EnchantmentConfig config) {
        // This will be handled by the BlockBreakListener - just return true
        return true;
    }

    /**
     * Meteor trigger with proper block breaking - works with or without ProtocolLib
     */
    private boolean triggerMeteor(Player player, BlockBreakEvent event, int level, EnchantmentConfig config) {
        try {
            if (!canStartAnimation(player, "meteor", config.getMaxActive())) {
                return false;
            }

            int radius = getRadiusForLevel(config, level);
            incrementAnimationCount(player, "meteor");

            // Play initial warning sound - FIXED: Lower volume
            playEnchantmentSound(player, "entity_wither_shoot", "meteor");

            // FIXED: Always break blocks regardless of ProtocolLib availability
            if (plugin.getProtocolLibHook() != null) {
                // Use ProtocolLib for visual effects if available
                plugin.getProtocolLibHook().spawnMeteor(
                        player,
                        event.getBlock().getLocation(),
                        radius,
                        config.getEntityType(),
                        config.getAnimation(),
                        config.getParticles(),
                        config.getSound(),
                        level,
                        getMeteorSizeForLevel(config, level),
                        () -> {
                            decrementAnimationCount(player, "meteor");
                            executeBlockBreaking(player, event.getBlock().getLocation(), radius, "meteor", config);
                        }
                );
            } else {
                // Fallback: Just break blocks with sound and particle effects
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline() && !plugin.isShuttingDown()) {
                        playEnchantmentSound(player, config.getSound(), "meteor");
                        decrementAnimationCount(player, "meteor");
                        executeBlockBreaking(player, event.getBlock().getLocation(), radius, "meteor", config);
                    }
                }, 60L); // 3 second delay for meteor effect
            }

            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error triggering meteor enchantment", e);
            decrementAnimationCount(player, "meteor");
            return false;
        }
    }

    /**
     * FIXED: Airstrike trigger with GUARANTEED animation and block breaking
     */
    private boolean triggerAirstrike(Player player, BlockBreakEvent event, int level, EnchantmentConfig config) {
        try {
            if (!canStartAnimation(player, "airstrike", config.getMaxActive())) {

                return false;
            }

            int radius = getRadiusForLevel(config, level);
            incrementAnimationCount(player, "airstrike");


            // Play warning siren sound immediately - FIXED: Lower volume
            playEnchantmentSound(player, "entity_lightning_bolt_thunder", "airstrike");

            // FIXED: GUARANTEED airstrike execution with proper fallback
            if (plugin.getProtocolLibHook() != null) {


                try {
                    plugin.getProtocolLibHook().spawnAirstrike(
                            player,
                            event.getBlock().getLocation(),
                            radius,
                            3 + level,
                            config.getAnimation(),
                            config.getParticles(),
                            config.getSound(),
                            () -> {

                                decrementAnimationCount(player, "airstrike");
                                executeAirstrikeEffects(player, event.getBlock().getLocation(), radius, config, level);
                            }
                    );
                } catch (Exception protocolError) {

                    // Fallback to manual execution
                    executeAirstrikeFallback(player, event.getBlock().getLocation(), radius, config, level);
                }
            } else {

                executeAirstrikeFallback(player, event.getBlock().getLocation(), radius, config, level);
            }

            return true;
        } catch (Exception e) {

            decrementAnimationCount(player, "airstrike");
            return false;
        }
    }

    /**
     * FIXED: Guaranteed airstrike fallback execution
     */
    private void executeAirstrikeFallback(Player player, org.bukkit.Location location, int radius, EnchantmentConfig config, int level) {


        // Create a timed sequence of effects to simulate airstrike
        new BukkitRunnable() {
            private int tickCount = 0;
            private final int maxTicks = 40; // 2 seconds
            private final int explosionCount = 3 + level;
            private int explosionsPlayed = 0;

            @Override
            public void run() {
                if (plugin.isShuttingDown() || !player.isOnline()) {
                    decrementAnimationCount(player, "airstrike");
                    this.cancel();
                    return;
                }

                tickCount++;

                // Play explosion sounds at intervals - FIXED: Lower volume
                if (tickCount % 10 == 0 && explosionsPlayed < explosionCount) {
                    playEnchantmentSound(player, config.getSound(), "airstrike");
                    explosionsPlayed++;

                }

                // End animation and execute effects
                if (tickCount >= maxTicks) {

                    decrementAnimationCount(player, "airstrike");
                    executeAirstrikeEffects(player, location, radius, config, level);
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * FIXED: Execute airstrike effects (sounds and block breaking)
     */
    private void executeAirstrikeEffects(Player player, org.bukkit.Location location, int radius, EnchantmentConfig config, int level) {

        // Play final explosion sounds - FIXED: Lower volume
        playMultipleExplosionSounds(player, config, level);

        // Break blocks in perfect sphere
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!plugin.isShuttingDown()) {

                plugin.getBlockBreaker().breakBlocksInRadius(player, location, radius, "airstrike");
            }
        });
    }

    /**
     * Execute block breaking - guaranteed to work
     */
    private void executeBlockBreaking(Player player, org.bukkit.Location location, int radius, String enchantmentType, EnchantmentConfig config) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!plugin.isShuttingDown()) {
                plugin.getBlockBreaker().breakBlocksInRadius(player, location, radius, enchantmentType);
            }
        });
    }

    /**
     * Play multiple explosion sounds for airstrike - FIXED: Lower volume
     */
    private void playMultipleExplosionSounds(Player player, EnchantmentConfig config, int level) {
        new BukkitRunnable() {
            private int soundCount = 0;
            private final int maxSounds = 3 + level;

            @Override
            public void run() {
                if (plugin.isShuttingDown() || !player.isOnline() || soundCount >= maxSounds) {
                    this.cancel();
                    return;
                }

                playEnchantmentSound(player, config.getSound(), "airstrike");
                soundCount++;

            }
        }.runTaskTimer(plugin, 10L, 15L);
    }

    /**
     * Apply passive Haste effect with thread safety for ALL tools - NO SOUND
     */
    public void applyHasteEffect(Player player, ItemStack tool) {
        if (!hasPermission(player, "haste") || plugin.isShuttingDown()) {
            return;
        }

        configLock.readLock().lock();
        try {
            Map<String, Integer> enchantments = plugin.getToolManager().getToolEnchantments(tool);
            int hasteLevel = enchantments.getOrDefault("haste", 0);

            if (hasteLevel > 0) {
                EnchantmentConfig config = enchantmentConfigs.get("haste");
                if (config != null && config.isEnabled()) {
                    List<Integer> hasteEffects = config.getHasteEffects();
                    if (hasteEffects != null && !hasteEffects.isEmpty()) {
                        int index = Math.max(0, Math.min(hasteLevel - 1, hasteEffects.size() - 1));
                        int effectLevel = hasteEffects.get(index);

                        // Execute on main thread - NO SOUND for haste
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (player.isOnline() && !plugin.isShuttingDown()) {
                                player.addPotionEffect(new PotionEffect(
                                        PotionEffectType.HASTE,
                                        60, // 3 seconds
                                        effectLevel - 1, // Effect levels start at 0
                                        true,
                                        false
                                ));
                            }
                        });
                    }
                }
            }
        } finally {
            configLock.readLock().unlock();
        }
    }

    /**
     * FIXED: Enhanced sound playing with validation, LOWER volume control, and enchantment filtering
     */
    private void playEnchantmentSound(Player player, String soundName, String enchantmentType) {
        // FIXED: Only play sounds for allowed enchantments
        if (!SOUND_ENABLED_ENCHANTMENTS.contains(enchantmentType)) {
            return;
        }

        if (player == null || !player.isOnline() || soundName == null || soundName.isEmpty()) {
            return;
        }

        // Execute on main thread to avoid AsyncCatcher
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                Sound sound = validatedSounds.get(soundName.toLowerCase());
                if (sound == null) {
                    // Try to parse the sound name directly
                    try {
                        sound = Sound.valueOf(soundName.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        sound = Sound.ENTITY_GENERIC_EXPLODE; // Fallback

                    }
                }

                // FIXED: Play sound with LOWER volume and pitch based on enchantment type
                float volume = getSoundVolume(enchantmentType);
                float pitch = getSoundPitch(enchantmentType);

                player.playSound(player.getLocation(), sound, volume, pitch);

                // Also play sound for nearby players for dramatic effect - LOWER volume
                if (enchantmentType.equals("meteor") || enchantmentType.equals("airstrike") || enchantmentType.equals("explosive")) {
                    playNearbySound(player, sound, volume * 0.5f, pitch); // FIXED: Even lower for nearby players
                }

            } catch (Exception e) {

            }
        });
    }

    // FIXED: Helper methods for LOWER sound volume management
    private float getSoundVolume(String enchantmentType) {
        switch (enchantmentType) {
            case "meteor":
                return 0.7f; // REDUCED from 1.5f
            case "airstrike":
                return 0.6f; // REDUCED from 1.3f
            case "explosive":
                return 0.5f; // REDUCED from 1.0f
            default:
                return 0.4f; // REDUCED default
        }
    }

    private float getSoundPitch(String enchantmentType) {
        switch (enchantmentType) {
            case "meteor":
                return 0.8f;
            case "airstrike":
                return 1.0f;
            case "explosive":
                return 1.2f;
            default:
                return 1.0f;
        }
    }

    private void playNearbySound(Player sourcePlayer, Sound sound, float baseVolume, float pitch) {
        try {
            final double maxDistance = 20.0; // REDUCED from 30.0

            for (Player nearbyPlayer : sourcePlayer.getWorld().getPlayers()) {
                if (nearbyPlayer.equals(sourcePlayer)) continue;

                double distance = nearbyPlayer.getLocation().distance(sourcePlayer.getLocation());
                if (distance <= maxDistance) {
                    float volume = (float) (baseVolume * (1.0 - (distance / maxDistance)));
                    if (volume > 0.05f) { // REDUCED minimum volume threshold
                        nearbyPlayer.playSound(sourcePlayer.getLocation(), sound, volume, pitch);
                    }
                }
            }
        } catch (Exception e) {

        }
    }

    // Animation and cooldown management methods
    private boolean canStartAnimation(Player player, String animationType, int maxActive) {
        ConcurrentHashMap<String, AtomicInteger> playerAnimations = activeAnimations.get(player);
        if (playerAnimations == null) {
            return true;
        }
        AtomicInteger count = playerAnimations.get(animationType);
        return count == null || count.get() < maxActive;
    }

    private void incrementAnimationCount(Player player, String animationType) {
        ConcurrentHashMap<String, AtomicInteger> playerAnimations =
                activeAnimations.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        AtomicInteger count = playerAnimations.computeIfAbsent(animationType, k -> new AtomicInteger(0));
        count.incrementAndGet();
    }

    private void decrementAnimationCount(Player player, String animationType) {
        ConcurrentHashMap<String, AtomicInteger> playerAnimations = activeAnimations.get(player);
        if (playerAnimations != null) {
            AtomicInteger count = playerAnimations.get(animationType);
            if (count != null && count.get() > 0) {
                count.decrementAndGet();
            }
        }
    }

    private boolean isOnEnchantmentCooldown(Player player, String enchantment) {
        if (player.hasPermission("ghasttools.bypass.cooldown")) {
            return false;
        }

        ConcurrentHashMap<String, Long> playerCooldowns = enchantmentCooldowns.get(player);
        if (playerCooldowns == null) {
            return false;
        }

        Long lastTrigger = playerCooldowns.get(enchantment);
        if (lastTrigger == null) {
            return false;
        }

        EnchantmentConfig config = enchantmentConfigs.get(enchantment);
        long cooldownMs = config != null ? config.getCooldown() * 1000L : 5000L;

        return System.currentTimeMillis() - lastTrigger < cooldownMs;
    }

    private void setEnchantmentCooldown(Player player, String enchantment, int cooldownSeconds) {
        ConcurrentHashMap<String, Long> playerCooldowns =
                enchantmentCooldowns.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        playerCooldowns.put(enchantment, System.currentTimeMillis());
        globalCooldowns.put(player, System.currentTimeMillis());
    }

    private void updatePlayerStats(Player player, String enchantment) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (!plugin.isShuttingDown()) {
                    PlayerData playerData = plugin.getDataManager().loadPlayerData(player.getUniqueId()).join();

                    playerData.setLastEnchantUsed(enchantment);
                    playerData.incrementEnchantmentUsage(enchantment);

                    if (enchantment.equals("meteor")) {
                        playerData.addMeteorSpawned();
                    } else if (enchantment.equals("airstrike")) {
                        playerData.addAirstrike();
                    }

                    plugin.getDataManager().savePlayerData(player.getUniqueId(), playerData);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error updating player stats for " + player.getName(), e);
            }
        });
    }

    private double getChanceForLevel(EnchantmentConfig config, int level) {
        List<Double> chances = config.getChances();
        if (chances == null || chances.isEmpty()) {
            return 0.0;
        }
        int index = Math.max(0, Math.min(level - 1, chances.size() - 1));
        return chances.get(index);
    }

    private int getRadiusForLevel(EnchantmentConfig config, int level) {
        List<Integer> radiusList = config.getRadius();
        if (radiusList == null || radiusList.isEmpty()) {
            return 5; // Default radius
        }
        int index = Math.max(0, Math.min(level - 1, radiusList.size() - 1));
        return radiusList.get(index);
    }

    private int getMeteorSizeForLevel(EnchantmentConfig config, int level) {
        List<Integer> meteorSizes = config.getMeteorSize();
        if (meteorSizes == null || meteorSizes.isEmpty()) {
            return level; // Default to level as size
        }
        int index = Math.max(0, Math.min(level - 1, meteorSizes.size() - 1));
        return meteorSizes.get(index);
    }

    private boolean hasPermission(Player player, String enchantment) {
        if (player.hasPermission("ghasttools.bypass.worldguard")) {
            return true;
        }
        return player.hasPermission("ghasttools.enchant." + enchantment);
    }

    /**
     * Public accessors with thread safety
     */
    public List<String> getAllEnchantmentNames() {
        configLock.readLock().lock();
        try {
            return new ArrayList<>(enchantmentConfigs.keySet());
        } finally {
            configLock.readLock().unlock();
        }
    }

    public EnchantmentConfig getEnchantmentConfig(String enchantmentName) {
        configLock.readLock().lock();
        try {
            return enchantmentConfigs.get(enchantmentName);
        } finally {
            configLock.readLock().unlock();
        }
    }

    public Map<String, EnchantmentConfig> getEnchantmentConfigs() {
        configLock.readLock().lock();
        try {
            return new HashMap<>(enchantmentConfigs);
        } finally {
            configLock.readLock().unlock();
        }
    }

    /**
     * Enhanced cleanup with proper thread safety
     */
    public void cleanup() {
        configLock.writeLock().lock();
        try {
            // Clear all tracking data
            activeAnimations.clear();
            enchantmentCooldowns.clear();
            globalCooldowns.clear();
            validatedSounds.clear();

            plugin.getLogger().info("EnchantmentManager cleaned up successfully");
        } finally {
            configLock.writeLock().unlock();
        }
    }

    // Configuration class for enchantments
    public static class EnchantmentConfig {
        private boolean enabled = true;
        private int maxLevel = 1;
        private List<Double> chances;
        private int cooldown = 0;
        private List<Integer> radius;
        private String entityType = "falling_block";
        private String animation = "linear";
        private int maxActive = 2;
        private String particles = "explosion";
        private String sound = "entity_generic_explode";
        private List<Integer> hasteEffects;
        private List<String> materials;
        private List<Integer> customModelData;
        private List<Integer> meteorSize;
        private List<Integer> speedDuration;
        private List<Integer> speedAmplifier;
        private List<Double> multiplier;
        private String description = "";

        // Getters and setters
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxLevel() {
            return maxLevel;
        }

        public void setMaxLevel(int maxLevel) {
            this.maxLevel = maxLevel;
        }

        public List<Double> getChances() {
            return chances;
        }

        public void setChances(List<Double> chances) {
            this.chances = chances;
        }

        public int getCooldown() {
            return cooldown;
        }

        public void setCooldown(int cooldown) {
            this.cooldown = cooldown;
        }

        public List<Integer> getRadius() {
            return radius;
        }

        public void setRadius(List<Integer> radius) {
            this.radius = radius;
        }

        public String getEntityType() {
            return entityType;
        }

        public void setEntityType(String entityType) {
            this.entityType = entityType;
        }

        public String getAnimation() {
            return animation;
        }

        public void setAnimation(String animation) {
            this.animation = animation;
        }

        public int getMaxActive() {
            return maxActive;
        }

        public void setMaxActive(int maxActive) {
            this.maxActive = maxActive;
        }

        public String getParticles() {
            return particles;
        }

        public void setParticles(String particles) {
            this.particles = particles;
        }

        public String getSound() {
            return sound;
        }

        public void setSound(String sound) {
            this.sound = sound;
        }

        public List<Integer> getHasteEffects() {
            return hasteEffects;
        }

        public void setHasteEffects(List<Integer> hasteEffects) {
            this.hasteEffects = hasteEffects;
        }

        public List<String> getMaterials() {
            return materials;
        }

        public void setMaterials(List<String> materials) {
            this.materials = materials;
        }

        public List<Integer> getCustomModelData() {
            return customModelData;
        }

        public void setCustomModelData(List<Integer> customModelData) {
            this.customModelData = customModelData;
        }

        public List<Integer> getMeteorSize() {
            return meteorSize;
        }

        public void setMeteorSize(List<Integer> meteorSize) {
            this.meteorSize = meteorSize;
        }

        public List<Integer> getSpeedDuration() {
            return speedDuration;
        }

        public void setSpeedDuration(List<Integer> speedDuration) {
            this.speedDuration = speedDuration;
        }

        public List<Integer> getSpeedAmplifier() {
            return speedAmplifier;
        }

        public void setSpeedAmplifier(List<Integer> speedAmplifier) {
            this.speedAmplifier = speedAmplifier;
        }

        public List<Double> getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(List<Double> multiplier) {
            this.multiplier = multiplier;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}