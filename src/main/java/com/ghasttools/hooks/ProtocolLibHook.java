package com.ghasttools.hooks;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.ghasttools.GhastToolsPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;

/**
 * FIXED: ProtocolLib integration with invisible armor stands for meteors and minimal particles
 */
public class ProtocolLibHook {

    private final GhastToolsPlugin plugin;
    private final ProtocolManager protocolManager;
    private final Random random = new Random();
    private final ReentrantReadWriteLock animationLock = new ReentrantReadWriteLock();

    // Enhanced thread-safe tracking with proper synchronization
    private final ConcurrentHashMap<Player, AtomicInteger> packetCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Player, AtomicLong> lastPacketTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Player, AtomicInteger> activeAnimations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Player> entityOwners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Player, java.util.Set<Integer>> playerEntities = new ConcurrentHashMap<>();

    // Enhanced sound tracking to prevent audio spam
    private final ConcurrentHashMap<Player, AtomicInteger> activeSounds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Player, AtomicLong> lastSoundTime = new ConcurrentHashMap<>();

    // Configuration constants
    private static final int MAX_PACKETS_PER_SECOND = 20; // REDUCED for better performance
    private static final int MAX_ANIMATIONS_PER_PLAYER = 2; // REDUCED for performance
    private static final int MAX_SOUNDS_PER_PLAYER = 3; // REDUCED to prevent audio spam
    private static final long PACKET_RESET_INTERVAL = 1000;
    private static final long SOUND_RESET_INTERVAL = 2000;
    private static final long ANIMATION_TIMEOUT = 15000; // REDUCED timeout
    private static final int ENTITY_ID_START = 1000000;
    private static final int ENTITY_ID_MAX = 2000000;

    // Enhanced entity ID management with thread safety
    private final AtomicInteger entityIdCounter = new AtomicInteger(ENTITY_ID_START);

    public ProtocolLibHook(GhastToolsPlugin plugin) {
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
    }

    /**
     * Initialize ProtocolLib hook with enhanced cleanup and monitoring
     */
    public void initialize() {
        // Start comprehensive cleanup task
        BukkitTask cleanupTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.isShuttingDown()) {
                    cleanupPacketCounts();
                    cleanupExpiredAnimations();
                    cleanupOrphanedEntities();
                    cleanupSoundTracking();

                    // Log performance metrics every 5 minutes
                    if (System.currentTimeMillis() % 300000 < 5000) {
                        logPerformanceMetrics();
                    }
                }
            }
        }.runTaskTimerAsynchronously(plugin, 20L, 100L); // Every 5 seconds

        plugin.registerTask("protocollib-cleanup", cleanupTask);
        plugin.getLogger().info("ProtocolLib integration initialized with enhanced performance monitoring");
    }

    /**
     * FIXED: Spawn single explosive TNT for explosive enchantment (WORKS EXACTLY LIKE AIRSTRIKE)
     */
    public void spawnSingleExplosiveTNT(Player player, Location target, String particles, String sound, Runnable callback) {
        if (!canStartAnimation(player) || !validateParameters(player, target)) {

            if (callback != null) callback.run();
            return;
        }

        animationLock.writeLock().lock();
        try {
            // Calculate spawn location above target (same as airstrike)
            Location spawnLocation = target.clone().add(0, 20, 0); // Spawn 20 blocks above

            // Increment active animation count with thread safety
            activeAnimations.computeIfAbsent(player, k -> new AtomicInteger(0)).incrementAndGet();
            final int currentEntityId = getNextEntityId();

            // Track entity ownership
            entityOwners.put(currentEntityId, player);
            playerEntities.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(currentEntityId);

            // FIXED: TNT animation with proper timing and sound effects (EXACTLY LIKE AIRSTRIKE)
            BukkitTask tntTask = new BukkitRunnable() {
                private int ticks = 0;
                private final int maxTicks = 40; // 2 seconds fall time (same as airstrike)
                private final long startTime = System.currentTimeMillis();
                private boolean hasPlayedImpactSound = false;

                @Override
                public void run() {
                    try {
                        // Enhanced safety checks with timeout
                        if (shouldCancelAnimation(player, ticks, maxTicks, startTime)) {
                            cleanupTNTAnimation(player, currentEntityId, target, particles, sound, callback);
                            this.cancel();
                            return;
                        }

                        // Calculate current position with improved easing (SAME AS AIRSTRIKE)
                        Location currentPos = calculateTNTPosition(spawnLocation, target, ticks, maxTicks);

                        // Optimized entity spawning with packet limiting
                        if (ticks % 2 == 0 && canSendPacket(player)) {
                            spawnFakeTNT(player, currentPos, currentEntityId);
                        }

                        // Enhanced sound timing for TNT
                        double progress = (double) ticks / maxTicks;
                        if (!hasPlayedImpactSound && progress > 0.8) {
                            playSound(player, Sound.ENTITY_TNT_PRIMED, 0.4f, 1.1f, "explosive_impact"); // REDUCED volume
                            hasPlayedImpactSound = true;
                        }

                        // FIXED: Add MINIMAL trail particles (SAME AS AIRSTRIKE)
                        if (ticks % 8 == 0) { // REDUCED frequency
                            spawnMinimalTNTParticles(player, currentPos);
                        }

                        ticks++;

                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING, "Error in explosive TNT animation", e);
                        cleanupTNTAnimation(player, currentEntityId, target, particles, sound, callback);
                        this.cancel();
                    }
                }
            }.runTaskTimerAsynchronously(plugin, 0L, 1L);

            plugin.registerTask("explosive-tnt-" + currentEntityId, tntTask);

        } finally {
            animationLock.writeLock().unlock();
        }
    }

    /**
     * Calculate TNT position with smooth falling animation (SAME AS AIRSTRIKE)
     */
    private Location calculateTNTPosition(Location start, Location target, int ticks, int maxTicks) {
        double progress = (double) ticks / maxTicks;
        progress = easeInQuart(progress); // Smooth acceleration

        return start.clone().add(
                (target.getX() - start.getX()) * progress,
                (target.getY() - start.getY()) * progress,
                (target.getZ() - start.getZ()) * progress
        );
    }

    /**
     * FIXED: Enhanced meteor effect with ARMOR STAND instead of falling blocks and MINIMAL particles
     */
    public void spawnMeteor(Player player, Location target, int radius, String entityType,
                            String animation, String particles, String sound, int level, int meteorSize, Runnable callback) {

        if (!canStartAnimation(player) || !validateParameters(player, target)) {
            if (callback != null) callback.run();
            return;
        }

        animationLock.writeLock().lock();
        try {
            // Calculate spawn location with proper bounds checking
            Location spawnLocation = calculateSpawnLocation(target, radius);

            // Get configurable meteor properties
            Material meteorMaterial = getMeteorMaterial(level);
            int customModelData = getMeteorCustomModelData(level);

            // Increment active animation count with thread safety
            activeAnimations.computeIfAbsent(player, k -> new AtomicInteger(0)).incrementAndGet();


            // FIXED: Create invisible armor stand with custom item on head
            spawnMeteorArmorStand(player, spawnLocation, target, meteorMaterial, customModelData,
                    animation, particles, sound, level, meteorSize, callback);

        } finally {
            animationLock.writeLock().unlock();
        }
    }

    /**
     * FIXED: Spawn meteor as invisible armor stand with custom item on head
     */
    private void spawnMeteorArmorStand(Player player, Location spawnLocation, Location target,
                                       Material meteorMaterial, int customModelData, String animation,
                                       String particles, String sound, int level, int meteorSize, Runnable callback) {

        // Execute on main thread for entity spawning
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                // Create invisible armor stand
                ArmorStand meteorArmorStand = (ArmorStand) spawnLocation.getWorld().spawnEntity(spawnLocation, EntityType.ARMOR_STAND);

                // Make armor stand invisible and disable interactions
                meteorArmorStand.setVisible(false);
                meteorArmorStand.setGravity(false); // We'll control movement manually
                meteorArmorStand.setInvulnerable(true);
                meteorArmorStand.setBasePlate(false);
                meteorArmorStand.setArms(false);
                meteorArmorStand.setSmall(true);
                meteorArmorStand.setMarker(true); // Make it non-collidable

                // FIXED: Create custom item for armor stand head
                ItemStack meteorItem = new ItemStack(meteorMaterial);
                ItemMeta meta = meteorItem.getItemMeta();
                if (meta != null && customModelData > 0) {
                    meta.setCustomModelData(customModelData);
                    meteorItem.setItemMeta(meta);
                }

                // Put custom item on armor stand head
                meteorArmorStand.getEquipment().setHelmet(meteorItem);


                // FIXED: Animate the armor stand falling down
                animateMeteorArmorStand(player, meteorArmorStand, spawnLocation, target, animation,
                        particles, sound, level, meteorSize, callback);

            } catch (Exception e) {

                decrementAnimationCount(player);
                if (callback != null) callback.run();
            }
        });
    }

    /**
     * FIXED: Animate meteor armor stand falling with minimal particles
     */
    private void animateMeteorArmorStand(Player player, ArmorStand armorStand, Location start, Location target,
                                         String animation, String particles, String sound, int level,
                                         int meteorSize, Runnable callback) {

        BukkitTask meteorTask = new BukkitRunnable() {
            private int ticks = 0;
            private final int maxTicks = calculateAnimationDuration(10, "meteor");
            private final long startTime = System.currentTimeMillis();
            private boolean hasPlayedWarningSound = false;
            private boolean hasPlayedImpactSound = false;

            @Override
            public void run() {
                try {
                    // Enhanced safety checks
                    if (shouldCancelAnimation(player, ticks, maxTicks, startTime) || armorStand.isDead()) {
                        cleanupMeteorArmorStand(player, armorStand, target, particles, sound, callback);
                        this.cancel();
                        return;
                    }

                    // Calculate current position with improved easing
                    Location currentPos = calculateMeteorPosition(start, target, animation, ticks, maxTicks);

                    // Move armor stand to current position
                    armorStand.teleport(currentPos);

                    // Enhanced sound timing for meteor sequence
                    handleMeteorSounds(player, ticks, maxTicks);

                    // FIXED: MINIMAL particle effects with much lower frequency
                    if (ticks % 10 == 0) { // REDUCED from 3 to 10
                        spawnMinimalMeteorParticles(player, currentPos, meteorSize);
                    }

                    // Check if meteor hit the ground
                    if (currentPos.getY() <= target.getY() + 1) {

                        cleanupMeteorArmorStand(player, armorStand, target, particles, sound, callback);
                        this.cancel();
                        return;
                    }

                    ticks++;

                } catch (Exception e) {

                    cleanupMeteorArmorStand(player, armorStand, target, particles, sound, callback);
                    this.cancel();
                }
            }

            /**
             * Enhanced sound timing for meteor sequence with LOWER volumes
             */
            private void handleMeteorSounds(Player player, int ticks, int maxTicks) {
                double progress = (double) ticks / maxTicks;

                // Play warning sound at start
                if (!hasPlayedWarningSound && ticks < 10) {
                    playSound(player, Sound.ENTITY_WITHER_SHOOT, 0.3f, 0.7f, "meteor_warning"); // REDUCED volume
                    hasPlayedWarningSound = true;
                }

                // Play whoosh sounds during flight
                if (progress > 0.3 && progress < 0.9 && ticks % 30 == 0) { // REDUCED frequency
                    playSound(player, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.2f, 1.2f, "meteor_flight"); // REDUCED volume
                }

                // Play impact sound near the end
                if (!hasPlayedImpactSound && progress > 0.85) {
                    playSound(player, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 0.5f, 0.8f, "meteor_impact"); // REDUCED volume
                    hasPlayedImpactSound = true;
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        plugin.registerTask("meteor-armorstand-" + armorStand.getEntityId(), meteorTask);
    }

    /**
     * FIXED: Cleanup meteor armor stand and despawn it
     */
    private void cleanupMeteorArmorStand(Player player, ArmorStand armorStand, Location target,
                                         String particles, String sound, Runnable callback) {

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                // Despawn the armor stand
                if (!armorStand.isDead()) {
                    armorStand.remove();
                }

                // Spawn minimal impact effects
                if (player.isOnline() && !plugin.isShuttingDown()) {
                    spawnMinimalImpactEffects(player, target, particles);
                }

                decrementAnimationCount(player);

                if (callback != null) callback.run();

            } catch (Exception e) {

                decrementAnimationCount(player);
                if (callback != null) callback.run();
            }
        });

        plugin.unregisterTask("meteor-armorstand-" + armorStand.getEntityId());
    }

    /**
     * Enhanced airstrike effect with proper sound sequencing and staggered impacts
     */
    public void spawnAirstrike(Player player, Location target, int radius, int tntCount,
                               String animation, String particles, String sound, Runnable callback) {

        if (!canStartAnimation(player) || !validateParameters(player, target)) {

            if (callback != null) callback.run();
            return;
        }

        animationLock.writeLock().lock();
        try {
            // Limit TNT count for performance
            tntCount = Math.min(tntCount, 4); // REDUCED from 6
            Location[] spawnLocations = calculateAirstrikePositions(target, radius, tntCount, animation);
            AtomicInteger completedTNT = new AtomicInteger(0);

            activeAnimations.computeIfAbsent(player, k -> new AtomicInteger(0)).incrementAndGet();

            // Play initial warning siren with LOWER volume
            playSound(player, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.4f, 1.5f, "airstrike_warning"); // REDUCED volume

            // Staggered TNT deployment with sound effects
            for (int i = 0; i < spawnLocations.length; i++) {
                final int index = i;
                final Location spawnLoc = spawnLocations[i];

                int finalTntCount = tntCount;
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (!plugin.isShuttingDown() && player.isOnline()) {
                        // Play deployment sound for each TNT with LOWER volume
                        playSound(player, Sound.ENTITY_TNT_PRIMED, 0.3f, 1.3f, "airstrike_deploy"); // REDUCED volume

                        spawnSingleTNT(player, spawnLoc, target, particles, sound, () -> {
                            if (completedTNT.incrementAndGet() >= spawnLocations.length) {
                                // All TNTs completed
                                AtomicInteger count = activeAnimations.get(player);
                                if (count != null) {
                                    count.decrementAndGet();
                                }

                                // Play final explosion sequence with LOWER volume
                                playExplosionSequence(player, finalTntCount);

                                if (callback != null) callback.run();
                            }
                        });
                    }
                }, i * 10L); // Slightly longer delay for dramatic effect
            }

        } finally {
            animationLock.writeLock().unlock();
        }
    }

    /**
     * Play explosion sequence for airstrike finale with LOWER volumes
     */
    private void playExplosionSequence(Player player, int explosionCount) {
        new BukkitRunnable() {
            private int count = 0;
            private final int maxExplosions = Math.min(explosionCount, 3); // REDUCED

            @Override
            public void run() {
                if (plugin.isShuttingDown() || !player.isOnline() || count >= maxExplosions) {
                    this.cancel();
                    return;
                }

                // Vary the explosion sounds for variety with LOWER volumes
                Sound explosionSound = (count % 2 == 0) ?
                        Sound.ENTITY_GENERIC_EXPLODE : Sound.ENTITY_DRAGON_FIREBALL_EXPLODE;
                float pitch = 0.8f + (count * 0.1f); // Increase pitch slightly each time

                playSound(player, explosionSound, 0.4f, pitch, "airstrike_explosion"); // REDUCED volume
                count++;
            }
        }.runTaskTimer(plugin, 20L, 15L); // Start after 1 second, repeat every 0.75 seconds
    }

    /**
     * Enhanced single TNT spawning with proper entity tracking and sound timing
     */
    private void spawnSingleTNT(Player player, Location spawn, Location target,
                                String particles, String sound, Runnable callback) {

        final int currentEntityId = getNextEntityId();

        // Track entity ownership
        entityOwners.put(currentEntityId, player);
        playerEntities.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(currentEntityId);

        BukkitTask tntTask = new BukkitRunnable() {
            private int ticks = 0;
            private final int maxTicks = calculateAnimationDuration(10, "airstrike");
            private final long startTime = System.currentTimeMillis();
            private boolean hasPlayedImpactSound = false;

            @Override
            public void run() {
                try {
                    if (shouldCancelAnimation(player, ticks, maxTicks, startTime)) {
                        cleanupTNTAnimation(player, currentEntityId, target, particles, sound, callback);
                        this.cancel();
                        return;
                    }

                    // Calculate position with enhanced easing
                    double progress = easeInQuart((double) ticks / maxTicks);
                    Location currentPos = spawn.clone().add(
                            (target.getX() - spawn.getX()) * progress,
                            (target.getY() - spawn.getY()) * progress,
                            (target.getZ() - spawn.getZ()) * progress
                    );

                    // Spawn TNT entity with packet limiting
                    if (ticks % 3 == 0 && canSendPacket(player)) {
                        spawnFakeTNT(player, currentPos, currentEntityId);
                    }

                    // Enhanced sound timing for TNT with LOWER volume
                    if (!hasPlayedImpactSound && progress > 0.9) {
                        playSound(player, Sound.ENTITY_TNT_PRIMED, 0.3f, 1.1f, "tnt_impact"); // REDUCED volume
                        hasPlayedImpactSound = true;
                    }

                    // FIXED: Add MINIMAL trail particles
                    if (ticks % 10 == 0) { // REDUCED frequency
                        spawnMinimalTNTParticles(player, currentPos);
                    }

                    ticks++;

                } catch (Exception e) {

                    cleanupTNTAnimation(player, currentEntityId, target, particles, sound, callback);
                    this.cancel();
                }
            }
        }.runTaskTimerAsynchronously(plugin, 0L, 1L);

        plugin.registerTask("tnt-" + currentEntityId, tntTask);
    }

    /**
     * Enhanced sound playing with proper validation and anti-spam with LOWER volumes
     */
    private void playSound(Player player, Sound sound, float volume, float pitch, String soundType) {
        if (!canPlaySound(player)) {
            return;
        }

        // Track sound to prevent spam
        activeSounds.computeIfAbsent(player, k -> new AtomicInteger(0)).incrementAndGet();
        lastSoundTime.put(player, new AtomicLong(System.currentTimeMillis()));

        // Execute on main thread to avoid AsyncCatcher
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                if (player.isOnline() && !plugin.isShuttingDown()) {
                    player.playSound(player.getLocation(), sound, volume, pitch);

                    // Play for nearby players based on sound type with LOWER volume
                    if (isGlobalSound(soundType)) {
                        playNearbySound(player, sound, volume * 0.3f, pitch, soundType); // REDUCED from 0.6f
                    }
                }
            } catch (Exception e) {
                // Silently handle sound errors
            }
        });
    }

    /**
     * Check if sound should be played globally
     */
    private boolean isGlobalSound(String soundType) {
        return soundType.contains("meteor") || soundType.contains("airstrike") || soundType.contains("explosion") || soundType.contains("explosive");
    }

    /**
     * Enhanced nearby sound playing with distance-based volume and type filtering
     */
    private void playNearbySound(Player sourcePlayer, Sound sound, float baseVolume, float pitch, String soundType) {
        try {
            double maxDistance = getMaxSoundDistance(soundType);

            for (Player nearbyPlayer : sourcePlayer.getWorld().getPlayers()) {
                if (nearbyPlayer.equals(sourcePlayer) || !canPlaySound(nearbyPlayer)) continue;

                double distance = nearbyPlayer.getLocation().distance(sourcePlayer.getLocation());
                if (distance <= maxDistance) {
                    // Calculate volume with proper falloff
                    float volume = (float) (baseVolume * Math.pow(1.0 - (distance / maxDistance), 2));
                    if (volume > 0.05f) { // REDUCED minimum threshold
                        nearbyPlayer.playSound(sourcePlayer.getLocation(), sound, volume, pitch);
                    }
                }
            }
        } catch (Exception e) {

        }
    }

    /**
     * Get maximum sound distance based on sound type - REDUCED distances
     */
    private double getMaxSoundDistance(String soundType) {
        if (soundType.contains("meteor")) {
            return 30.0; // REDUCED from 50.0
        } else if (soundType.contains("airstrike")) {
            return 25.0; // REDUCED from 40.0
        } else if (soundType.contains("explosion") || soundType.contains("explosive")) {
            return 20.0; // REDUCED from 35.0
        } else {
            return 15.0; // REDUCED default range
        }
    }

    /**
     * Enhanced sound spam prevention
     */
    private boolean canPlaySound(Player player) {
        if (!player.isOnline()) {
            return false;
        }

        AtomicInteger soundCount = activeSounds.get(player);
        if (soundCount != null && soundCount.get() >= MAX_SOUNDS_PER_PLAYER) {
            return false; // Too many active sounds
        }

        AtomicLong lastTime = lastSoundTime.get(player);
        if (lastTime != null) {
            long timeSinceLastSound = System.currentTimeMillis() - lastTime.get();
            return timeSinceLastSound >= 200; // INCREASED minimum time between sounds
        }

        return true;
    }

    /**
     * Enhanced parameter validation
     */
    private boolean validateParameters(Player player, Location target) {
        return player != null && player.isOnline() &&
                target != null && target.getWorld() != null &&
                !plugin.isShuttingDown();
    }

    /**
     * Calculate appropriate spawn location with bounds checking
     */
    private Location calculateSpawnLocation(Location target, int radius) {
        int spawnHeight = Math.max(30, radius * 3); // REDUCED height
        spawnHeight = Math.min(spawnHeight, 80); // REDUCED cap

        return target.clone().add(0, spawnHeight, 0);
    }

    /**
     * Calculate animation duration based on type and radius
     */
    private int calculateAnimationDuration(int radius, String animationType) {
        switch (animationType) {
            case "meteor":
                return 60 + (radius * 2); // REDUCED duration
            case "airstrike":
                return 30 + radius; // REDUCED duration
            default:
                return 40;
        }
    }

    /**
     * Enhanced animation cancellation conditions
     */
    private boolean shouldCancelAnimation(Player player, int ticks, int maxTicks, long startTime) {
        return ticks >= maxTicks ||
                !player.isOnline() ||
                plugin.isReloading() ||
                plugin.isShuttingDown() ||
                System.currentTimeMillis() - startTime > ANIMATION_TIMEOUT;
    }

    /**
     * Animation control methods (thread-safe)
     */
    private boolean canStartAnimation(Player player) {
        AtomicInteger count = activeAnimations.get(player);
        return count == null || count.get() < MAX_ANIMATIONS_PER_PLAYER;
    }

    /**
     * Enhanced packet spam prevention with per-player tracking
     */
    private boolean canSendPacket(Player player) {
        if (!player.isOnline()) {
            return false;
        }

        long currentTime = System.currentTimeMillis();
        AtomicInteger count = packetCounts.computeIfAbsent(player, k -> new AtomicInteger(0));
        AtomicLong lastTime = lastPacketTime.computeIfAbsent(player, k -> new AtomicLong(currentTime));

        if (currentTime - lastTime.get() >= PACKET_RESET_INTERVAL) {
            count.set(0);
            lastTime.set(currentTime);
        }

        return count.incrementAndGet() <= MAX_PACKETS_PER_SECOND;
    }

    /**
     * Enhanced cleanup methods with comprehensive tracking cleanup
     */
    private void cleanupPacketCounts() {
        packetCounts.entrySet().removeIf(entry -> !entry.getKey().isOnline());
        lastPacketTime.entrySet().removeIf(entry -> !entry.getKey().isOnline());
    }

    private void cleanupExpiredAnimations() {
        activeAnimations.entrySet().removeIf(entry -> !entry.getKey().isOnline());
    }

    private void cleanupOrphanedEntities() {
        entityOwners.entrySet().removeIf(entry -> !entry.getValue().isOnline());
        playerEntities.entrySet().removeIf(entry -> !entry.getKey().isOnline());
    }

    /**
     * Sound tracking cleanup
     */
    private void cleanupSoundTracking() {
        long currentTime = System.currentTimeMillis();

        // Reset sound counts every 2 seconds
        lastSoundTime.entrySet().removeIf(entry -> {
            if (!entry.getKey().isOnline()) {
                return true;
            }
            if (currentTime - entry.getValue().get() >= SOUND_RESET_INTERVAL) {
                AtomicInteger count = activeSounds.get(entry.getKey());
                if (count != null) {
                    count.set(0);
                }
            }
            return false;
        });

        activeSounds.entrySet().removeIf(entry -> !entry.getKey().isOnline());
    }

    /**
     * Performance metrics logging
     */
    private void logPerformanceMetrics() {
        try {
            int totalAnimations = activeAnimations.values().stream().mapToInt(AtomicInteger::get).sum();
            int totalEntities = entityOwners.size();
            int totalSounds = activeSounds.values().stream().mapToInt(AtomicInteger::get).sum();


        } catch (Exception e) {
            plugin.getLogger().fine("Error logging performance metrics: " + e.getMessage());
        }
    }

    // FIXED: Entity spawning methods with proper falling block support
    private void spawnFakeTNT(Player player, Location location, int entityId) {
        if (!canSendPacket(player)) return;

        try {
            PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
            packet.getIntegers().write(0, entityId);
            packet.getUUIDs().write(0, java.util.UUID.randomUUID());
            packet.getEntityTypeModifier().write(0, EntityType.TNT);
            packet.getDoubles().write(0, location.getX());
            packet.getDoubles().write(1, location.getY());
            packet.getDoubles().write(2, location.getZ());

            // Add proper velocity for falling TNT
            packet.getIntegers().write(1, 0); // Velocity X
            packet.getIntegers().write(2, -150); // Falling velocity
            packet.getIntegers().write(3, 0); // Velocity Z

            protocolManager.sendServerPacket(player, packet);

        } catch (Exception e) {

        }
    }

    // Cleanup and animation methods
    private void cleanupTNTAnimation(Player player, int entityId, Location target,
                                     String particles, String sound, Runnable callback) {
        removeEntity(player, entityId);
        cleanupEntityTracking(player, entityId);

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!plugin.isShuttingDown() && player.isOnline()) {
                spawnMinimalImpactEffects(player, target, particles);
            }
            if (callback != null) callback.run();
        });

        plugin.unregisterTask("tnt-" + entityId);
        plugin.unregisterTask("explosive-tnt-" + entityId);
    }

    private void cleanupEntityTracking(Player player, int entityId) {
        entityOwners.remove(entityId);
        java.util.Set<Integer> playerEntitySet = playerEntities.get(player);
        if (playerEntitySet != null) {
            playerEntitySet.remove(entityId);
        }
    }

    private void decrementAnimationCount(Player player) {
        AtomicInteger count = activeAnimations.get(player);
        if (count != null) {
            count.decrementAndGet();
        }
    }

    private void removeEntity(Player player, int entityId) {
        if (!player.isOnline()) return;

        try {
            PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
            packet.getIntegerArrays().write(0, new int[]{entityId});
            protocolManager.sendServerPacket(player, packet);
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to remove entity: " + e.getMessage());
        }
    }

    // FIXED: MINIMAL particle methods with greatly reduced particle counts
    private void spawnMinimalMeteorParticles(Player player, Location location, int meteorSize) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && !plugin.isShuttingDown()) {
                try {
                    int particleCount = Math.min(meteorSize * 2, 8); // GREATLY REDUCED
                    double spread = Math.min(meteorSize * 0.3, 1.0); // REDUCED spread

                    player.spawnParticle(Particle.FLAME, location, particleCount, spread, spread, spread, 0.05);
                    player.spawnParticle(Particle.SMOKE, location, particleCount / 2, spread / 2, spread / 2, spread / 2, 0.02);
                } catch (Exception e) {

                }
            }
        });
    }

    private void spawnMinimalTNTParticles(Player player, Location location) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && !plugin.isShuttingDown()) {
                try {
                    player.spawnParticle(Particle.SMOKE, location, 3, 0.3, 0.3, 0.3, 0.02); // GREATLY REDUCED
                    player.spawnParticle(Particle.CLOUD, location, 2, 0.2, 0.2, 0.2, 0.01); // GREATLY REDUCED
                } catch (Exception e) {

                }
            }
        });
    }

    private void spawnMinimalImpactEffects(Player player, Location location, String particles) {
        try {
            Particle particle;
            try {
                particle = Particle.valueOf(particles.toUpperCase());
            } catch (IllegalArgumentException e) {
                particle = Particle.EXPLOSION;
            }

            // GREATLY REDUCED impact particles
            player.spawnParticle(particle, location, 15, 2, 2, 2, 0.1); // REDUCED from 50
            player.spawnParticle(Particle.SMOKE, location, 8, 1.5, 1.5, 1.5, 0.05); // REDUCED from 30
        } catch (Exception e) {

        }
    }

    // Helper methods
    private Material getMeteorMaterial(int level) {
        try {
            var pickaxeConfig = plugin.getConfigManager().getPickaxeConfig();
            if (pickaxeConfig == null) {
                return Material.MAGMA_BLOCK;
            }

            List<String> materials = pickaxeConfig.getStringList("enchantments.meteor.materials");
            if (materials.isEmpty()) {
                return Material.MAGMA_BLOCK;
            }

            int index = Math.min(level - 1, materials.size() - 1);
            index = Math.max(0, index);

            String materialName = materials.get(index);
            return Material.valueOf(materialName.toUpperCase());

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error getting meteor material for level " + level, e);
            return Material.MAGMA_BLOCK;
        }
    }

    private int getMeteorCustomModelData(int level) {
        try {
            var pickaxeConfig = plugin.getConfigManager().getPickaxeConfig();
            if (pickaxeConfig == null) {
                return 3000 + level;
            }

            List<Integer> customModelData = pickaxeConfig.getIntegerList("enchantments.meteor.custom_model_data");
            if (customModelData.isEmpty()) {
                return 3000 + level;
            }

            int index = Math.min(level - 1, customModelData.size() - 1);
            index = Math.max(0, index);

            return customModelData.get(index);

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error getting meteor custom model data for level " + level, e);
            return 3000 + level;
        }
    }

    private Location calculateMeteorPosition(Location start, Location target, String animation, int ticks, int maxTicks) {
        double progress = (double) ticks / maxTicks;
        progress = easeInQuart(progress);

        switch (animation.toLowerCase()) {
            case "spiral":
                double angle = progress * Math.PI * 3.0; // REDUCED spirals
                double spiralRadius = 6 * (1 - progress); // REDUCED radius
                double x = target.getX() + Math.cos(angle) * spiralRadius;
                double z = target.getZ() + Math.sin(angle) * spiralRadius;
                double y = start.getY() + (target.getY() - start.getY()) * progress;
                return new Location(target.getWorld(), x, y, z);

            case "random":
                double randomX = (random.nextDouble() - 0.5) * 6 * (1 - progress); // REDUCED randomness
                double randomZ = (random.nextDouble() - 0.5) * 6 * (1 - progress); // REDUCED randomness
                return start.clone().add(
                        (target.getX() - start.getX()) * progress + randomX,
                        (target.getY() - start.getY()) * progress,
                        (target.getZ() - start.getZ()) * progress + randomZ
                );

            default: // linear
                return start.clone().add(
                        (target.getX() - start.getX()) * progress,
                        (target.getY() - start.getY()) * progress,
                        (target.getZ() - start.getZ()) * progress
                );
        }
    }

    private Location[] calculateAirstrikePositions(Location center, int radius, int count, String animation) {
        Location[] positions = new Location[count];

        switch (animation.toLowerCase()) {
            case "line":
                for (int i = 0; i < count; i++) {
                    double offset = (i - count / 2.0) * 3.0; // REDUCED spacing
                    positions[i] = center.clone().add(offset, 15 + random.nextInt(10), 0); // REDUCED height
                }
                break;

            case "circle":
                for (int i = 0; i < count; i++) {
                    double angle = (2 * Math.PI * i) / count;
                    double x = Math.cos(angle) * radius * 0.5; // REDUCED radius
                    double z = Math.sin(angle) * radius * 0.5; // REDUCED radius
                    positions[i] = center.clone().add(x, 15 + random.nextInt(10), z); // REDUCED height
                }
                break;

            case "v_formation":
                for (int i = 0; i < count; i++) {
                    double side = (i % 2 == 0) ? -1 : 1;
                    double distance = (i / 2) * 3.0; // REDUCED distance
                    positions[i] = center.clone().add(side * distance, 15 + random.nextInt(10), -distance); // REDUCED height
                }
                break;

            default: // scatter
                for (int i = 0; i < count; i++) {
                    double x = (random.nextDouble() - 0.5) * radius * 0.8; // REDUCED scatter
                    double z = (random.nextDouble() - 0.5) * radius * 0.8; // REDUCED scatter
                    positions[i] = center.clone().add(x, 15 + random.nextInt(10), z); // REDUCED height
                }
                break;
        }

        return positions;
    }

    private double easeInQuart(double t) {
        return t * t * t * t;
    }

    private int getNextEntityId() {
        int id = entityIdCounter.incrementAndGet();
        if (id > ENTITY_ID_MAX) {
            entityIdCounter.set(ENTITY_ID_START);
            id = entityIdCounter.incrementAndGet();
        }
        return id;
    }

    /**
     * Enhanced cleanup method for shutdown with comprehensive tracking cleanup
     */
    public void cleanup() {
        animationLock.writeLock().lock();
        try {
            // Remove all tracked entities
            for (Player player : playerEntities.keySet()) {
                if (player.isOnline()) {
                    java.util.Set<Integer> entities = playerEntities.get(player);
                    if (entities != null) {
                        for (Integer entityId : entities) {
                            removeEntity(player, entityId);
                        }
                    }
                }
            }

            // Clear all tracking data
            packetCounts.clear();
            lastPacketTime.clear();
            activeAnimations.clear();
            entityOwners.clear();
            playerEntities.clear();
            activeSounds.clear();
            lastSoundTime.clear();


        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error during ProtocolLib cleanup", e);
        } finally {
            animationLock.writeLock().unlock();
        }
    }

    // Public accessor methods for monitoring
    public int getTotalActiveAnimations() {
        return activeAnimations.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    public boolean hasActiveAnimations(Player player) {
        AtomicInteger count = activeAnimations.get(player);
        return count != null && count.get() > 0;
    }

    public int getPlayerEntityCount(Player player) {
        java.util.Set<Integer> entities = playerEntities.get(player);
        return entities != null ? entities.size() : 0;
    }

    public int getActiveSoundCount(Player player) {
        AtomicInteger count = activeSounds.get(player);
        return count != null ? count.get() : 0;
    }
}