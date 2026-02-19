package lru.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import stats.CacheStats;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.AssertionsForClassTypes.within;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CacheStats Tests")
public class CacheStatsTest {
    private CacheStats stats;

    @BeforeEach
    void setUp() {
        stats = new CacheStats();
    }

    @Test
    @DisplayName("initial stats should be zero")
    void initialStatsShouldBeZero() {
        assertEquals(0, stats.getHitCount());
        assertEquals(0, stats.getMissCount());
        assertEquals(0, stats.getEvictionCount());
        assertEquals(0, stats.getLoadCount());
        assertEquals(0, stats.getLoadFailCount());
        assertEquals(0, stats.getExpiredCount());
        assertEquals(0, stats.getPutCount());
        assertEquals(0, stats.hitRate());
        assertEquals(0, stats.missRate());
        assertEquals(0, stats.totalRequestCount());
    }

    @Test
    @DisplayName("recordHit increments hit count and updates hit rate")
    void recordHitIncrementsHitCount() {
        stats.recordHit();
        stats.recordHit();
        stats.recordMiss();
        assertEquals(2, stats.getHitCount());
        assertEquals(1, stats.getMissCount());
        assertEquals(2.0 / 3.0, stats.hitRate());
        assertEquals(1.0 / 3.0, stats.missRate());
        assertEquals(3, stats.totalRequestCount());
    }

    @Test
    @DisplayName("reset zeroes all counters")
    void resetZeroesAllCounters() {
        stats.recordHit();
        stats.recordMiss();
        stats.recordEviction();
        stats.recordLoad();
        stats.recordLoadFail();
        stats.recordExpired();
        stats.recordPut();

        stats.reset();

        assertEquals(0, stats.getHitCount());
        assertEquals(0, stats.getMissCount());
        assertEquals(0, stats.getEvictionCount());
        assertEquals(0, stats.getLoadCount());
        assertEquals(0, stats.getLoadFailCount());
        assertEquals(0, stats.getExpiredCount());
        assertEquals(0, stats.getPutCount());
    }

    @Test
    @DisplayName("snapshot captures point-in-time values")
    void snapshotCapturesPointInTimeValues() {
        stats.recordHit();
        stats.recordHit();
        stats.recordMiss();

        CacheStats.Snapshot snapshot = stats.snapshot();
        assertEquals(2, snapshot.getHitCount());
        assertEquals(1, snapshot.getMissCount());

        stats.recordHit();
        stats.recordHit();
        assertEquals(2, snapshot.getHitCount());
    }

    @Test
    @DisplayName("snapshot toString contains rate percentages")
    void snapshotToStringContainsRatePercentages() {
        stats.recordHit();
        stats.recordMiss();
        String s = stats.snapshot().toString();
        assertTrue(s.contains("hitRate"));
        assertTrue(s.contains("missRate"));
    }

    @Nested
    @DisplayName("Concurrency")
    class ConcurrencyTest {

        @Test
        @DisplayName("concurrent increments are fully counted")
        void concurrentIncrementsAreFullyCounted() throws InterruptedException {
            int threads = 20;
            int opsEach = 1000;
            CountDownLatch latch = new CountDownLatch(threads);

            for(int t=0; t<threads; t++) {
                new Thread(() -> {
                    try {
                        for(int i=0; i<opsEach; i++) {
                            stats.recordHit();
                        }
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            assertEquals(threads * opsEach, stats.getHitCount());
        }
    }
}
