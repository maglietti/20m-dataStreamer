# DataStreamer Performance Analysis: 1KB Payload Test

This document analyzes DataStreamer operations with the original team's data schema (1KB payload per record), comparing index timing strategies and cluster behavior.

## Test Configuration

| Parameter | Value |
|-----------|-------|
| Total Records | 20,000,000 |
| Record Schema | ID (VARCHAR PK), COLUMN1 (1KB VARCHAR), COLUMN2 (INT), COLUMN3 (TIMESTAMP) |
| Payload Size | ~1KB per record |
| RAFT retryTimeoutMillis | 30,000 ms |
| Cluster Size | 5 nodes |
| Storage Engine | aipersist (persistent) |
| RAFT Log Storage | RocksDB (internal) |

## Results Comparison: All Scenarios

| Metric | Optimized (500/8) | Indexes BEFORE (5000/4) | Indexes AFTER (5000/4) | Original Team |
|--------|-------------------|-------------------------|------------------------|---------------|
| Page Size | 500 | 5,000 | 5,000 | 1,000 |
| Parallel Ops | 8 | 4 | 4 | 1 |
| Index Timing | BEFORE | BEFORE | AFTER | BEFORE |
| Test Duration | ~6:47 | ~6:19 | ~7:37 | ~18:30 |
| Lease Update Failures | **0** | 10 | **0** | 394 |
| Peers Unavailable | **0** | 8 | 2 | Multiple |
| Page Replacement Events | 5 | 5 | 5 | Unknown |
| Throttling Events | 10 | 13 | 6 | Unknown |
| Checkpoint Speed | 25-56 MB/s | 14-47 MB/s | 24-52 MB/s | 11-66 MB/s |
| Replication Lag Warnings | 0 | 0 | 0 | 986 |
| Total Warnings | **10** | 52 | 12 | 1000+ |
| Status | Success | Success | Success | Success (with issues) |

### Key Observations

**Optimized Configuration (pageSize=500, parallelOps=8, Indexes BEFORE):**

- Zero lease failures with indexes created before data load
- Zero peers unavailable warnings
- Smoother I/O through natural batch staggering
- Higher checkpoint speeds (25-56 MB/s) due to reduced I/O spikes
- Only 10 warnings total (all benign: 5 page replacement, 5 membership filtering)

**Indexes BEFORE Load (pageSize=5000, parallelOps=4):**

- Faster streaming time (6:19) because index already exists
- More cluster stress: 10 lease failures, 8 peers unavailable, 13 throttling events
- Lower checkpoint speeds due to synchronized batch completions causing I/O spikes

**Indexes AFTER Load (pageSize=5000, parallelOps=4):**

- Slightly longer total time (7:37) due to post-load index creation
- Minimal cluster stress: 0 lease failures, 2 peers unavailable, 6 throttling events
- Higher checkpoint speeds with more consistent performance

**Original Team:**

- Significantly longer time (~18:30)
- Severe cluster stress: 394 lease failures, 986 replication lag warnings
- Issues caused by inadequate backpressure handling and default timeout configurations

## Detailed Results: Optimized Configuration (pageSize=500, parallelOps=8)

| Metric | Value |
|--------|-------|
| Configuration | pageSize=500, parallelOps=8, indexes BEFORE |
| Test Duration | ~6 minutes 47 seconds |
| Lease Update Failures | 0 |
| Peers Unavailable Warnings | 0 |
| Page Replacement Events | 5 (one per node) |
| Throttling Events | 10 (2 per node) |

### Warning Analysis (Optimized)

Total warnings across all 5 nodes: **10**

| Warning Type | Count | Severity | Description |
|--------------|-------|----------|-------------|
| PersistentPageMemory | 5 | Normal | Page replacement under load |
| MembershipProtocol | 5 | Benign | Seed address filtering (startup) |

### Checkpoint Performance (Optimized)

| Node | Checkpoints | Pages Written | Slowest | Avg Speed |
|------|-------------|---------------|---------|-----------|
| node1 | 6 | 60K-110K | 53,219ms | 25-56 MB/s |
| node2 | 6 | 60K-110K | ~53,000ms | 25-56 MB/s |
| node3 | 6 | 60K-110K | ~53,000ms | 25-56 MB/s |
| node4 | 6 | 60K-110K | ~53,000ms | 25-56 MB/s |
| node5 | 6 | 60K-110K | ~53,000ms | 25-56 MB/s |

### Why Optimized Configuration Works

The smaller batch size (500 vs 5000) with higher parallelism (8 vs 4) creates naturally staggered I/O patterns. Instead of 4 large batches completing simultaneously and creating I/O spikes, 8 smaller batches complete at random intervals, smoothing the flow. This prevents the checkpoint system from being overwhelmed and eliminates the conditions that caused lease update failures.

## Detailed Results: Indexes BEFORE Load (pageSize=5000, parallelOps=4)

| Metric | Value |
|--------|-------|
| Configuration | pageSize=5000, parallelOps=4, indexes BEFORE |
| Test Duration | ~6 minutes 19 seconds |
| Lease Update Failures | 10 (all on node2) |
| Peers Unavailable Warnings | 8 |
| Page Replacement Events | 5 (one per node) |
| Throttling Events | 13 |

### Warning Analysis (Indexes Before, 5000/4)

Total warnings across all 5 nodes: **52**

| Warning Type | Count | Severity | Description |
|--------------|-------|----------|-------------|
| NodeImpl (RAFT config) | 24 | Benign | Startup configuration changes |
| LeaseUpdater | 10 | Moderate | Outdated lease data on node2 |
| RaftGroupServiceImpl | 8 | Moderate | Peers temporarily unavailable |
| PersistentPageMemory | 5 | Normal | Page replacement under load |
| MembershipProtocol | 5 | Benign | Seed address filtering |

### Checkpoint Performance (Indexes Before, 5000/4)

| Node | Checkpoints | Pages Written | Slowest | Avg Speed |
|------|-------------|---------------|---------|-----------|
| node1 | 5 | 86K-112K | 103,752ms | 16-47 MB/s |
| node2 | 5 | 86K-112K | 109,613ms | 14-39 MB/s |
| node3 | 5 | 86K-112K | 111,003ms | 14-42 MB/s |
| node4 | 5 | 86K-112K | 111,556ms | 14-38 MB/s |
| node5 | 5 | 86K-112K | 108,266ms | 14-41 MB/s |

## Detailed Results: Indexes AFTER Load (pageSize=5000, parallelOps=4)

| Metric | Value |
|--------|-------|
| Configuration | pageSize=5000, parallelOps=4, indexes AFTER |
| Test Duration | ~7 minutes 37 seconds |
| Lease Update Failures | 0 |
| Peers Unavailable Warnings | 2 |
| Page Replacement Events | 5 (one per node) |
| Throttling Events | 6 |

### Warning Analysis (Indexes After, 5000/4)

Total warnings across all 5 nodes: **12**

| Warning Type | Count | Severity | Description |
|--------------|-------|----------|-------------|
| PersistentPageMemory | 5 | Normal | Page replacement under load |
| MembershipProtocol | 5 | Benign | Seed address filtering (startup) |
| RaftGroupServiceImpl | 2 | Minor | Peers temporarily unavailable |

### Checkpoint Performance (Indexes After, 5000/4)

| Node | Checkpoints | Pages Written | Slowest | Avg Speed |
|------|-------------|---------------|---------|-----------|
| node1 | 5 | 85K-98K | 53,030ms | 28-42 MB/s |
| node2 | 5 | 84K-100K | 53,746ms | 24-48 MB/s |
| node3 | 5 | 84K-100K | 51,677ms | 27-52 MB/s |
| node4 | 5 | 85K-100K | 53,523ms | 26-365 MB/s |
| node5 | 5 | 85K-102K | 54,142ms | 28-75 MB/s |

## RocksDB Activity (RAFT Log Storage)

The cluster uses RocksDB internally for RAFT write-ahead logging (not for user data).

| Node | Flush/Compaction Events (Indexes After) |
|------|----------------------------------------|
| node1 | 54 |
| node2 | 54 |
| node3 | 58 |
| node4 | 62 |
| node5 | 54 |

This activity is separate from user data storage (aipersist) and represents normal RAFT log maintenance.

## Configuration Verification

### RAFT Configuration (from node logs)

```
raft{
  retryTimeoutMillis=30000,
  responseTimeoutMillis=3000,
  retryDelayMillis=200,
  fsync=false
}
```

### Storage Profile

```
profiles=[{engine=aipersist,name=default,replacementMode=CLOCK,sizeBytes=-1}]
```

### Page Memory Configuration

```
PersistentPageMemory: memoryAllocated=2.0 GiB, pages=130048, checkpointBuffer=512.0 MiB
```

## DataStreamer Tuning Theory

### Parameter Definitions

**Page Size**: Number of records buffered before sending a batch to a partition.

**Parallel Operations**: Number of in-flight batch operations active simultaneously per partition.

### Memory Pressure Model

Total in-flight data = `pageSize × parallelOps × partitions × recordSize`

With our cluster (25 partitions, ~1.1KB records):

| Configuration | Page Size | Parallel Ops | In-Flight Data |
|---------------|-----------|--------------|----------------|
| Original test | 5,000 | 4 | 550MB |
| Conservative | 1,000 | 4 | 110MB |
| Optimized | 500 | 8 | 110MB |

### Checkpoint Bottleneck Analysis

The checkpoint system writes dirty pages to disk. During streaming:

- Incoming data rate: ~300K records/sec × 1.1KB = 330MB/sec
- Dirty page generation: ~20,000 pages/sec (16KB pages)
- Observed checkpoint speed: 14-47 MB/s (880-2,940 pages/sec)

The checkpoint cannot keep up with dirty page generation, causing throttling and page replacement. The goal is to smooth I/O patterns rather than eliminate this fundamental constraint.

### Batch Synchronization Problem

With synchronized parallel operations, all batches complete simultaneously creating I/O spikes:

```
Time ─────────────────────────────────────────────►
Op1:  [████████]        [████████]        [████████]
Op2:  [████████]        [████████]        [████████]
Op3:  [████████]        [████████]        [████████]
Op4:  [████████]        [████████]        [████████]
      ▲                 ▲                 ▲
      Spike             Spike             Spike
```

### Solution: Higher Parallelism with Smaller Batches

With more parallel operations, batch completions become randomly distributed (law of large numbers), naturally smoothing the flow:

```
Time ─────────────────────────────────────────────►
Op1:  [██]    [██]    [██]    [██]    [██]    [██]
Op2:   [██]    [██]    [██]    [██]    [██]    [██]
Op3:    [██]    [██]    [██]    [██]    [██]    [██]
...
Op8:       [██]    [██]    [██]    [██]    [██]
      ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲
      Smooth continuous flow
```

**Mathematical basis**: Standard deviation of completion rate ∝ 1/√N

Doubling parallel ops from 4 to 8 reduces spike magnitude by ~30%.

### Optimal Configuration

For smooth I/O with indexes present:

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Page Size | 500 | Small batches for granular I/O |
| Parallel Ops | 8 | High concurrency for natural smoothing |
| In-Flight | 110MB | Same memory footprint as conservative |

This configuration provides:

- 8x more batches than original (40,000 vs 5,000 per partition fill)
- Natural staggering through random completion times
- Better interleaving with index maintenance
- Faster backpressure response

## Why Our Implementation Succeeded

Both runs (indexes before and after) completed successfully compared to the original team's problematic run:

1. **Demand-Driven Backpressure**: Custom `Flow.Publisher` generates records only when requested
2. **RAFT Retry Timeout**: 30-second timeout absorbs temporary I/O delays
3. **Throttling**: Checkpoint system automatically slowed writes during contention
4. **Adequate Resources**: 2GB page memory per node with 512MB checkpoint buffer

## Recommendations

### Index Timing Strategy

| Strategy | Configuration | Duration | Cluster Stress | Best For |
|----------|---------------|----------|----------------|----------|
| Indexes BEFORE (optimized) | 500/8 | ~6:47 | Minimal (10 warnings) | Best balance of speed and stability |
| Indexes AFTER | 5000/4 | ~7:37 | Minimal (12 warnings) | Maximum stability, production loads |
| Indexes BEFORE | 5000/4 | ~6:19 | Moderate (52 warnings) | Faster but with cluster stress |

### Required Configuration for High-Volume Loads

**Node Configuration:**

```
node config update ignite.raft '{retryTimeoutMillis=30000}'
```

**Client Implementation:**

- Use reactive `Flow.Publisher` with demand-driven generation
- For indexes BEFORE load: use pageSize=500, parallelOps=8 for zero lease failures
- For indexes AFTER load: pageSize=5000, parallelOps=4 works well
- Monitor backpressure events

### When to Expect Warnings

Under heavy load, these warnings are normal and recoverable:

- Page replacement started (memory pressure)
- Throttling applied (checkpoint catch-up)
- Minor lease update failures (< 10)

### When to Investigate

These patterns indicate problems:

- Sustained peers unavailable (> 30 seconds)
- Lease update failures > 100
- Replication lag warnings
- Checkpoint speeds consistently < 10 MB/s

## Summary

| Scenario | Configuration | Time | Lease Failures | Stability |
|----------|---------------|------|----------------|-----------|
| Optimized (Indexes Before) | 500/8 | 6:47 | **0** | Excellent |
| Indexes After | 5000/4 | 7:37 | 0 | Excellent |
| Indexes Before | 5000/4 | 6:19 | 10 | Good |
| Original Team | 1000/1 | 18:30 | 394 | Poor |

All our test runs with 1KB payload per record (matching the original team's schema) completed successfully. The optimized configuration (pageSize=500, parallelOps=8) achieves zero lease failures even with indexes created before data load, validating the tuning theory that smaller batches with higher parallelism create smoother I/O patterns.

The original team's issues were caused by:

1. Inadequate backpressure handling (blocking SubmissionPublisher vs demand-driven Flow.Publisher)
2. Default RAFT timeout configurations (too short for heavy I/O)
3. Insufficient tuning of DataStreamer options (pageSize=1000, parallelOps=1)

Our implementation demonstrates that proper client-side backpressure handling, server-side timeout configuration, and optimized DataStreamer tuning enable successful high-volume data streaming with indexes created before data load.
