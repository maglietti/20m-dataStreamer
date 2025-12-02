# DataStreamer Performance Analysis: Indexes Before Load

This document analyzes a DataStreamer operation with indexes created BEFORE data load, replicating the original test conditions that caused issues for another team.

## Test Configuration

| Parameter | Value |
|-----------|-------|
| Index Timing | BEFORE data load |
| Total Records | 20,000,000 |
| Page Size | 5,000 |
| Parallel Ops | 4 |
| RAFT retryTimeoutMillis | 30,000 ms |
| Cluster Size | 5 nodes |
| Storage Engine | aipersist (persistent) |

## Results Summary

| Metric | Value |
|--------|-------|
| Streaming Time | 57.53 seconds |
| Total Elapsed Time | 64.76 seconds |
| Average Rate | 308,836 events/sec |
| Peak Rate | 371,455 events/sec |
| Backpressure Events | 729 |
| Status | Success |

## Comparison: Index Timing Impact

| Metric | Indexes Before | Indexes After | Original Team |
|--------|----------------|---------------|---------------|
| Streaming Time | 57.53 sec | 26.04 sec | ~1,110 sec |
| Total Time | 64.76 sec | 80.19 sec | 1,110 sec |
| Average Rate | 308,836/sec | 249,419/sec | ~18,000/sec |
| Peak Rate | 371,455/sec | 845,800/sec | N/A |
| Backpressure Events | 729 | 371 | N/A |

### Observations

Creating indexes BEFORE data load:
- Increased streaming time by 2.2x (57.53 sec vs 26.04 sec)
- Reduced peak throughput by 56% (371K vs 845K events/sec)
- Generated 96% more backpressure events (729 vs 371)
- Total elapsed time was actually lower because index creation on empty table is faster

The index maintenance overhead during streaming is significant but the implementation handled it without errors.

## Critical Issue Analysis

### Issues from Original Team Run

| Issue | Original Team | This Run |
|-------|--------------|----------|
| Lease update timeouts | 28 | **0** |
| Primary replica changed errors | Multiple | **0** |
| Replication lag warnings | 986 | **0** |
| Lease update failures (outdated data) | 394 | **0** |

### Warning Analysis

Total warnings across all 5 nodes: **8**

All warnings are benign cluster startup messages:

| Warning Type | Count | Description |
|--------------|-------|-------------|
| Filtering out seed address | 5 | Each node filtering its own address from seed list |
| Exception on initial Sync | 3 | Brief connection refused during cluster formation |

No operational warnings occurred during data streaming or index maintenance.

## Checkpoint Performance

| Node | Pages Written | Write Time | Fsync Time | Total Time | Avg Speed |
|------|--------------|------------|------------|------------|-----------|
| node1 | 96,856 | 9,157ms | 44ms | 9,337ms | 162 MB/s |
| node2 | 96,458 | 9,000ms | 137ms | 9,292ms | 162 MB/s |
| node3 | 96,289 | 9,070ms | 22ms | 9,276ms | 162 MB/s |
| node4 | 96,706 | 10,226ms | 27ms | 10,400ms | 145 MB/s |
| node5 | 96,888 | 9,963ms | 28ms | 10,183ms | 149 MB/s |

### Comparison with Original Team

| Metric | Original Team | This Run |
|--------|--------------|----------|
| Checkpoint Speed | 11-66 MB/s | 145-162 MB/s |
| Fsync Time | 2,328-6,249ms | 22-137ms |
| Total Checkpoint Time | 2,601-6,397ms | 9,276-10,400ms |

Checkpoint throughput improved 2-15x compared to the original team's run. Fsync times dropped dramatically, indicating healthier I/O patterns.

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

The `retryTimeoutMillis=30000` setting is active and prevents lease update failures during I/O contention.

### Cluster Configuration

```
replication{
  leaseExpirationIntervalMillis=5000,
  rpcTimeoutMillis=60000
}
```

## Why This Run Succeeded

Despite creating indexes before data load (the same condition that caused issues for the original team), this run completed without errors due to:

1. **Proper Backpressure Handling**: Custom `Flow.Publisher` with demand-driven generation prevents memory pressure
2. **RAFT Retry Timeout**: 30-second timeout provides headroom for operations during I/O contention
3. **Resource Allocation**: Docker containers with 10GB memory and 4 CPUs per node
4. **Reactive Streaming**: DataStreamer API with appropriate page size and parallel operations

## Application Logs Summary

### Phase Timing

| Phase | Duration |
|-------|----------|
| Connect to cluster | 0.15 sec |
| Create table | 1.08 sec |
| Create indexes (before load) | 5.69 sec |
| Stream 20M records | 57.53 sec |
| Verify data | 0.23 sec |
| **Total** | **64.76 sec** |

### Progress During Streaming

| Time | Records Published | Rate | Backpressure | Heap Usage |
|------|-------------------|------|--------------|------------|
| 10s | 3,638,251 | 214,041/s | 44 | 2,724MB |
| 20s | 7,276,975 | 364,002/s | 164 | 8,519MB |
| 30s | 10,991,576 | 371,455/s | 284 | 5,159MB |
| 40s | 14,535,880 | 354,434/s | 455 | 7,763MB |
| 50s | 18,152,923 | 361,638/s | 640 | 4,339MB |

The heap usage fluctuated between 2.7GB and 8.5GB, showing active garbage collection and healthy memory management.

## Recommendations

### When to Create Indexes Before Load

Create indexes before load when:
- Query performance immediately after load is critical
- The performance penalty (2.2x slower streaming) is acceptable
- Proper backpressure handling is implemented

### When to Defer Index Creation

Defer index creation (after load) when:
- Maximum throughput is required
- Post-load index creation time is acceptable
- Reducing cluster stress during bulk operations is important

### Required Configuration for High-Volume Loads

Regardless of index timing, apply these settings:

**Node Configuration:**
```
node config update ignite.raft '{retryTimeoutMillis=30000}'
```

**Client Implementation:**
- Use reactive `Flow.Publisher` with demand-driven generation
- Configure appropriate page size (5,000-10,000)
- Set per-partition parallel operations (4-8)
- Implement backpressure monitoring

## Conclusion

Even with indexes created before data load (replicating the original team's conditions), this implementation completed the 20 million record load in 64.76 seconds with zero errors. The key factors enabling success were:

1. Reactive backpressure handling in the client application
2. Proper RAFT timeout configuration (30 seconds)
3. Adequate cluster resources

The original team's issues were caused by inadequate backpressure handling and default timeout configurations, not by index timing alone.
