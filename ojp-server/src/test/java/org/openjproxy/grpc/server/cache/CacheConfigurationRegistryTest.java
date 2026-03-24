package org.openjproxy.grpc.server.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CacheConfigurationRegistry}.
 */
class CacheConfigurationRegistryTest {
    
    private CacheConfigurationRegistry registry;
    
    @BeforeEach
    void setUp() {
        registry = CacheConfigurationRegistry.getInstance();
        registry.clear();
        
        // Clear all test properties
        System.getProperties().stringPropertyNames().stream()
            .filter(key -> key.startsWith("testds") || key.startsWith("otherds"))
            .forEach(System::clearProperty);
    }
    
    @AfterEach
    void tearDown() {
        registry.clear();
        
        // Clear all test properties
        System.getProperties().stringPropertyNames().stream()
            .filter(key -> key.startsWith("testds") || key.startsWith("otherds"))
            .forEach(System::clearProperty);
    }
    
    @Test
    void testGetInstanceReturnsSingleton() {
        CacheConfigurationRegistry instance1 = CacheConfigurationRegistry.getInstance();
        CacheConfigurationRegistry instance2 = CacheConfigurationRegistry.getInstance();
        
        assertSame(instance1, instance2);
    }
    
    @Test
    void testGetOrLoadDisabledConfiguration() {
        // No properties set - should return disabled configuration
        CacheConfiguration config = registry.getOrLoad("testds");
        
        assertNotNull(config);
        assertFalse(config.isEnabled());
        assertEquals("testds", config.getDatasourceName());
    }
    
    @Test
    void testGetOrLoadEnabledConfiguration() {
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "300s");
        
        CacheConfiguration config = registry.getOrLoad("testds");
        
        assertTrue(config.isEnabled());
        assertEquals(1, config.getRules().size());
    }
    
    @Test
    void testGetOrLoadCachesConfiguration() {
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "300s");
        
        CacheConfiguration config1 = registry.getOrLoad("testds");
        CacheConfiguration config2 = registry.getOrLoad("testds");
        
        // Should return the same instance
        assertSame(config1, config2);
        assertEquals(1, registry.size());
    }
    
    @Test
    void testGetOrLoadMultipleDatasources() {
        // Configure testds
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .* FROM products.*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "600s");
        
        // Configure otherds
        System.setProperty("otherds.ojp.cache.enabled", "true");
        System.setProperty("otherds.ojp.cache.queries.1.pattern", "SELECT .* FROM users.*");
        System.setProperty("otherds.ojp.cache.queries.1.ttl", "300s");
        
        CacheConfiguration config1 = registry.getOrLoad("testds");
        CacheConfiguration config2 = registry.getOrLoad("otherds");
        
        assertNotSame(config1, config2);
        assertEquals("testds", config1.getDatasourceName());
        assertEquals("otherds", config2.getDatasourceName());
        assertEquals(2, registry.size());
    }
    
    @Test
    void testReload() {
        // Initial configuration
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "300s");
        
        CacheConfiguration config1 = registry.getOrLoad("testds");
        assertTrue(config1.isEnabled());
        assertEquals(1, config1.getRules().size());
        
        // Change configuration
        System.setProperty("testds.ojp.cache.queries.2.pattern", "SELECT .* FROM users.*");
        System.setProperty("testds.ojp.cache.queries.2.ttl", "600s");
        
        // Reload
        CacheConfiguration config2 = registry.reload("testds");
        
        assertNotSame(config1, config2);
        assertTrue(config2.isEnabled());
        assertEquals(2, config2.getRules().size());
    }
    
    @Test
    void testReloadInvalidConfiguration() {
        // Initial valid configuration
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "300s");
        
        CacheConfiguration config1 = registry.getOrLoad("testds");
        assertTrue(config1.isEnabled());
        
        // Change to invalid configuration
        System.clearProperty("testds.ojp.cache.queries.1.pattern");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "invalid");
        
        // Reload should fail
        assertThrows(IllegalArgumentException.class, () -> registry.reload("testds"));
        
        // Old configuration should still be removed from cache
        assertFalse(registry.isCached("testds"));
    }
    
    @Test
    void testIsCached() {
        assertFalse(registry.isCached("testds"));
        
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "300s");
        
        registry.getOrLoad("testds");
        
        assertTrue(registry.isCached("testds"));
    }
    
    @Test
    void testClear() {
        // Load multiple configurations
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "300s");
        
        System.setProperty("otherds.ojp.cache.enabled", "true");
        System.setProperty("otherds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("otherds.ojp.cache.queries.1.ttl", "300s");
        
        registry.getOrLoad("testds");
        registry.getOrLoad("otherds");
        
        assertEquals(2, registry.size());
        
        // Clear all
        registry.clear();
        
        assertEquals(0, registry.size());
        assertFalse(registry.isCached("testds"));
        assertFalse(registry.isCached("otherds"));
    }
    
    @Test
    void testSize() {
        assertEquals(0, registry.size());
        
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "300s");
        
        registry.getOrLoad("testds");
        assertEquals(1, registry.size());
        
        System.setProperty("otherds.ojp.cache.enabled", "true");
        System.setProperty("otherds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("otherds.ojp.cache.queries.1.ttl", "300s");
        
        registry.getOrLoad("otherds");
        assertEquals(2, registry.size());
    }
    
    @Test
    void testGetOrLoadNullDatasourceName() {
        assertThrows(NullPointerException.class, () -> registry.getOrLoad(null));
    }
    
    @Test
    void testReloadNullDatasourceName() {
        assertThrows(NullPointerException.class, () -> registry.reload(null));
    }
    
    @Test
    void testConcurrentAccess() throws InterruptedException {
        System.setProperty("testds.ojp.cache.enabled", "true");
        System.setProperty("testds.ojp.cache.queries.1.pattern", "SELECT .*");
        System.setProperty("testds.ojp.cache.queries.1.ttl", "300s");
        
        // Simulate concurrent access from multiple threads
        Thread[] threads = new Thread[10];
        CacheConfiguration[] configs = new CacheConfiguration[10];
        
        for (int i = 0; i < threads.length; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                configs[index] = registry.getOrLoad("testds");
            });
        }
        
        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }
        
        // All threads should get the same instance
        CacheConfiguration firstConfig = configs[0];
        for (int i = 1; i < configs.length; i++) {
            assertSame(firstConfig, configs[i]);
        }
        
        // Should only have loaded once
        assertEquals(1, registry.size());
    }
}
