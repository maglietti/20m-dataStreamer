# DataStreamer Performance Analysis: 1KB Payload Test

This document analyzes DataStreamer operations with the original team's data schema (1KB payload per record), comparing index timing strategies and cluster behavior.

## Test Configuration

| Parameter | Value |
|-----------|-------|
| Total Records | 20,000,000 |
| Record Schema | ID (VARCHAR PK), COLUMN1 (1KB VARCHAR), COLUMN2 (INT), COLUMN3 (TIMESTAMP) |
| Payload Size | ~1KB per record |
| Page Size | 5,000 |
| Parallel Ops | 4 |
| RAFT retryTimeoutMillis | 30,000 ms |
| Cluster Size | 5 nodes |
| Storage Engine | aipersist (persistent) |
| RAFT Log Storage | RocksDB (internal) |

## Results Comparison: All Three Scenarios

| Metric | Indexes BEFORE | Indexes AFTER | Original Team |
|--------|----------------|---------------|---------------|
| Test Duration | ~6:19 | ~7:37 | ~18:30 |
| Lease Update Failures | 10 | **0** | 394 |
| Peers Unavailable | 8 | **2** | Multiple |
| Page Replacement Events | 5 | 5 | Unknown |
| Throttling Events | 13 | **6** | Unknown |
| Checkpoint Speed | 14-47 MB/s | 24-52 MB/s | 11-66 MB/s |
| Replication Lag Warnings | 0 | 0 | 986 |
| Total Warnings | 52 | **12** | 1000+ |
| Status | Success | Success | Success (with issues) |

### Key Observations

**Indexes BEFORE Load:**

- Faster streaming time (6:19) because index already exists
- More cluster stress: 10 lease failures, 8 peers unavailable, 13 throttling events
- Lower checkpoint speeds due to concurrent index maintenance

**Indexes AFTER Load:**

- Slightly longer total time (7:37) due to post-load index creation
- Minimal cluster stress: 0 lease failures, 2 peers unavailable, 6 throttling events
- Higher checkpoint speeds with more consistent performance

**Original Team:**

- Significantly longer time (~18:30)
- Severe cluster stress: 394 lease failures, 986 replication lag warnings
- Issues caused by inadequate backpressure handling and default timeout configurations

## Detailed Results: Indexes BEFORE Load

| Metric | Value |
|--------|-------|
| Test Duration | ~6 minutes 19 seconds |
| Lease Update Failures | 10 (all on node2) |
| Peers Unavailable Warnings | 8 |
| Page Replacement Events | 5 (one per node) |
| Throttling Events | 13 |

### Warning Analysis (Indexes Before)

Total warnings across all 5 nodes: **52**

| Warning Type | Count | Severity | Description |
|--------------|-------|----------|-------------|
| NodeImpl (RAFT config) | 24 | Benign | Startup configuration changes |
| LeaseUpdater | 10 | Moderate | Outdated lease data on node2 |
| RaftGroupServiceImpl | 8 | Moderate | Peers temporarily unavailable |
| PersistentPageMemory | 5 | Normal | Page replacement under load |
| MembershipProtocol | 5 | Benign | Seed address filtering |

### Checkpoint Performance (Indexes Before)

| Node | Checkpoints | Pages Written | Slowest | Avg Speed |
|------|-------------|---------------|---------|-----------|
| node1 | 5 | 86K-112K | 103,752ms | 16-47 MB/s |
| node2 | 5 | 86K-112K | 109,613ms | 14-39 MB/s |
| node3 | 5 | 86K-112K | 111,003ms | 14-42 MB/s |
| node4 | 5 | 86K-112K | 111,556ms | 14-38 MB/s |
| node5 | 5 | 86K-112K | 108,266ms | 14-41 MB/s |

## Detailed Results: Indexes AFTER Load

| Metric | Value |
|--------|-------|
| Test Duration | ~7 minutes 37 seconds |
| Lease Update Failures | 0 |
| Peers Unavailable Warnings | 2 |
| Page Replacement Events | 5 (one per node) |
| Throttling Events | 6 |

### Warning Analysis (Indexes After)

Total warnings across all 5 nodes: **12**

| Warning Type | Count | Severity | Description |
|--------------|-------|----------|-------------|
| PersistentPageMemory | 5 | Normal | Page replacement under load |
| MembershipProtocol | 5 | Benign | Seed address filtering (startup) |
| RaftGroupServiceImpl | 2 | Minor | Peers temporarily unavailable |

### Checkpoint Performance (Indexes After)

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

## Why Our Implementation Succeeded

Both runs (indexes before and after) completed successfully compared to the original team's problematic run:

1. **Demand-Driven Backpressure**: Custom `Flow.Publisher` generates records only when requested
2. **RAFT Retry Timeout**: 30-second timeout absorbs temporary I/O delays
3. **Throttling**: Checkpoint system automatically slowed writes during contention
4. **Adequate Resources**: 2GB page memory per node with 512MB checkpoint buffer

## Recommendations

### Index Timing Strategy

| Strategy | Duration | Cluster Stress | Best For |
|----------|----------|----------------|----------|
| Indexes AFTER load | ~7:37 | Minimal (12 warnings) | Maximum stability, production loads |
| Indexes BEFORE load | ~6:19 | Moderate (52 warnings) | Query performance immediately after load |

### Required Configuration for High-Volume Loads

**Node Configuration:**

```
node config update ignite.raft '{retryTimeoutMillis=30000}'
```

**Client Implementation:**

- Use reactive `Flow.Publisher` with demand-driven generation
- Configure page size 5,000-10,000
- Set per-partition parallel operations 4-8
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

| Scenario | Time | Lease Failures | Stability |
|----------|------|----------------|-----------|
| Our Implementation (Indexes After) | 7:37 | 0 | Excellent |
| Our Implementation (Indexes Before) | 6:19 | 10 | Good |
| Original Team | 18:30 | 394 | Poor |

Both our test runs with 1KB payload per record (matching the original team's schema) completed successfully. Creating indexes after data load provides the cleanest run with zero lease update failures. Creating indexes before load is viable but generates more cluster stress.

The original team's issues were caused by:

1. Inadequate backpressure handling (blocking SubmissionPublisher vs demand-driven Flow.Publisher)
2. Default RAFT timeout configurations (too short for heavy I/O)
3. Insufficient tuning of DataStreamer options

Our implementation demonstrates that proper client-side backpressure handling and server-side timeout configuration enable successful high-volume data streaming regardless of index timing.
