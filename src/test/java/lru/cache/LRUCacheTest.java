package lru.cache;

import config.CacheConfig;
import core.LRUCache;
import loader.CacheLoadException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import stats.CacheStats;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LRUCache Tests")
public class LRUCacheTest {

    private LRUCache<String, String> cache;

    @BeforeEach
    void setUp() {
        cache = new LRUCache<>(CacheConfig.<String, String>builder()
                .capacity(5)
                .ttlSeconds(60)
                .cleanupIntervalSeconds(30)
                .recordStats(true)
                .build());
    }

    @AfterEach
    void tearDown() {
        cache.shutdown();
    }

    @Nested
    @DisplayName("Basic CRUD Operations")
    public class BasicCrudTests {

        @Test
        void putAndGetReturnsValue() {
            cache.put("key1", "value1");
            assertTrue(cache.get("key1").isPresent());
            assertEquals("value1", cache.get("key1").orElse(null));
        }

        @Test
        void getMissingKeyReturnsEmpty() {
            assertTrue(cache.get("missing").isEmpty());
        }

        @Test
        void putOverwritesExistingValue() {
            cache.put("key1", "original");
            cache.put("key1", "updated");
            assertTrue(cache.get("key1").isPresent());
            assertEquals("updated", cache.get("key1").orElse(null));
        }

        @Test
        void removeDeletesExistingKey() {
            cache.put("key1", "value1");
            assertTrue(cache.remove("key1"));
            assertTrue(cache.get("key1").isEmpty());
        }

        @Test
        void removeMissingKeyReturnsFalse() {
            assertFalse(cache.remove("nonexistent"));
        }

        @Test
        void containsKeyPresent() {
            cache.put("key1", "value1");
            assertTrue(cache.containsKey("key1"));
        }

        @Test
        void containsKeyMissing() {
            assertFalse(cache.containsKey("missing"));
        }

        @Test
        void sizeIncrementsOnPut() {
            assertEquals(0, cache.size());
            cache.put("a", "1");
            assertEquals(1, cache.size());
            cache.put("b", "2");
            assertEquals(2, cache.size());
        }

        @Test
        void clearEmptiesCache() {
            cache.put("a", "1");
            cache.put("b", "2");
            cache.clear();
            assertTrue(cache.isEmpty());
            assertEquals(0, cache.size());
        }

        @Test
        void keysReturnsCurrentKeySet() {
            cache.put("a", "1");
            cache.put("b", "2");
            assertTrue(cache.keys().contains("a"));
            assertTrue(cache.keys().contains("b"));
        }

        @Test
        void nullKeyThrowsNPException() {
            assertThrows(NullPointerException.class, () -> cache.put(null, "value"));
            assertThrows(NullPointerException.class, () -> cache.get(null));
            assertThrows(NullPointerException.class, () -> cache.remove(null));
            assertThrows(NullPointerException.class, () -> cache.containsKey(null));
        }

        @Test
        void nullValueThrowsNPException() {
            assertThrows(NullPointerException.class, () -> cache.put("key", null));
        }
    }

    @Nested
    @DisplayName("LRU Eviction")
    public class LruEvictionTests {

        @Test
        void evictsLRUEntryOnCapacityExceeded() {
            for (int i = 1; i <= 5; i++) cache.put("key" + i, "value" + i);

            cache.get("key1");
            cache.put("key6", "value6");

            assertTrue(cache.containsKey("key1"));
            assertFalse(cache.containsKey("key2"));
            assertTrue(cache.containsKey("key6"));
        }

        @Test
        void recentlyAccessedEntryNotEvicted() {
            for (int i = 1; i <= 5; i++) cache.put("key" + i, "value" + i);

            cache.get("key1");
            cache.get("key1");

            cache.put("a", "1");
            cache.put("b", "2");
            cache.put("c", "3");

            assertTrue(cache.containsKey("key1"));
        }

        @Test
        void updatePromotesEntryToMRU() {
            for (int i = 1; i <= 5; i++) cache.put("key" + i, "value" + i);

            cache.put("key1", "updated");
            cache.put("key6", "value6");

            assertTrue(cache.containsKey("key1"));
            assertFalse(cache.containsKey("key2"));
        }

        @Test
        void evictionCounterIncrementsOnLruEviction() {
            for (int i = 1; i <= 5; i++) cache.put("key" + i, "value" + i);

            cache.put("key6", "value6");
            assertEquals(1, cache.getStats().getEvictionCount());
        }

        @Test
        void evictionDoesNotExceedCapacity() {
            for (int i = 0; i < 20; i++) cache.put("key" + i, "value" + i);
            assertTrue(cache.size() <= 5);
        }
    }

    @Nested
    @DisplayName("CacheLoader Integration")
    class CacheLoaderTests {

        @Test
        void loaderInvokedOnMissAndResultCached() {
            AtomicInteger loadCount = new AtomicInteger();
            LRUCache<String, String> loaderCache = new LRUCache<>(
                    CacheConfig.<String, String>builder()
                            .capacity(10)
                            .ttlSeconds(60)
                            .loader(key -> {
                                loadCount.incrementAndGet();
                                return "loaded-" + key;
                            })
                            .build()
            );

            try {
                assertEquals("loaded-k1", loaderCache.get("k1").orElse(null));
                assertEquals(1, loadCount.get());

                assertEquals("loaded-k1", loaderCache.get("k1").orElse(null));
                assertEquals(1, loadCount.get());
            } finally {
                loaderCache.shutdown();
            }
        }

        @Test
        void loaderReturningNullGivesEmptyOptional() {
            LRUCache<String, String> nullLoaderCache = new LRUCache<>(
                    CacheConfig.<String, String>builder()
                            .capacity(10)
                            .ttlSeconds(60)
                            .loader(key -> null)
                            .build()
            );

            try {
                assertTrue(nullLoaderCache.get("missing").isEmpty());
            } finally {
                nullLoaderCache.shutdown();
            }
        }

        @Test
        void loadExceptionReturnsEmpty() {
            LRUCache<String, String> exceptionLoaderCache = new LRUCache<>(
                    CacheConfig.<String, String>builder()
                            .capacity(10)
                            .ttlSeconds(60)
                            .loader(key -> {
                                throw new CacheLoadException(key, "DB unavailable");
                            })
                            .build()
            );

            try {
                assertTrue(exceptionLoaderCache.get("k1").isEmpty());
                assertEquals(1, exceptionLoaderCache.getStats().getLoadFailCount());
            } finally {
                exceptionLoaderCache.shutdown();
            }
        }

        @Test
        void loaderLoadCountTracked() {
            LRUCache<String, String> loaderCache = new LRUCache<>(
                    CacheConfig.<String, String>builder()
                            .capacity(10)
                            .ttlSeconds(60)
                            .loader(key -> "value-" + key)
                            .build()
            );

            try {
                loaderCache.get("a");
                loaderCache.get("b");
                loaderCache.get("c");
                assertEquals(3, loaderCache.getStats().getLoadCount());
            } finally {
                loaderCache.shutdown();
            }
        }
    }

    @Nested
    @DisplayName("Cache Statistics")
    class StatisticsTests {

        @Test
        void hitRateIsOneWhenAllHits() {
            cache.put("key", "value");
            cache.get("key");
            cache.get("key");
            assertEquals(1.0, cache.getStats().hitRate(), 0.0001);
        }

        @Test
        void missRateIsOneWhenAllMisses() {
            cache.get("missing1");
            cache.get("missing2");
            assertEquals(1.0, cache.getStats().missRate(), 0.0001);
        }

        @Test
        void hitAndMissRatesSumToOne() {
            cache.put("key", "value");
            cache.get("key");
            cache.get("missing");

            double hitRate = cache.getStats().hitRate();
            double missRate = cache.getStats().missRate();

            assertEquals(1.0, hitRate + missRate, 0.0001);
        }

        @Test
        void putCountTracked() {
            cache.put("a", "1");
            cache.put("b", "2");
            cache.put("a", "updated");
            assertEquals(3, cache.getStats().getPutCount());
        }

        @Test
        void statsSnapshotIsImmutable() {
            cache.put("a", "1");
            cache.get("a");

            CacheStats.Snapshot snap = cache.getStats().snapshot();
            long snapHits = snap.getHitCount();

            cache.get("a");
            cache.get("b");

            assertEquals(snapHits, snap.getHitCount());
        }

        @Test
        void statsResetZeroesCounters() {
            cache.put("a", "1");
            cache.get("a");
            cache.get("b");

            cache.getStats().reset();

            assertEquals(0, cache.getStats().getHitCount());
            assertEquals(0, cache.getStats().getMissCount());
            assertEquals(0, cache.getStats().getPutCount());
        }
    }

    @Nested
    @DisplayName("Concurrency and Thread Safety")
    @Execution(ExecutionMode.CONCURRENT)
    class ConcurrentTests {

        private static final int THREAD_COUNT = 16;
        private static final int OPS_PER_THREAD = 500;

        @Test
        void concurrentPutsAreThreadSafe() throws InterruptedException {
            LRUCache<String, String> concurrentCache = new LRUCache<>(
                    CacheConfig.<String, String>builder()
                            .capacity(100)
                            .ttlSeconds(60)
                            .build()
            );

            try {
                CyclicBarrier barrier = new CyclicBarrier(THREAD_COUNT);
                CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
                List<Throwable> errors = new CopyOnWriteArrayList<>();

                for (int t = 0; t < THREAD_COUNT; t++) {
                    final int threadId = t;
                    new Thread(() -> {
                        try {
                            barrier.await();
                            for (int i = 0; i < OPS_PER_THREAD; i++) {
                                concurrentCache.put("key-" + threadId + "-" + i, "value");
                            }
                        } catch (Throwable e) {
                            errors.add(e);
                        } finally {
                            latch.countDown();
                        }
                    }).start();
                }

                assertTrue(latch.await(30, TimeUnit.SECONDS));
                assertTrue(errors.isEmpty());
                assertTrue(concurrentCache.size() <= 100);
            } finally {
                concurrentCache.shutdown();
            }
        }
    }
}
