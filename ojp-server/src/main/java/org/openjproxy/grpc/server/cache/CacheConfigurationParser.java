package org.openjproxy.grpc.server.cache;

import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parses cache configuration from System properties (ojp.properties).
 * 
 * <p>Expected property format:
 * <pre>
 * datasourceName.ojp.cache.enabled=true
 * datasourceName.ojp.cache.queries.1.pattern=SELECT .* FROM products WHERE .*
 * datasourceName.ojp.cache.queries.1.ttl=600s
 * datasourceName.ojp.cache.queries.1.invalidateOn=products,product_prices
 * datasourceName.ojp.cache.queries.2.pattern=SELECT .* FROM users WHERE .*
 * datasourceName.ojp.cache.queries.2.ttl=300s
 * datasourceName.ojp.cache.queries.2.invalidateOn=users
 * </pre>
 *
 * <p>Thread-safe.
 */
public final class CacheConfigurationParser {
    
    private CacheConfigurationParser() {
        // Utility class
    }
    
    /**
     * Parse cache configuration from System properties for the specified datasource.
     *
     * @param datasourceName the datasource name
     * @return parsed cache configuration (never null)
     * @throws IllegalArgumentException if configuration is invalid
     */
    public static CacheConfiguration parse(String datasourceName) {
        Objects.requireNonNull(datasourceName, "datasourceName cannot be null");
        
        String prefix = datasourceName + ".ojp.cache.";
        
        // Check if caching is enabled
        boolean enabled = getBooleanProperty(prefix + "enabled", false);
        if (!enabled) {
            return CacheConfiguration.disabled(datasourceName);
        }
        
        // Parse query rules
        List<CacheRule> rules = parseQueryRules(datasourceName, prefix);
        
        return new CacheConfiguration(datasourceName, enabled, rules);
    }
    
    /**
     * Parse all query rules for a datasource.
     */
    private static List<CacheRule> parseQueryRules(String datasourceName, String prefix) {
        List<CacheRule> rules = new ArrayList<>();
        
        // Find all query indices (1, 2, 3, ...)
        Set<Integer> indices = findQueryIndices(prefix);
        
        if (indices.isEmpty()) {
            throw new IllegalArgumentException(
                "Cache enabled for datasource '" + datasourceName + 
                "' but no query rules defined. Expected properties like: " +
                prefix + "queries.1.pattern=..."
            );
        }
        
        for (int index : indices) {
            String queryPrefix = prefix + "queries." + index + ".";
            
            try {
                CacheRule rule = parseQueryRule(queryPrefix, index);
                rules.add(rule);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                    "Invalid cache rule at " + queryPrefix + ": " + e.getMessage(),
                    e
                );
            }
        }
        
        return rules;
    }
    
    /**
     * Parse a single query rule.
     */
    private static CacheRule parseQueryRule(String queryPrefix, int index) {
        // Pattern is required
        String patternStr = getProperty(queryPrefix + "pattern");
        if (patternStr == null || patternStr.isBlank()) {
            throw new IllegalArgumentException(
                "Missing required property: " + queryPrefix + "pattern"
            );
        }
        
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternStr);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                "Invalid regex pattern at " + queryPrefix + "pattern: " + e.getMessage(),
                e
            );
        }
        
        // TTL with default
        String ttlStr = getProperty(queryPrefix + "ttl", "300s");
        Duration ttl = parseDuration(ttlStr, queryPrefix + "ttl");
        
        // Invalidation tables (optional)
        String invalidateOnStr = getProperty(queryPrefix + "invalidateOn", "");
        List<String> invalidateOn = parseTableList(invalidateOnStr);
        
        // Enabled flag (default true)
        boolean enabled = getBooleanProperty(queryPrefix + "enabled", true);
        
        return new CacheRule(pattern, ttl, invalidateOn, enabled);
    }
    
    /**
     * Find all query indices by scanning System properties.
     */
    private static Set<Integer> findQueryIndices(String prefix) {
        Set<Integer> indices = new TreeSet<>();
        String queriesPrefix = prefix + "queries.";
        
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith(queriesPrefix)) {
                String rest = key.substring(queriesPrefix.length());
                int dotIndex = rest.indexOf('.');
                if (dotIndex > 0) {
                    try {
                        int index = Integer.parseInt(rest.substring(0, dotIndex));
                        if (index > 0) {  // Only positive indices
                            indices.add(index);
                        }
                    } catch (NumberFormatException ignored) {
                        // Not a valid index, skip
                    }
                }
            }
        }
        
        return indices;
    }
    
    /**
     * Parse duration from string like "300s", "10m", "1h".
     */
    private static Duration parseDuration(String ttlStr, String propertyName) {
        if (ttlStr == null || ttlStr.isBlank()) {
            throw new IllegalArgumentException(
                "TTL cannot be empty at " + propertyName
            );
        }
        
        ttlStr = ttlStr.trim().toLowerCase();
        
        try {
            if (ttlStr.endsWith("s")) {
                long seconds = Long.parseLong(ttlStr.substring(0, ttlStr.length() - 1));
                if (seconds <= 0) {
                    throw new IllegalArgumentException("TTL must be positive");
                }
                return Duration.ofSeconds(seconds);
            } else if (ttlStr.endsWith("m")) {
                long minutes = Long.parseLong(ttlStr.substring(0, ttlStr.length() - 1));
                if (minutes <= 0) {
                    throw new IllegalArgumentException("TTL must be positive");
                }
                return Duration.ofMinutes(minutes);
            } else if (ttlStr.endsWith("h")) {
                long hours = Long.parseLong(ttlStr.substring(0, ttlStr.length() - 1));
                if (hours <= 0) {
                    throw new IllegalArgumentException("TTL must be positive");
                }
                return Duration.ofHours(hours);
            } else {
                throw new IllegalArgumentException(
                    "Invalid TTL format: '" + ttlStr + "'. Expected format: <number>s|m|h (e.g., '300s', '10m', '1h')"
                );
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid TTL format at " + propertyName + ": '" + ttlStr + "'. " +
                "Expected format: <number>s|m|h (e.g., '300s', '10m', '1h')",
                e
            );
        }
    }
    
    /**
     * Parse comma-separated table list.
     */
    private static List<String> parseTableList(String invalidateOnStr) {
        if (invalidateOnStr == null || invalidateOnStr.isBlank()) {
            return List.of();
        }
        
        return Arrays.stream(invalidateOnStr.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .distinct()  // Remove duplicates
            .toList();
    }
    
    /**
     * Get boolean property from System properties.
     */
    private static boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }
    
    /**
     * Get string property from System properties.
     */
    private static String getProperty(String key) {
        return System.getProperty(key);
    }
    
    /**
     * Get string property from System properties with default.
     */
    private static String getProperty(String key, String defaultValue) {
        String value = System.getProperty(key);
        return (value != null) ? value : defaultValue;
    }
}
