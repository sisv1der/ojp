package org.openjproxy.jdbc;

import com.openjproxy.grpc.CacheConfiguration;
import com.openjproxy.grpc.CacheRule;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.PatternSyntaxException;

/**
 * Builds CacheConfiguration proto message from System properties.
 * 
 * <p>Expected properties format:
 * <pre>
 * datasourceName.ojp.cache.enabled=true
 * datasourceName.ojp.cache.distribute=false
 * datasourceName.ojp.cache.queries.1.pattern=SELECT .* FROM products.*
 * datasourceName.ojp.cache.queries.1.ttl=600s
 * datasourceName.ojp.cache.queries.1.invalidateOn=products,product_prices
 * datasourceName.ojp.cache.queries.2.pattern=SELECT .* FROM users.*
 * datasourceName.ojp.cache.queries.2.ttl=300s
 * datasourceName.ojp.cache.queries.2.invalidateOn=users
 * </pre>
 */
@Slf4j
public class CacheConfigurationBuilder {
    
    /**
     * Build CacheConfiguration proto message from System properties for a given datasource.
     *
     * @param datasourceName The datasource name to build configuration for
     * @return CacheConfiguration proto message
     */
    public static CacheConfiguration buildCacheConfiguration(String datasourceName) {
        String prefix = datasourceName + ".ojp.cache.";
        
        // Check if caching is enabled
        boolean enabled = Boolean.parseBoolean(
            System.getProperty(prefix + "enabled", "false")
        );
        
        if (!enabled) {
            log.debug("Caching disabled for datasource: {}", datasourceName);
            return CacheConfiguration.newBuilder()
                .setEnabled(false)
                .setDistribute(false)
                .build();
        }
        
        // Check if distribution is enabled (default: false for local-only)
        boolean distribute = Boolean.parseBoolean(
            System.getProperty(prefix + "distribute", "false")
        );
        
        log.info("Building cache configuration for datasource '{}': enabled={}, distribute={}", 
            datasourceName, enabled, distribute);
        
        CacheConfiguration.Builder builder = CacheConfiguration.newBuilder()
            .setEnabled(true)
            .setDistribute(distribute);
        
        // Parse query rules
        Set<Integer> indices = findQueryIndices(prefix);
        log.debug("Found {} cache query rule indices for datasource '{}'", indices.size(), datasourceName);
        
        for (int index : indices) {
            try {
                CacheRule rule = parseQueryRule(prefix, index);
                if (rule != null) {
                    builder.addRules(rule);
                    log.debug("Added cache rule {}: pattern={}, ttl={}s, invalidateOn={}", 
                        index, rule.getSqlPattern(), rule.getTtlSeconds(), rule.getInvalidateOnList());
                }
            } catch (Exception e) {
                log.warn("Failed to parse cache rule {} for datasource '{}': {}", 
                    index, datasourceName, e.getMessage());
            }
        }
        
        CacheConfiguration config = builder.build();
        log.info("Built cache configuration for datasource '{}' with {} rules", 
            datasourceName, config.getRulesCount());
        
        return config;
    }
    
    /**
     * Find all query indices in System properties.
     * Looks for properties like: datasource.ojp.cache.queries.N.*
     *
     * @param prefix The property prefix (e.g., "postgres_prod.ojp.cache.")
     * @return Set of query indices found
     */
    private static Set<Integer> findQueryIndices(String prefix) {
        Set<Integer> indices = new TreeSet<>();  // Sorted set for consistent ordering
        String queriesPrefix = prefix + "queries.";
        
        // Scan System properties for query indices
        for (Object key : System.getProperties().keySet()) {
            String propertyKey = (String) key;
            if (propertyKey.startsWith(queriesPrefix)) {
                // Extract index from property like "datasource.ojp.cache.queries.1.pattern"
                String remainder = propertyKey.substring(queriesPrefix.length());
                int dotIndex = remainder.indexOf('.');
                if (dotIndex > 0) {
                    try {
                        int index = Integer.parseInt(remainder.substring(0, dotIndex));
                        indices.add(index);
                    } catch (NumberFormatException e) {
                        log.warn("Invalid query index in property: {}", propertyKey);
                    }
                }
            }
        }
        
        return indices;
    }
    
    /**
     * Parse a single cache rule from System properties.
     *
     * @param prefix The property prefix (e.g., "postgres_prod.ojp.cache.")
     * @param index The query rule index
     * @return CacheRule proto message or null if pattern is missing
     */
    private static CacheRule parseQueryRule(String prefix, int index) {
        String queryPrefix = prefix + "queries." + index + ".";
        
        // Pattern is required
        String pattern = System.getProperty(queryPrefix + "pattern");
        if (pattern == null || pattern.trim().isEmpty()) {
            log.warn("Missing pattern for cache rule {}, skipping", index);
            return null;
        }
        
        // Validate regex pattern
        try {
            java.util.regex.Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            log.error("Invalid regex pattern for cache rule {}: {}", index, e.getMessage());
            throw new IllegalArgumentException(
                "Invalid regex pattern for cache rule " + index + ": " + e.getMessage(), e);
        }
        
        // Parse TTL (default: 300s = 5 minutes)
        String ttlStr = System.getProperty(queryPrefix + "ttl", "300s");
        long ttlSeconds = parseDurationToSeconds(ttlStr);
        
        if (ttlSeconds <= 0) {
            log.error("Invalid TTL for cache rule {}: {} (must be positive)", index, ttlStr);
            throw new IllegalArgumentException(
                "Invalid TTL for cache rule " + index + ": " + ttlStr + " (must be positive)");
        }
        
        // Parse invalidateOn table list (comma-separated, optional)
        String invalidateOnStr = System.getProperty(queryPrefix + "invalidateOn", "");
        List<String> invalidateOn = parseTableList(invalidateOnStr);
        
        // Check if rule is enabled (default: true)
        boolean enabled = Boolean.parseBoolean(
            System.getProperty(queryPrefix + "enabled", "true")
        );
        
        return CacheRule.newBuilder()
            .setSqlPattern(pattern)
            .setTtlSeconds(ttlSeconds)
            .addAllInvalidateOn(invalidateOn)
            .setEnabled(enabled)
            .build();
    }
    
    /**
     * Parse duration string to seconds.
     * Supports formats: "300s", "10m", "2h"
     *
     * @param duration Duration string
     * @return Duration in seconds
     */
    private static long parseDurationToSeconds(String duration) {
        if (duration == null || duration.trim().isEmpty()) {
            return 300; // Default: 5 minutes
        }
        
        duration = duration.trim().toLowerCase();
        
        // Parse numeric value and unit
        char unit = duration.charAt(duration.length() - 1);
        String valueStr = duration.substring(0, duration.length() - 1).trim();
        
        try {
            long value = Long.parseLong(valueStr);
            
            switch (unit) {
                case 's':  // seconds
                    return value;
                case 'm':  // minutes
                    return value * 60;
                case 'h':  // hours
                    return value * 3600;
                default:
                    // No unit, assume seconds
                    return Long.parseLong(duration);
            }
        } catch (NumberFormatException e) {
            log.error("Invalid duration format: {}", duration);
            throw new IllegalArgumentException("Invalid duration format: " + duration, e);
        }
    }
    
    /**
     * Parse comma-separated table list.
     *
     * @param tableList Comma-separated table names
     * @return List of table names
     */
    private static List<String> parseTableList(String tableList) {
        if (tableList == null || tableList.trim().isEmpty()) {
            return new ArrayList<>();
        }
        
        return Arrays.stream(tableList.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }
}
