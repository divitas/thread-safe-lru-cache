package lru.cache;

import config.CacheConfig;
import core.LRUCache;
import loader.CacheLoadException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import warming.CacheWarmer;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CacheWarmer Tests")
public class CacheWarmingTest {
    private LRUCache<String, String> cache;

    @BeforeEach
    void setUp() {
        cache = new LRUCache<>(CacheConfig.<String, String>builder()
                .capacity(200)
                .ttlSeconds(60)
                .build());
    }

    @AfterEach
    void tearDown() {
        cache.shutdown();
    }

    @Test
    @DisplayName("warm loads all keys using the provided loader")
    void warmLoadsAllKeys() {
        CacheWarmer<String, String> warmer = CacheWarmer.<String, String>builder()
                .concurrency(4)
                .loader(key -> "loaded-" + key)
                .build();

        List<String> keys = IntStream.range(0, 50)
                .mapToObj(i -> "key" + i)
                .toList();

        CacheWarmer.WarmingResult result = warmer.warm(cache, keys);

        assertEquals(50, result.getSuccessCount());
        assertEquals(0, result.getFailCount());

        for (String key : keys) {
            assertTrue(cache.get(key).isPresent());
            assertTrue(cache.get(key).get().contains("loaded-" + key));
        }

    }

    @Test
    @DisplayName("warm records failures when loader throws exceptions")
    void warmRecordsFailures() {
        CacheWarmer<String, String> warmer = CacheWarmer.<String, String>builder()
                .loader(key -> {throw new CacheLoadException(key, "fail");})
                .build();

        CacheWarmer.WarmingResult result = warmer.warm(cache, List.of("key1", "key2", "key3"));

        assertEquals(0, result.getSuccessCount());
        assertEquals(3, result.getFailCount());
    }

    @Test
    @DisplayName("warm with empty key list returns empty result")
    void warmWithEmptyKeyList() {
        CacheWarmer<String, String> warmer = CacheWarmer.<String, String>builder()
                .loader(key -> "v")
                .build();

        CacheWarmer.WarmingResult result = warmer.warm(cache, List.of());
        assertEquals(0, result.getSuccessCount());
        assertEquals(0, result.getElapsedMs());
    }

    @Test
    @DisplayName("WarmingResult getTotalCount returns correct total")
    void warmingResultGetTotalCount() {
        CacheWarmer<String, String> warmer = CacheWarmer.<String, String>builder()
                .loader(key -> key.startsWith("good") ? "v": null)
                .build();

        List<String> keys = List.of("good1", "good2", "bad1");
        CacheWarmer.WarmingResult result = warmer.warm(cache, keys);
        assertEquals(3, result.getTotalCount());
    }

    @Test
    @DisplayName("CacheWarmer builder rejects missing loader")
    void cacheWarmerBuilderRejectsMissingLoader() {
        assertThrows(NullPointerException.class, () -> {CacheWarmer.<String, String>builder().build();});
    }

    @Test
    @DisplayName("CacheWarmer builder rejects non-positive concurrency")
    void cacheWarmerBuilderRejectsNonPositiveConcurrency() {
        assertThrows(IllegalArgumentException.class, () -> {
            CacheWarmer.<String, String>builder().loader(k -> "v").concurrency(0).build();
        });
        assertThrows(IllegalArgumentException.class, () -> {
            CacheWarmer.<String, String>builder().loader(k -> "v").concurrency(-1).build();
        });
    }
}
