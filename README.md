# Thread-Safe LRU Cache

A high-performance, production-grade **Least-Recently-Used (LRU) cache** built in Java 17. Designed for concurrent environments, it combines a `ConcurrentHashMap` for O(1) lookup with a hand-rolled doubly-linked list for LRU ordering, guarded by a `ReentrantReadWriteLock` that maximises read throughput.

---

## Features

| Feature | Details |
|---|---|
| **LRU Eviction** | Doubly-linked list + hashmap gives O(1) get, put, and evict |
| **TTL Expiry** | Per-entry creation timestamp; entries expire lazily on access and eagerly via scheduled cleanup |
| **Thread Safety** | `ReentrantReadWriteLock` — multiple concurrent readers, exclusive writers |
| **Cache Loader** | Functional interface for automatic value computation on cache miss |
| **Statistics** | `AtomicLong`-backed hit/miss/eviction/load counters with snapshot support |
| **Cache Warming** | Concurrent bulk pre-load via `CacheWarmer` with configurable thread pool |
| **Builder Pattern** | Fluent `CacheConfig.Builder` with validation on all fields |
| **Scheduled Cleanup** | Background `ScheduledExecutorService` daemon thread sweeps expired entries |

---

## Tech Stack

- **Java 17**
- **Gradle 8.4** (build tool)
- **JUnit 5.10** (testing)
- **Mockito 5.5** (mocking)
- **AssertJ 3.24** (fluent assertions)
- **Awaitility 4.2** (async test assertions)

---

## Project Structure

```
thread-safe-cache/
├── build.gradle
├── settings.gradle
├── gradle/wrapper/
│   └── gradle-wrapper.properties
├── src/
│   ├── main/java/com/cache/
│   │   ├── core/
│   │   │   ├── Cache.java            ← Public interface
│   │   │   ├── CacheEntry.java       ← Internal doubly-linked list node
│   │   │   └── LRUCache.java         ← Main implementation
│   │   ├── config/
│   │   │   └── CacheConfig.java      ← Immutable config + fluent builder
│   │   ├── stats/
│   │   │   └── CacheStats.java       ← AtomicLong-backed statistics
│   │   ├── loader/
│   │   │   ├── CacheLoader.java      ← Functional interface for loading values
│   │   │   └── CacheLoadException.java
│   │   └── warming/
│   │       └── CacheWarmer.java      ← Concurrent bulk pre-loader
│   └── test/java/com/cache/
│       ├── LRUCacheTest.java         ← 35+ unit & concurrency tests
│       ├── CacheStatsTest.java       ← Isolated stats tests
│       └── CacheWarmingTest.java     ← Warming tests
```

---

## Architecture Deep Dive

### Data Structure

```
ConcurrentHashMap<K, CacheEntry<K,V>>
     │
     └─ O(1) key → node pointer

Doubly-Linked List (LRU ordering):
  [HEAD sentinel] ↔ [MRU entry] ↔ ... ↔ [LRU entry] ↔ [TAIL sentinel]
```

- **GET:** Acquire read lock → check map → if hit, `touch()` entry to refresh access time → move to head under write lock.
- **PUT:** Acquire write lock → if key exists, update in-place and promote; else if at capacity evict tail, then insert at head.
- **EVICT:** Unlink `tail.prev` from list + remove from map — O(1).

### Concurrency Strategy

```
ReentrantReadWriteLock
├── ReadLock   → get() fast path (multiple threads concurrent)
└── WriteLock  → put(), remove(), eviction, cleanup (exclusive)
```

The `ConcurrentHashMap` itself is used for O(1) lookups, but **all structural mutations to the linked list** (which determines LRU order) require the write lock. This design gives maximum read concurrency while keeping writes serialised and correct.

### TTL Implementation

```java
// In CacheEntry
boolean isExpired(long ttlSeconds) {
    return (System.currentTimeMillis() - createdAt) > (ttlSeconds * 1_000L);
}
```

Expiry is checked lazily on every `get()` and eagerly by the background cleanup task:

```
ScheduledExecutorService (daemon thread "cache-cleanup")
  └─ Every cleanupIntervalSeconds:
       1. Read lock → collect expired keys
       2. Write lock → re-verify + removeEntry() for each
```

The double-check (read then write) is critical: an entry could be refreshed between the two lock acquisitions and must not be removed after a re-put.

---

## Quick Start

### Build

```bash
./gradlew build
```

### Run Tests

```bash
./gradlew test
```

### View Test Report

```bash
open build/reports/tests/test/index.html
```

### Test Coverage Report

```bash
./gradlew jacocoTestReport
open build/reports/jacoco/test/html/index.html
```

---

## Usage Examples

### Basic Usage

```java
LRUCache<String, User> cache = new LRUCache<>(
    CacheConfig.<String, User>builder()
        .capacity(1000)
        .ttl(10, TimeUnit.MINUTES)
        .build()
);

cache.put("user-123", user);
Optional<User> result = cache.get("user-123");

cache.shutdown(); // release background threads
```

### With Cache Loader (automatic miss handling)

```java
LRUCache<String, User> cache = new LRUCache<>(
    CacheConfig.<String, User>builder()
        .capacity(500)
        .ttl(5, TimeUnit.MINUTES)
        .cleanupIntervalSeconds(60)
        .recordStats(true)
        .cacheLoader(key -> userRepository.findById(key))  // auto-load on miss
        .build()
);

// First call: miss → loader invoked → result cached
Optional<User> user = cache.get("user-42");

// Second call: hit → loader NOT invoked
Optional<User> sameUser = cache.get("user-42");
```

### Cache Warming at Startup

```java
CacheWarmer<String, Product> warmer = CacheWarmer.<String, Product>builder()
    .concurrency(8)
    .loader(id -> productRepository.findById(id))
    .build();

List<String> hotProductIds = analyticsService.getTopProductIds(500);
CacheWarmer.WarmingResult result = warmer.warm(cache, hotProductIds);

System.out.printf("Warmed %d/%d products in %dms%n",
    result.getSuccessCount(), result.getTotalCount(), result.getElapsedMs());
```

### Monitoring Statistics

```java
CacheStats stats = cache.getStats();

System.out.printf("Hit rate:   %.1f%%%n", stats.hitRate()  * 100);
System.out.printf("Miss rate:  %.1f%%%n", stats.missRate() * 100);
System.out.printf("Evictions:  %d%n",    stats.getEvictionCount());
System.out.printf("Total reqs: %d%n",    stats.totalRequestCount());

// Immutable point-in-time snapshot for logging/reporting
CacheStats.Snapshot snap = stats.snapshot();
metricsSystem.record(snap);
```

---

## Test Overview

### Test Classes

| Class | Test Count | Focus |
|---|---|---|
| `LRUCacheTest` | 35 | CRUD, eviction, TTL, loader, stats, warming, concurrency |
| `CacheStatsTest` | 9 | Isolated stats counter accuracy and concurrency |
| `CacheWarmingTest` | 6 | Bulk loading correctness and error handling |

### Concurrency Tests (inside `LRUCacheTest`)

| Test | Threads | Ops/Thread | What it verifies |
|---|---|---|---|
| Concurrent puts | 16 | 500 | No exceptions, size ≤ capacity |
| Concurrent gets + puts | 16 | 500 | No data corruption |
| Stats under concurrency | 16 | 500 | `putCount == totalPuts` |
| Eviction under load | 16 | 500 | Size never exceeds capacity |
| Concurrent removes | 16 | 30 | No exceptions |

---

## Configuration Reference

| Method | Default | Description |
|---|---|---|
| `capacity(int)` | 100 | Max entries before LRU eviction |
| `ttl(long, TimeUnit)` | 5 min | Time-to-live per entry |
| `ttlSeconds(long)` | 300 | TTL in seconds (shorthand) |
| `cleanupIntervalSeconds(long)` | 60 | Background sweep frequency |
| `recordStats(boolean)` | true | Enable/disable stat tracking |
| `cacheLoader(CacheLoader)` | null | Auto-load values on miss |

---

## Design Decisions & Trade-offs

**Why `ReentrantReadWriteLock` over `synchronized`?**
Read-heavy workloads benefit significantly from multiple concurrent readers. Synchronized blocks would serialize every `get()` unnecessarily.

**Why not `LinkedHashMap(accessOrder=true)` wrapped in `Collections.synchronizedMap`?**
`synchronizedMap` uses a single mutex, eliminating read concurrency. The custom linked list with RWLock provides better throughput under realistic mixed workloads.

**Why a single cleanup thread vs. per-entry scheduled tasks?**
Per-entry timers at scale (millions of entries) would create millions of `ScheduledFuture` objects. A single sweeper thread is far more memory-efficient and predictable.

**Why `ConcurrentHashMap` if we already have a lock?**
The map is read under both read and write locks. `ConcurrentHashMap` ensures the internal map structure itself is safe from `ConcurrentModificationException` even if we accidentally miss a locking path — it's a safety net, not the primary synchronisation mechanism.

---

## Potential Extensions

- **Segment-based locking** (like `ConcurrentHashMap` internals) to reduce write contention further
- **Soft/Weak reference values** for memory-sensitive caches
- **Async loader** with `CompletableFuture` to avoid blocking threads during load
- **Redis / distributed cache** adapter implementing the `Cache<K,V>` interface
- **Prometheus metrics** integration via `CacheStats.snapshot()`
