# Distributed Locking Demo — Spring Boot + Redisson + PostgreSQL

A working demonstration of how a distributed lock prevents the classic check-then-act race condition when multiple JVM
threads (or service instances) try to modify a shared resource concurrently.

This README is written as a complete reference. If you come back to this project in a year or two, you should be able to
understand every concept and design decision from this document alone.

---

## Table of Contents

1. [The Problem We're Solving](#1-the-problem-were-solving)
2. [Why `synchronized` Doesn't Work in Microservices](#2-why-synchronized-doesnt-work-in-microservices)
3. [What Distributed Locking Actually Is](#3-what-distributed-locking-actually-is)
4. [Required Properties of a Distributed Lock](#4-required-properties-of-a-distributed-lock)
5. [Why Redisson](#5-why-redisson)
6. [Key Design Decisions Explained](#6-key-design-decisions-explained)

---

## 1. The Problem We're Solving

### The check-then-act race condition

Imagine an inventory system where an item has `quantity = 1`. Two requests come in simultaneously, each wanting to buy
it:
Time Thread A Thread B DB quantity
───── ──────────────────────── ──────────────────────── ───────────
T=0 — — 1
T=1 reads quantity = 1 — 1
T=2 — reads quantity = 1 1
T=3 passes "if quantity > 0"     passes "if quantity > 0"    1
T=4 sets quantity = 0, saves — 0
T=5 — sets quantity = 0, saves 0
RESULT Sale processed ✓ Sale processed ✓ 💥 Should be -1

Both threads believed they were the legitimate buyer. Both succeeded. The item has been "sold" twice but the database
only decremented once. This is called **overselling** — a real production bug that costs money.

The pattern is everywhere:

- **Inventory management** — selling more units than exist
- **Payment processing** — charging the same card twice
- **Coupon redemption** — applying a one-use code multiple times
- **Scheduled jobs** — running the same nightly report on every instance

The window between "read" and "write" is where two threads can both see the old value before either updates it. We need
a way to make read-check-write **atomic** across all threads.

---

## 2. Why `synchronized` Doesn't Work in Microservices

In a single-JVM application, Java's `synchronized` keyword (or `ReentrantLock`) prevents the race:

```java
public synchronized void sellItem() { ...}
```

This works because all threads live in the same JVM and share the same memory. The lock is an in-memory object — only
one thread at a time can hold it.

In microservices, you typically run **multiple instances** of the same service for high availability and scale. Each
instance is a separate JVM with its own memory. `synchronized` only coordinates threads **within one JVM**.
Instance 1 (JVM)            Instance 2 (JVM)            Instance 3 (JVM)
─────────────── ─────────────── ───────────────
synchronized works synchronized works synchronized works
within Instance 1 within Instance 2 within Instance 3
BUT... none of them know about each other
All three can be in the critical section simultaneously

If three instances all process the same order at the same instant, `synchronized` does nothing to prevent it. We need a
lock that lives **outside** any single JVM.

---

## 3. What Distributed Locking Actually Is

A distributed lock is a lock stored in a **shared external system** that all instances can see and respect. Common
backing stores:

- **Redis** (most common — fast, simple)
- **Zookeeper** (strong consistency)
- **Database** (using `SELECT FOR UPDATE` or a dedicated lock table)
- **Etcd**, **Consul** (less common in Java ecosystems)

The mechanism is conceptually simple:
Instance 1: "Hey Redis, can I have the lock for item-123?"
Redis:      "Yes, here it is. TTL = 30 seconds."
Instance 2: "Hey Redis, can I have the lock for item-123?"
Redis:      "No, Instance 1 has it. Try again."
Instance 1: ... does the work ...
Instance 1: "Hey Redis, I'm done. Releasing the lock."
Redis:      "OK."
Instance 2: "Hey Redis, can I have the lock for item-123?"
Redis:      "Yes, here it is."

Only one instance holds the lock at a time. Everyone else waits or fails. The critical section becomes serialised across
the entire system.

---

## 4. Required Properties of a Distributed Lock

A correctly implemented distributed lock must guarantee:

### 1. Mutual exclusion

At any given moment, only one client can hold the lock. This is the fundamental purpose.

### 2. Deadlock-free (TTL / lease expiry)

Every lock must have a Time-To-Live. If the lock holder crashes or hangs, the lock **must eventually expire
automatically**. Without TTL, a crashed instance holds the lock forever and the system deadlocks.

### 3. Owner-only release

Only the client that acquired the lock should be able to release it. Use a unique token (UUID) per acquisition. On
release, verify the token matches before deleting — otherwise Instance B could accidentally release Instance A's lock.

### 4. Fault tolerance

The locking system must remain available even if some nodes fail. A single Redis node is a single point of failure. Use
Redis Sentinel, Redis Cluster, or RedLock for production HA.

### 5. Re-entrancy (optional)

The same thread that holds a lock should be able to acquire it again without deadlocking. Useful when a locked method
calls another method that also tries to acquire the same lock.

---

## 5. Why Redisson

Redisson is a Java library that wraps Redis with high-level abstractions. For distributed locking, it offers `RLock`
which handles all the gnarly details of a correct implementation.

What Redisson handles for you:

- **Atomic SETNX + EXPIRE** — acquires the lock and sets TTL in a single operation (race-free)
- **Pub/sub waiting** — instead of busy-polling, waiters subscribe to a Redis channel and get notified when the lock is
  released
- **Re-entrancy tracking** — same thread can lock the same key multiple times (uses a counter internally)
- **Watchdog auto-renewal** — if you don't specify a `leaseTime`, a background thread renews the TTL every 10 seconds,
  preventing expiry during long operations
- **Owner tracking** — uses a UUID token internally so you can never release someone else's lock

The naïve "just use Redis SETNX" approach has subtle bugs around timing, atomicity, and reentrancy. Redisson is
battle-tested and handles them all.

---

## 6. Key Design Decisions Explained

### Why a separate `InventoryWriter` bean instead of putting `@Transactional` on `sellItem()`?

The naïve approach would be to put `@Transactional` directly on `SafeItemService.sellItem()`:

```java

@Transactional  // ← BAD: this seems simpler but is broken
public boolean sellItem(Long itemId) {
    RLock lock = redisson.getLock(...);
    try {
        lock.tryLock(...);
        // do DB work here
    } finally {
        lock.unlock();   // ← released BEFORE transaction commits
    }
}
```

**The problem: lock release happens BEFORE the transaction commits.**

Spring's `@Transactional` proxy commits the transaction *after* the method returns. If the lock is released inside the
`finally` block of that same method, there's a window between lock release and commit where:
Thread A:                       Thread B:
───────── ─────────
finally: lock.unlock()    ────▶ acquires lock (free!)
reads quantity = 1 ← STALE READ
A's update isn't committed yet
proxy: commit transaction
updates quantity = 0
commits
✓ "sold"

Both threads sell the item. The race condition isn't actually fixed.

**The fix:** put `@Transactional` on a method called *during* the locked window, so begin-work-commit all happen inside
the lock:
acquire lock
↓
BEGIN transaction ← starts inside the lock
read, update, save
COMMIT transaction ← commits inside the lock
↓
release lock ← released after commit

We tried two ways to achieve this:

1. **Self-injection with `@Lazy`** — inject `SafeItemService` into itself, call `self.doSell()` so the call goes through
   Spring's proxy. This works in theory but had a bug where the lazy proxy held an uninitialised instance with null
   fields.
2. **Separate bean (what we settled on)** — extract the transactional method into `InventoryWriter`. Cross-bean calls
   naturally go through Spring's proxy. No `@Lazy`, no self-references, no null fields. Cleaner separation of concerns
   too.

### Why `@Transactional(propagation = Propagation.REQUIRES_NEW)`?

`REQUIRES_NEW` always starts a fresh transaction, regardless of whether one already exists. For our demo, `REQUIRED` (
the default) would behave identically because there's no outer transaction. So I just trusted `Default` however having `REQUIRES_NEW` has these advantages:

- It documents intent: "each sale is its own atomic operation"
- It's defensive against future code changes
- It mimics real HTTP request handlers (each request arrives without a transaction)

### Why explicit `RedissonConfig` instead of relying on the starter?

We use the plain `redisson` library, not `redisson-spring-boot-starter`, because:

- The starter has Spring Boot version compatibility issues (we hit `ClassNotFoundException: RedisProperties`)
- Defining `RedissonClient` ourselves is one config class — easier to reason about
- No hidden auto-configuration behaviour

```java

@Bean(destroyMethod = "shutdown")
public RedissonClient redissonClient() {
    Config config = new Config();
    config.useSingleServer()
            .setAddress("redis://localhost:6379");
    return Redisson.create(config);
}
```

The `destroyMethod = "shutdown"` ensures Redisson cleans up its connection pool when Spring shuts down.

### Lock parameters explained

```java
acquired =lock.

tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
```

- **`waitTime` (10s)** — maximum time a thread will wait to acquire the lock before giving up. Returns `false` if it
  can't acquire in this window.
- **`leaseTime` (30s)** — maximum time the lock is held. Auto-releases after this even if the holder crashes. Must be
  longer than your max expected critical section duration.
- **`isHeldByCurrentThread()` check before `unlock()`** — prevents releasing someone else's lock if logic bugs cause
  this thread to attempt unlock without holding it.
- **`try/finally`** — guarantees release even if the critical section throws an exception.

### Why `ddl-auto: validate`?

- `none` — Hibernate ignores schema entirely; broken schemas only fail at query time
- `validate` — Hibernate checks at startup that all `@Entity` classes have matching tables and columns. **Fails fast**
  with a clear error if anything is wrong.
- `update` — Hibernate adds missing columns; dangerous in production (silent migrations)
- `create` / `create-drop` — destroys data; only for tests

`validate` is the production gold standard. Schema changes themselves are managed by `init.sql` (or Flyway/Liquibase in
production), not by Hibernate.

### Why use resource-specific lock keys?

```java
String lockKey = "item:lock:" + itemId;   // GOOD
String lockKey = "item:lock";             // BAD
```

Using a global key like `"item:lock"` would serialise *every* sale across *every* item — even unrelated items would
block each other. By including the item id in the key, sales of different items proceed in parallel, and only contention
for the *same* item is serialised. Lock granularity directly impacts throughput.

### Some commands
- To run docker containers for redis and postgres : `docker-compose up -d`
- To see status of all services : `docker-compose ps`
- Stop (containers preserved, can restart later): `docker-compose stop`
- Start previously stopped containers: `docker-compose start`
- Stops and removes containers(full cleanup): `docker-compose down`


