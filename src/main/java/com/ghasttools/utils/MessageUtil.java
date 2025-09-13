package com.ghasttools.utils;

import com.ghasttools.GhastToolsPlugin;
import me.clip.placeholderapi.PlaceholderAPI;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FIXED: Enhanced thread-safe utility class with proper cache management and memory leak prevention
 */
public class MessageUtil {

    private final GhastToolsPlugin plugin;
    private volatile FileConfiguration messagesConfig;
    private volatile String messageType;

    // FIXED: Enhanced caching with proper memory management
    private final ConcurrentHashMap<String, String> messageCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> cacheTimestamps = new ConcurrentHashMap<>();

    // Enhanced color patterns for better support
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern RGB_PATTERN = Pattern.compile("&\\{(\\d{1,3}),(\\d{1,3}),(\\d{1,3})\\}");
    private static final Pattern GRADIENT_PATTERN = Pattern.compile("&\\[gradient:([A-Fa-f0-9]{6}):([A-Fa-f0-9]{6})\\]([^&]*)&\\[/gradient\\]");
    private static final Pattern BUKKIT_COLOR_PATTERN = Pattern.compile("&([0-9a-fk-or])");

    // Configuration constants to avoid magic numbers
    private static final long CACHE_DURATION_MS = 300000; // 5 minutes
    private static final int MAX_CACHE_SIZE = 1000;
    private static final long CACHE_CLEANUP_INTERVAL = 60000; // 1 minute

    // FIXED: Track cleanup task to prevent memory leaks
    private BukkitTask cleanupTask;

    public MessageUtil(GhastToolsPlugin plugin) {
        this.plugin = plugin;
        reloadMessages();
        startCacheCleanupTask();
    }

    /**
     * FIXED: Enhanced reload with proper error handling and null safety
     */
    public synchronized void reloadMessages() {
        try {
            if (plugin.getConfigManager() != null) {
                this.messagesConfig = plugin.getConfigManager().getMessagesConfig();

                if (plugin.getConfigManager().getMainConfig() != null) {
                    this.messageType = plugin.getConfigManager().getMainConfig().getString("messages.type", "chat");
                } else {
                    this.messageType = "chat";
                }

                this.messageCache.clear(); // Clear cache on reload
                this.cacheTimestamps.clear();
                plugin.getLogger().info("Messages reloaded successfully");
            } else {
                plugin.getLogger().warning("ConfigManager not available during message reload");
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to reload messages: " + e.getMessage());
            // Set safe defaults
            this.messageType = "chat";
        }
    }

    /**
     * FIXED: Enhanced message sending with proper null checks
     */
    public void sendMessage(CommandSender sender, String messageKey) {
        sendMessage(sender, messageKey, Map.of());
    }

    public void sendMessage(CommandSender sender, String messageKey, Map<String, String> placeholders) {
        if (sender == null || messageKey == null) {
            return;
        }

        // FIXED: Check if plugin is shutting down
        if (plugin.isShuttingDown()) {
            return;
        }

        String message = getMessage(messageKey, placeholders);

        // Apply PlaceholderAPI if sender is a player and hook is available
        if (sender instanceof Player) {
            Player player = (Player) sender;
            message = applyPlaceholderAPI(player, message);

            // FIXED: Enhanced message type handling with fallbacks
            try {
                switch (messageType.toLowerCase()) {
                    case "actionbar":
                        sendActionBar(player, message);
                        break;
                    case "title":
                        sendTitle(player, message);
                        break;
                    default: // chat
                        sender.sendMessage(message);
                        break;
                }
            } catch (Exception e) {
                // Fallback to chat if other methods fail
                sender.sendMessage(message);
            }
        } else {
            // For console, strip colors for readability
            sender.sendMessage(ChatColor.stripColor(message));
        }
    }

    /**
     * FIXED: Enhanced message retrieval with proper caching and null safety
     */
    public String getMessage(String messageKey, Map<String, String> placeholders) {
        if (messageKey == null) {
            return "§cInvalid message key";
        }

        // Create cache key with better hash handling
        String cacheKey = messageKey + "_" + (placeholders != null ? placeholders.hashCode() : 0);

        // Check cache first
        String cachedMessage = getCachedMessage(cacheKey);
        if (cachedMessage != null) {
            return cachedMessage;
        }

        // Get message from config
        String message = getMessageFromConfig(messageKey);

        // FIXED: Safe placeholder replacement with null checks
        if (placeholders != null && !placeholders.isEmpty()) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    message = message.replace("{" + entry.getKey() + "}", entry.getValue());
                }
            }
        }

        // Translate color codes
        message = translateColorCodes(message);

        // Cache the result with size limit
        cacheMessage(cacheKey, message);

        return message;
    }

    /**
     * FIXED: Enhanced config retrieval with proper null checks
     */
    private String getMessageFromConfig(String messageKey) {
        if (messagesConfig == null) {
            return "§cMessages not loaded: " + messageKey;
        }

        try {
            String message = messagesConfig.getString("messages." + messageKey);
            if (message == null) {
                return "§cMessage not found: " + messageKey;
            }
            return message;
        } catch (Exception e) {
            plugin.getLogger().fine("Error retrieving message '" + messageKey + "': " + e.getMessage());
            return "§cError loading message: " + messageKey;
        }
    }

    /**
     * FIXED: Enhanced cache management with proper memory limits and leak prevention
     */
    private String getCachedMessage(String cacheKey) {
        Long timestamp = cacheTimestamps.get(cacheKey);
        if (timestamp != null && System.currentTimeMillis() - timestamp < CACHE_DURATION_MS) {
            return messageCache.get(cacheKey);
        }

        // Remove expired entry
        messageCache.remove(cacheKey);
        cacheTimestamps.remove(cacheKey);
        return null;
    }

    private void cacheMessage(String cacheKey, String message) {
        // Prevent memory leaks by limiting cache size
        if (messageCache.size() >= MAX_CACHE_SIZE) {
            clearExpiredCache();
        }

        if (messageCache.size() < MAX_CACHE_SIZE) {
            messageCache.put(cacheKey, message);
            cacheTimestamps.put(cacheKey, System.currentTimeMillis());
        }
    }

    /**
     * FIXED: Enhanced cache cleanup with better efficiency
     */
    public void clearExpiredCache() {
        long currentTime = System.currentTimeMillis();
        cacheTimestamps.entrySet().removeIf(entry -> {
            if (currentTime - entry.getValue() > CACHE_DURATION_MS) {
                messageCache.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * FIXED: Start cache cleanup task with proper tracking
     */
    private void startCacheCleanupTask() {
        // Cancel existing task if any
        if (cleanupTask != null && !cleanupTask.isCancelled()) {
            cleanupTask.cancel();
        }

        cleanupTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!plugin.isShuttingDown()) {
                clearExpiredCache();
            }
        }, 20L * 60, 20L * 60); // Every minute
    }

    /**
     * Enhanced color code translation supporting multiple formats
     */
    private String translateColorCodes(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        try {
            // Handle gradient colors first (most complex)
            message = translateGradients(message);

            // Handle hex colors (&#RRGGBB)
            message = translateHexColors(message);

            // Handle RGB colors (&{r,g,b})
            message = translateRgbColors(message);

            // Handle standard Bukkit color codes (&0-9, &a-f, &k-o, &r)
            message = translateBukkitColors(message);

            return message;

        } catch (Exception e) {
            plugin.getLogger().fine("Error translating color codes: " + e.getMessage());
            // Fallback to basic color translation
            return ChatColor.translateAlternateColorCodes('&', message);
        }
    }

    /**
     * FIXED: Enhanced gradient translation with error handling
     */
    private String translateGradients(String message) {
        try {
            Matcher gradientMatcher = GRADIENT_PATTERN.matcher(message);
            StringBuffer buffer = new StringBuffer();

            while (gradientMatcher.find()) {
                String startHex = gradientMatcher.group(1);
                String endHex = gradientMatcher.group(2);
                String text = gradientMatcher.group(3);

                if (startHex != null && endHex != null && text != null) {
                    String gradientText = createGradientText(text, startHex, endHex);
                    gradientMatcher.appendReplacement(buffer, Matcher.quoteReplacement(gradientText));
                }
            }
            gradientMatcher.appendTail(buffer);

            return buffer.toString();

        } catch (Exception e) {
            plugin.getLogger().fine("Error processing gradients: " + e.getMessage());
            return message;
        }
    }

    /**
     * Create gradient text between two hex colors
     */
    private String createGradientText(String text, String startHex, String endHex) {
        if (text.length() <= 1) {
            return applyHexColor(text, startHex);
        }

        StringBuilder result = new StringBuilder();
        int length = text.length();

        for (int i = 0; i < length; i++) {
            float ratio = (float) i / (length - 1);
            String interpolatedHex = interpolateHexColor(startHex, endHex, ratio);
            result.append(applyHexColor(String.valueOf(text.charAt(i)), interpolatedHex));
        }

        return result.toString();
    }

    /**
     * FIXED: Safe hex color interpolation with validation
     */
    private String interpolateHexColor(String startHex, String endHex, float ratio) {
        try {
            if (startHex.length() != 6 || endHex.length() != 6) {
                return startHex; // Fallback to start color
            }

            int startR = Integer.parseInt(startHex.substring(0, 2), 16);
            int startG = Integer.parseInt(startHex.substring(2, 4), 16);
            int startB = Integer.parseInt(startHex.substring(4, 6), 16);

            int endR = Integer.parseInt(endHex.substring(0, 2), 16);
            int endG = Integer.parseInt(endHex.substring(2, 4), 16);
            int endB = Integer.parseInt(endHex.substring(4, 6), 16);

            int r = Math.round(startR + (endR - startR) * ratio);
            int g = Math.round(startG + (endG - startG) * ratio);
            int b = Math.round(startB + (endB - startB) * ratio);

            // Clamp values to valid range
            r = Math.max(0, Math.min(255, r));
            g = Math.max(0, Math.min(255, g));
            b = Math.max(0, Math.min(255, b));

            return String.format("%02x%02x%02x", r, g, b);

        } catch (NumberFormatException e) {
            return startHex; // Fallback to start color
        }
    }

    /**
     * FIXED: Enhanced hex color translation with error handling
     */
    private String translateHexColors(String message) {
        try {
            Matcher hexMatcher = HEX_PATTERN.matcher(message);
            StringBuffer buffer = new StringBuffer();

            while (hexMatcher.find()) {
                String hexColor = hexMatcher.group(1);
                if (hexColor != null && hexColor.length() == 6) {
                    String colorCode = applyHexColor("", hexColor);
                    hexMatcher.appendReplacement(buffer, Matcher.quoteReplacement(colorCode));
                }
            }
            hexMatcher.appendTail(buffer);

            return buffer.toString();

        } catch (Exception e) {
            plugin.getLogger().fine("Error processing hex colors: " + e.getMessage());
            return message;
        }
    }

    /**
     * FIXED: Safe hex color application with version detection
     */
    private String applyHexColor(String text, String hexColor) {
        try {
            // Try modern hex color support (1.16+)
            net.md_5.bungee.api.ChatColor chatColor = net.md_5.bungee.api.ChatColor.of("#" + hexColor);
            return chatColor + text;
        } catch (Exception e) {
            // Fallback to closest legacy color
            return getClosestLegacyColor(hexColor) + text;
        }
    }

    /**
     * FIXED: Enhanced RGB color translation with validation
     */
    private String translateRgbColors(String message) {
        try {
            Matcher rgbMatcher = RGB_PATTERN.matcher(message);
            StringBuffer buffer = new StringBuffer();

            while (rgbMatcher.find()) {
                try {
                    int r = Math.min(255, Math.max(0, Integer.parseInt(rgbMatcher.group(1))));
                    int g = Math.min(255, Math.max(0, Integer.parseInt(rgbMatcher.group(2))));
                    int b = Math.min(255, Math.max(0, Integer.parseInt(rgbMatcher.group(3))));

                    String hexColor = String.format("%02x%02x%02x", r, g, b);
                    String colorCode = applyHexColor("", hexColor);
                    rgbMatcher.appendReplacement(buffer, Matcher.quoteReplacement(colorCode));
                } catch (NumberFormatException e) {
                    // Invalid RGB values, leave as is
                    rgbMatcher.appendReplacement(buffer, rgbMatcher.group(0));
                }
            }
            rgbMatcher.appendTail(buffer);

            return buffer.toString();

        } catch (Exception e) {
            plugin.getLogger().fine("Error processing RGB colors: " + e.getMessage());
            return message;
        }
    }

    /**
     * Translate standard Bukkit color codes
     */
    private String translateBukkitColors(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    /**
     * FIXED: Enhanced legacy color mapping with better accuracy
     */
    private ChatColor getClosestLegacyColor(String hexColor) {
        try {
            if (hexColor.length() != 6) {
                return ChatColor.WHITE;
            }

            int r = Integer.parseInt(hexColor.substring(0, 2), 16);
            int g = Integer.parseInt(hexColor.substring(2, 4), 16);
            int b = Integer.parseInt(hexColor.substring(4, 6), 16);

            // Enhanced color mapping logic
            double brightness = (r * 0.299 + g * 0.587 + b * 0.114) / 255.0;

            if (brightness < 0.1) return ChatColor.BLACK;
            if (brightness > 0.9) return ChatColor.WHITE;

            // Color hue detection
            if (r > g && r > b) {
                return r > 200 ? ChatColor.RED : ChatColor.DARK_RED;
            } else if (g > r && g > b) {
                return g > 200 ? ChatColor.GREEN : ChatColor.DARK_GREEN;
            } else if (b > r && b > g) {
                return b > 200 ? ChatColor.BLUE : ChatColor.DARK_BLUE;
            } else if (r > 150 && g > 150) {
                return ChatColor.YELLOW;
            } else if (r > 150 && b > 150) {
                return ChatColor.LIGHT_PURPLE;
            } else if (g > 150 && b > 150) {
                return ChatColor.AQUA;
            }

            return brightness > 0.5 ? ChatColor.GRAY : ChatColor.DARK_GRAY;

        } catch (Exception e) {
            return ChatColor.WHITE;
        }
    }

    /**
     * FIXED: Safe PlaceholderAPI application with null checks
     */
    private String applyPlaceholderAPI(Player player, String message) {
        if (plugin.getPlaceholderAPIHook() != null && plugin.getPlaceholderAPIHook().isRegistered()) {
            try {
                return PlaceholderAPI.setPlaceholders(player, message);
            } catch (Exception e) {
                plugin.getLogger().fine("Error applying PlaceholderAPI placeholders: " + e.getMessage());
            }
        }
        return message;
    }

    /**
     * FIXED: Enhanced action bar with fallback
     */
    private void sendActionBar(Player player, String message) {
        try {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
        } catch (Exception e) {
            // Fallback to chat if action bar fails
            player.sendMessage(message);
        }
    }

    /**
     * FIXED: Enhanced title with proper parsing and fallback
     */
    private void sendTitle(Player player, String message) {
        try {
            String[] parts = message.split("\\|", 2);
            String title = parts.length > 0 ? parts[0] : "";
            String subtitle = parts.length > 1 ? parts[1] : "";

            // Use configurable timing values
            int fadeIn = 10;
            int stay = 70;
            int fadeOut = 20;

            player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);

        } catch (Exception e) {
            // Fallback to chat if title fails
            player.sendMessage(message);
        }
    }

    /**
     * Send a message to all online players
     */
    public void broadcast(String messageKey, Map<String, String> placeholders) {
        plugin.getServer().getOnlinePlayers().forEach(player -> {
            sendMessage(player, messageKey, placeholders);
        });
    }

    public void broadcast(String messageKey) {
        broadcast(messageKey, Map.of());
    }

    /**
     * Get raw message without color translation (for logging)
     */
    public String getRawMessage(String messageKey, Map<String, String> placeholders) {
        if (messagesConfig == null) {
            return "Messages not loaded: " + messageKey;
        }

        String message = messagesConfig.getString("messages." + messageKey, "Message not found: " + messageKey);

        // Replace custom placeholders only
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    message = message.replace("{" + entry.getKey() + "}", entry.getValue());
                }
            }
        }

        return message;
    }

    /**
     * FIXED: Clear message cache and cancel cleanup task
     */
    public void clearCache() {
        messageCache.clear();
        cacheTimestamps.clear();
    }

    /**
     * FIXED: Cleanup method for plugin shutdown
     */
    public void cleanup() {
        if (cleanupTask != null && !cleanupTask.isCancelled()) {
            cleanupTask.cancel();
        }
        clearCache();
    }

    /**
     * Get cache size for monitoring
     */
    public int getCacheSize() {
        return messageCache.size();
    }

    /**
     * Test color code translation
     */
    public String testColorTranslation(String input) {
        return translateColorCodes(input);
    }
}