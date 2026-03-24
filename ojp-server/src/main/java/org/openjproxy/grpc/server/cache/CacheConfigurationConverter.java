package org.openjproxy.grpc.server.cache;

import com.openjproxy.grpc.CacheConfiguration;
import com.openjproxy.grpc.CacheRule;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Converts proto CacheConfiguration messages to domain CacheConfiguration objects.
 * Used by the server to parse cache configuration received from JDBC driver clients.
 */
@Slf4j
public class CacheConfigurationConverter {
    
    /**
     * Convert proto CacheConfiguration to domain CacheConfiguration.
     *
     * @param protoConfig Proto cache configuration from client
     * @param datasourceName Datasource name for this configuration
     * @return Domain CacheConfiguration object
     */
    public static org.openjproxy.grpc.server.cache.CacheConfiguration fromProto(
            CacheConfiguration protoConfig, 
            String datasourceName) {
        
        if (protoConfig == null || !protoConfig.getEnabled()) {
            log.debug("Cache configuration disabled for datasource: {}", datasourceName);
            return org.openjproxy.grpc.server.cache.CacheConfiguration.disabled(datasourceName);
        }
        
        log.info("Converting cache configuration for datasource '{}': enabled={}, distribute={}, rules={}", 
            datasourceName, protoConfig.getEnabled(), protoConfig.getDistribute(), protoConfig.getRulesCount());
        
        List<org.openjproxy.grpc.server.cache.CacheRule> domainRules = new ArrayList<>();
        
        // Convert each proto rule to domain rule
        int ruleIndex = 0;
        for (CacheRule protoRule : protoConfig.getRulesList()) {
            ruleIndex++;
            try {
                org.openjproxy.grpc.server.cache.CacheRule domainRule = convertRule(protoRule, ruleIndex);
                if (domainRule != null) {
                    domainRules.add(domainRule);
                    log.debug("Converted cache rule {}: pattern={}, ttl={}s, invalidateOn={}", 
                        ruleIndex, domainRule.getPattern().pattern(), domainRule.getTtl().getSeconds(), 
                        domainRule.getInvalidationTables());
                }
            } catch (Exception e) {
                log.error("Failed to convert cache rule {} for datasource '{}': {}", 
                    ruleIndex, datasourceName, e.getMessage());
                // Continue with other rules - skip invalid ones
            }
        }
        
        log.info("Converted {} cache rules for datasource '{}'", domainRules.size(), datasourceName);
        
        return new org.openjproxy.grpc.server.cache.CacheConfiguration(
            datasourceName,
            true,
            protoConfig.getDistribute(),
            domainRules
        );
    }
    
    /**
     * Convert proto CacheRule to domain CacheRule.
     *
     * @param protoRule Proto cache rule
     * @param ruleIndex Rule index for logging
     * @return Domain CacheRule or null if rule is invalid
     */
    private static org.openjproxy.grpc.server.cache.CacheRule convertRule(CacheRule protoRule, int ruleIndex) {
        // Validate pattern
        String sqlPattern = protoRule.getSqlPattern();
        if (sqlPattern == null || sqlPattern.trim().isEmpty()) {
            log.warn("Cache rule {} has empty pattern, skipping", ruleIndex);
            return null;
        }
        
        // Compile regex pattern
        Pattern pattern;
        try {
            pattern = Pattern.compile(sqlPattern);
        } catch (PatternSyntaxException e) {
            log.error("Invalid regex pattern for cache rule {}: {}", ruleIndex, e.getMessage());
            throw new IllegalArgumentException(
                "Invalid regex pattern for cache rule " + ruleIndex + ": " + e.getMessage(), e);
        }
        
        // Validate TTL
        long ttlSeconds = protoRule.getTtlSeconds();
        if (ttlSeconds <= 0) {
            log.error("Invalid TTL for cache rule {}: {} (must be positive)", ruleIndex, ttlSeconds);
            throw new IllegalArgumentException(
                "Invalid TTL for cache rule " + ruleIndex + ": " + ttlSeconds + " (must be positive)");
        }
        
        Duration ttl = Duration.ofSeconds(ttlSeconds);
        
        // Get invalidation tables (may be empty)
        List<String> invalidationTables = new ArrayList<>(protoRule.getInvalidateOnList());
        
        // Check if rule is enabled
        boolean enabled = protoRule.getEnabled();
        
        return new org.openjproxy.grpc.server.cache.CacheRule(
            pattern,
            ttl,
            invalidationTables,
            enabled
        );
    }
    
    /**
     * Check if proto cache configuration has any enabled rules.
     *
     * @param protoConfig Proto cache configuration
     * @return true if configuration is enabled and has at least one rule
     */
    public static boolean hasEnabledRules(CacheConfiguration protoConfig) {
        if (protoConfig == null || !protoConfig.getEnabled()) {
            return false;
        }
        
        return protoConfig.getRulesCount() > 0;
    }
}
