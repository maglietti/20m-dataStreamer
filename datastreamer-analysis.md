# DataStreamer Performance Analysis: 20 Million Records

This document analyzes a DataStreamer operation that completed without errors, inserting 20 million records into a 5-node cluster with 3 replicas. The run succeeded but operated near the edge of failure, revealing configuration improvements for production deployments.

## Test Configuration

| Parameter | Value |
|-----------|-------|
| Cluster Size | 5 nodes |
| Replication Factor | 3 replicas |
| Storage Engine | aipersist (persistent) |
| Total Records | 20,000,000 |
| Batch Size | 1,000,000 records per batch |
| Number of Batches | 20 |

## Results

| Metric | Value |
|--------|-------|
| Total Time | 1,110 seconds (~18.5 minutes) |
| Throughput | ~18,000 records/second |
| Status | Success |

## Test Code Analysis

The test application streams synthetic data using the DataStreamer API. Below is an annotated breakdown of the code with improvement recommendations.

### Record Definition

```java
// Record class for table mapping
// Note: Class name should follow Java conventions (e.g., GgTestTableA)
public class ggTestTableA {
    String id;       // Primary key
    String column1;  // ~1KB payload
    int column2;     // Random integer (indexed)
    LocalDateTime column3; // Timestamp
}
```

### Main Application Structure

```java
public class Main {
    public static void main(String[] args) throws IOException {
        // Configuration
        String clusterUrl = "hostname:10800";
        String authUsername = "igniteuser";
        String authPassword = "";
        String zoneName = "AVAILABLEINTHREENODES";
        int batchsize = 1000000;      // 1 million records per batch
        int numberOfBatches = 20;      // 20 batches = 20 million total
```

**Improvement 1: Externalize Configuration**

Hard-coded values should be externalized for flexibility:

```java
// Read from environment or config file
String clusterUrl = System.getenv().getOrDefault("CLUSTER_URL", "localhost:10800");
int batchsize = Integer.parseInt(System.getenv().getOrDefault("BATCH_SIZE", "1000000"));
```

### Payload Generation

```java
        int numChars = 1024;
        StringBuilder sb = new StringBuilder(numChars);

        // Fill with repeated pattern
        for (int i = 0; i < numChars; i++) {
            sb.append('A');
        }

        String oneKbString = sb.toString();
```

This creates a 1KB string used as padding for each record. Combined with the primary key and other fields, each record is approximately 1.1KB.

### Schema Creation

```java
        String createTableDDL = "CREATE TABLE IF NOT EXISTS GG_TEST_TABLE_A " +
            "(ID VARCHAR PRIMARY KEY, COLUMN1 VARCHAR, COLUMN2 INT, COLUMN3 TIMESTAMP) " +
            "ZONE " + zoneName;
        String createIndexDDL = "CREATE INDEX IF NOT EXISTS GG_TEST_TABLE_A_COLUMN2 " +
            "ON GG_TEST_TABLE_A (COLUMN2)";

        // ... inside try block:
        client.sql().execute(null, createTableDDL);
        client.sql().execute(null, createIndexDDL);
```

**Improvement 2: Defer Index Creation**

Creating the index before data load means the index is updated during streaming, adding I/O overhead:

```java
// Create table first
client.sql().execute(null, createTableDDL);

// Load data...

// Create index AFTER data load completes
client.sql().execute(null, createIndexDDL);
```

This reduces I/O contention during the streaming phase.

### Client Connection

```java
        IgniteClientAuthenticator auth = BasicAuthenticator.builder()
            .username(authUsername)
            .password(authPassword)
            .build();

        try (IgniteClient client = IgniteClient.builder()
                .addresses(clusterUrl)
                .authenticator(auth)
                .build()
        ) {
```

The client connection is properly managed with try-with-resources.

### Batch Loop Structure

```java
            RecordView<ggTestTableA> view = client.tables().table("GG_TEST_TABLE_A")
                .recordView(ggTestTableA.class);
            long startTime = System.currentTimeMillis();

            int startKeyId;
            int endKeyId = batchsize;

            for(int j = 1 ; j <= numberOfBatches; j++) {

                if(j == 1){
                   startKeyId = 1;
                   endKeyId = batchsize;
                }else{
                    startKeyId = endKeyId;
                    endKeyId = endKeyId + batchsize;
                }
                System.out.println("Batch = " + j + " start = " + startKeyId + " endKeyId = " + endKeyId);
```

**Improvement 3: Simplify Key Range Calculation**

The key range logic can be refactored:

```java
for (int j = 0; j < numberOfBatches; j++) {
    int startKeyId = j * batchsize + 1;
    int endKeyId = (j + 1) * batchsize;
    System.out.println("Batch = " + (j + 1) + " start = " + startKeyId + " endKeyId = " + endKeyId);
```

### DataStreamer Configuration

```java
                DataStreamerOptions options = DataStreamerOptions.builder()
                        .pageSize(1000)
                        .perPartitionParallelOperations(1)
                        .autoFlushInterval(1000)
                        .retryLimit(16)
                        .build();
```

**Issue:** Options object is recreated inside each batch loop iteration.

**Improvement 4: Create Options Once**

```java
// Create once outside the loop
DataStreamerOptions options = DataStreamerOptions.builder()
        .pageSize(1000)
        .perPartitionParallelOperations(1)
        .autoFlushInterval(1000)
        .retryLimit(32)   // Increase for improved resilience
        .build();

for (int j = 0; j < numberOfBatches; j++) {
    // Use the same options object
```

### SubmissionPublisher Usage (Key Issue)

```java
                CompletableFuture<Void> streamerFut;

                try (var publisher = new SubmissionPublisher<DataStreamerItem<ggTestTableA>>()) {

                    streamerFut = view.streamData(publisher, options);
```

**Issue:** Default SubmissionPublisher constructor uses a 256-item buffer, which does not align with the DataStreamerOptions configuration.

**Improvement 5: Configure Buffer Size**

```java
// Calculate buffer size: partitions x pageSize x perPartitionParallelOperations
// For a zone with ~12 partitions: 12 x 1000 x 1 = 12,000
int bufferSize = 12_000;

try (var publisher = new SubmissionPublisher<DataStreamerItem<ggTestTableA>>(
        ForkJoinPool.commonPool(), bufferSize)) {

    streamerFut = view.streamData(publisher, options);
```

The buffer size should accommodate the expected in-flight items based on partition count and DataStreamerOptions settings.

### Record Submission Loop

```java
                    ThreadLocalRandom rnd = ThreadLocalRandom.current();

                    for (int i = startKeyId; i <= endKeyId; i++) {
                        String id = "Primary Key " + i;
                        String column1 = oneKbString + i;
                        int column2 = rnd.nextInt();
                        LocalDateTime ldt = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
                        ggTestTableA entry = new ggTestTableA(id, column1, column2, ldt);
                        publisher.submit(DataStreamerItem.of(entry));
                        if ( i % 10_000 == 0) {
                            System.out.println("Streamed " + i + " records.");
                        }
                    }
```

**Issue:** String concatenation `oneKbString + i` creates a new ~1KB string for each record.

**Improvement 6: Optimize String Building**

```java
// Pre-size StringBuilder to avoid reallocations
StringBuilder columnBuilder = new StringBuilder(numChars + 10);

for (int i = startKeyId; i <= endKeyId; i++) {
    columnBuilder.setLength(0);
    columnBuilder.append(oneKbString).append(i);

    String id = "Primary Key " + i;
    String column1 = columnBuilder.toString();
    int column2 = rnd.nextInt();
    LocalDateTime ldt = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);

    ggTestTableA entry = new ggTestTableA(id, column1, column2, ldt);
    publisher.submit(DataStreamerItem.of(entry));

    if (i % 10_000 == 0) {
        System.out.println("Streamed " + i + " records.");
    }
}
```

### Batch Completion

```java
                }
                streamerFut.join();
            }
```

**Improvement 7: Add Throttling Between Batches**

Adding a delay between batches allows the cluster to stabilize:

```java
                }
                streamerFut.join();

                // Allow cluster to catch up between batches
                if (j < numberOfBatches) {
                    System.out.println("Batch " + j + " complete. Pausing before next batch...");
                    Thread.sleep(2000);
                }
            }
```

This reduces sustained I/O pressure that caused lease update timeouts.

### Timing Output

```java
            long endTime = System.currentTimeMillis();
            System.out.println("Total time " +  (endTime - startTime) / 1000);
```

**Improvement 8: Add Per-Batch Metrics**

```java
long batchStartTime = System.currentTimeMillis();

// ... batch processing ...

streamerFut.join();
long batchEndTime = System.currentTimeMillis();
long batchDuration = batchEndTime - batchStartTime;
double batchThroughput = (double) batchsize / (batchDuration / 1000.0);
System.out.printf("Batch %d complete: %d records in %.1f seconds (%.0f records/sec)%n",
    j, batchsize, batchDuration / 1000.0, batchThroughput);
```

### Complete Improved Version

```java
package com.gridgain.utilities;

import org.apache.ignite.client.BasicAuthenticator;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.client.IgniteClientAuthenticator;
import org.apache.ignite.table.DataStreamerItem;
import org.apache.ignite.table.DataStreamerOptions;
import org.apache.ignite.table.RecordView;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.ThreadLocalRandom;

public class DataStreamerTest {

    public static void main(String[] args) throws Exception {
        // Externalized configuration
        String clusterUrl = System.getenv().getOrDefault("CLUSTER_URL", "hostname:10800");
        String authUsername = System.getenv().getOrDefault("AUTH_USER", "igniteuser");
        String authPassword = System.getenv().getOrDefault("AUTH_PASS", "");
        String zoneName = System.getenv().getOrDefault("ZONE_NAME", "AVAILABLEINTHREENODES");
        int batchSize = Integer.parseInt(System.getenv().getOrDefault("BATCH_SIZE", "1000000"));
        int numberOfBatches = Integer.parseInt(System.getenv().getOrDefault("NUM_BATCHES", "20"));
        int pauseBetweenBatchesMs = Integer.parseInt(System.getenv().getOrDefault("BATCH_PAUSE_MS", "2000"));

        // Create 1KB payload template
        String oneKbString = "A".repeat(1024);

        // DDL statements
        String createTableDDL = "CREATE TABLE IF NOT EXISTS GG_TEST_TABLE_A " +
            "(ID VARCHAR PRIMARY KEY, COLUMN1 VARCHAR, COLUMN2 INT, COLUMN3 TIMESTAMP) " +
            "ZONE " + zoneName;
        String createIndexDDL = "CREATE INDEX IF NOT EXISTS GG_TEST_TABLE_A_COLUMN2 " +
            "ON GG_TEST_TABLE_A (COLUMN2)";

        IgniteClientAuthenticator auth = BasicAuthenticator.builder()
            .username(authUsername)
            .password(authPassword)
            .build();

        try (IgniteClient client = IgniteClient.builder()
                .addresses(clusterUrl)
                .authenticator(auth)
                .build()) {

            // Create table (defer index creation until after load)
            client.sql().execute(null, createTableDDL);

            RecordView<GgTestTableA> view = client.tables()
                .table("GG_TEST_TABLE_A")
                .recordView(GgTestTableA.class);

            // Create options once, outside the loop
            DataStreamerOptions options = DataStreamerOptions.builder()
                    .pageSize(1000)
                    .perPartitionParallelOperations(1)
                    .autoFlushInterval(1000)
                    .retryLimit(32)
                    .build();

            // Buffer size aligned with options: partitions x pageSize x parallelOps
            int bufferSize = 12_000;

            long totalStartTime = System.currentTimeMillis();
            long totalRecords = 0;

            for (int batch = 0; batch < numberOfBatches; batch++) {
                int startKeyId = batch * batchSize + 1;
                int endKeyId = (batch + 1) * batchSize;

                System.out.printf("Batch %d: keys %d to %d%n", batch + 1, startKeyId, endKeyId);
                long batchStartTime = System.currentTimeMillis();

                CompletableFuture<Void> streamerFut;

                try (var publisher = new SubmissionPublisher<DataStreamerItem<GgTestTableA>>(
                        ForkJoinPool.commonPool(), bufferSize)) {

                    streamerFut = view.streamData(publisher, options);

                    ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    StringBuilder columnBuilder = new StringBuilder(1034);

                    for (int i = startKeyId; i <= endKeyId; i++) {
                        columnBuilder.setLength(0);
                        columnBuilder.append(oneKbString).append(i);

                        GgTestTableA entry = new GgTestTableA(
                            "Primary Key " + i,
                            columnBuilder.toString(),
                            rnd.nextInt(),
                            LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS)
                        );

                        publisher.submit(DataStreamerItem.of(entry));

                        if (i % 100_000 == 0) {
                            System.out.printf("  Progress: %,d records%n", i);
                        }
                    }
                }

                streamerFut.join();
                totalRecords += batchSize;

                long batchDuration = System.currentTimeMillis() - batchStartTime;
                double batchThroughput = (double) batchSize / (batchDuration / 1000.0);
                System.out.printf("Batch %d complete: %,d records in %.1f sec (%.0f rec/sec)%n",
                    batch + 1, batchSize, batchDuration / 1000.0, batchThroughput);

                // Pause between batches to reduce sustained I/O pressure
                if (batch < numberOfBatches - 1 && pauseBetweenBatchesMs > 0) {
                    System.out.printf("Pausing %d ms before next batch...%n", pauseBetweenBatchesMs);
                    Thread.sleep(pauseBetweenBatchesMs);
                }
            }

            // Create index after data load completes
            System.out.println("Creating index...");
            long indexStartTime = System.currentTimeMillis();
            client.sql().execute(null, createIndexDDL);
            System.out.printf("Index created in %.1f sec%n",
                (System.currentTimeMillis() - indexStartTime) / 1000.0);

            long totalDuration = System.currentTimeMillis() - totalStartTime;
            double avgThroughput = (double) totalRecords / (totalDuration / 1000.0);
            System.out.printf("%nTotal: %,d records in %.1f sec (%.0f rec/sec average)%n",
                totalRecords, totalDuration / 1000.0, avgThroughput);
        }
    }
}
```

### Summary of Improvements

| Issue | Original Code | Improvement | Impact |
|-------|--------------|-------------|--------|
| SubmissionPublisher buffer | Default 256 items | Explicit 12,000 items | Improved backpressure alignment |
| Index creation timing | Before data load | After data load | Reduces I/O during streaming |
| DataStreamerOptions | Created per batch | Created once | Minor efficiency gain |
| String concatenation | `oneKbString + i` | StringBuilder reuse | Reduces garbage collection |
| Batch throttling | None | 2-second pause | Allows cluster to stabilize |
| retryLimit | 16 | 32 | Increased resilience to transient failures |
| Progress reporting | Every 10K records | Every 100K records | Reduces console I/O |
| Configuration | Hard-coded | Environment variables | Deployment flexibility |
| Metrics | Total time only | Per-batch throughput | Increased visibility |

## Reference Implementation: Improved DataStreamer Application

Based on patterns from production-ready reference applications, an improved implementation has been created that addresses all the issues identified above and adds monitoring capabilities.

### Architecture Overview

The improved implementation consists of four classes:

| File | Purpose |
|------|---------|
| `ImprovedDataStreamerTest.java` | Main application with phase-based execution |
| `SyntheticDataPublisher.java` | Custom Flow.Publisher with demand-driven generation |
| `StreamingMetrics.java` | Metrics tracking |
| `ManagedExecutorService.java` | AutoCloseable executor wrapper for resource cleanup |

### Key Improvements Over Original

**1. Custom Flow.Publisher with Reactive Backpressure**

Instead of `SubmissionPublisher` with fixed buffer, `SyntheticDataPublisher` implements `Flow.Publisher` directly:

```java
// Demand-driven generation: only generates when subscriber requests data
@Override
public void request(long n) {
    metrics.recordEventsRequested(n);
    demand.addAndGet(n);
    deliverItems();
}
```

This prevents memory bloat by generating records only when the DataStreamer can consume them.

**2. Managed Executor Lifecycle**

`ManagedExecutorService` wraps `ExecutorService` with AutoCloseable:

```java
try (var executor = ManagedExecutorService.newFixedThreadPool(2, "publisher")) {
    // Use executor
} // Automatic graceful shutdown
```

**3. Metrics Collection**

`StreamingMetrics` tracks:
- Events generated, published, and requested
- Backpressure events (when demand reaches zero)
- Current and peak throughput rates
- Batch completion times
- Heap memory usage
- System CPU load

**4. Deferred Index Creation**

Schema setup creates tables without indexes:

```java
// Phase 2: Create table WITHOUT indexes
prepareSchema(client);

// Phase 3: Stream data
streamData(client, metrics);

// Phase 4: Create indexes AFTER data load
createIndexes(client);
```

**5. Configuration via Environment Variables**

All parameters are externalized:

| Variable | Default | Description |
|----------|---------|-------------|
| `IGNITE_CONNECT_ADDRESS` | 127.0.0.1:10800 | Cluster connection |
| `RECORD_COUNT` | 20,000,000 | Total records to stream |
| `PAGE_SIZE` | 5,000 | DataStreamer page size |
| `PARALLEL_OPS` | 4 | Per-partition parallel operations |
| `BUFFER_SIZE` | 20,000 | Publisher buffer size |
| `MONITOR_INTERVAL_SECONDS` | 10 | Progress report interval |

**6. Phase-Based Progress Monitoring**

Clear output structure for tracking:

```
=== [1/4] Connecting to Cluster ===
<<< Connected to 127.0.0.1:10800

=== [2/4] Preparing Schema ===
>>> Creating table without indexes
<<< Table 'TestTable' created (indexes deferred)

=== [3/4] Streaming Data ===
--- Published: 1,000,000 | Rate: 45,000/s (peak: 48,000/s) | Backpressure: 12 | Heap: 512MB/2048MB
```

### Running the Improved Application

**Compile:**

```bash
javac -cp ignite-client-3.x.x.jar:. *.java
```

**Run with defaults:**

```bash
java -cp ignite-client-3.x.x.jar:. com.example.datastreamer.ImprovedDataStreamerTest
```

**Run with custom configuration:**

```bash
RECORD_COUNT=5000000 PAGE_SIZE=10000 PARALLEL_OPS=8 \
java -cp ignite-client-3.x.x.jar:. com.example.datastreamer.ImprovedDataStreamerTest
```

### Files Location

The improved implementation files are located in the same directory as this analysis:

- `ImprovedDataStreamerTest.java`
- `SyntheticDataPublisher.java`
- `StreamingMetrics.java`
- `ManagedExecutorService.java`

## Problem Background

Initial attempts to load data failed around 999,000 records with the error:

```
IGN-CMN-65535 The primary replica has changed
[expectedEnlistmentConsistencyToken=X, currentEnlistmentConsistencyToken=Y]
```

### Root Cause

The `enlistmentConsistencyToken` is derived from the lease's start time. When heavy DataStreamer I/O caused lease update operations to exceed timeout thresholds, leases expired and were recreated with new tokens. In-flight transactions using the old token failed.

### Resolution

The team applied the following configuration change:

```
node config update ignite.raft '{retryTimeoutMillis=30000}'
```

This increased the RAFT retry timeout from the default 10 seconds to 30 seconds, giving operations sufficient time to complete during I/O contention.

## Log Analysis

### Key Finding: Lease Update Timeouts Still Occurred

Despite the run completing without errors, the logs contain **28 instances** of this warning:

```
Lease update invocation took longer than lease interval [duration=X, leaseInterval=10000]
```

**Worst observed durations:**

| Duration (ms) | Multiple of Lease Interval |
|---------------|----------------------------|
| 23,707 | 2.4x |
| 17,598 | 1.8x |
| 15,289 | 1.5x |
| 14,789 | 1.5x |
| 14,391 | 1.4x |
| 13,597 | 1.4x |

The longest lease update took **23.7 seconds** to complete when the lease interval is 10 seconds. The run succeeded because the RAFT retry timeout was set to 30 seconds. Without this configuration change, these operations would have failed.

### Replication Lag

The logs show **986 instances** of replication lag warnings across the cluster:

```
Received entries of which the lastLog=X is not greater than appliedIndex=Y
```

**Distribution by node:**

| Node | Replication Lag Warnings |
|------|--------------------------|
| Node 3 | 767 |
| Node 0 | 121 |
| Node 2 | 79 |
| Node 4 | 19 |
| Node 1 | 0 |

Node 3 experienced elevated replication lag, receiving 78% of all lag warnings. This indicates the node was struggling to keep up with the write load.

### Lease Update Failures (Expected Behavior)

Node 1 logged **394 instances** of:

```
Lease update invocation failed because of outdated lease data on this node
```

These are expected retry messages. The lease data was stale when the update was attempted, triggering a retry. The increased RAFT retry timeout allowed these retries to succeed.

### Checkpoint Performance

Checkpoint operations showed the following characteristics:

| Checkpoint | Pages | Write Time | Fsync Time | Total Time | Speed |
|------------|-------|------------|------------|------------|-------|
| 1 | 11,057 | 235ms | 2,328ms | 2,601ms | 66 MB/s |
| 2 | 16,341 | 120ms | 6,249ms | 6,397ms | 40 MB/s |
| 3 | 3,024 | 48ms | 4,222ms | 4,282ms | 11 MB/s |

Fsync time accounts for approximately 90% of checkpoint duration. Disk I/O is the primary bottleneck.

## Risk Assessment

The run masked underlying issues that could cause failures under different conditions:

| Risk Factor | Observed Value | Threshold | Margin |
|-------------|----------------|-----------|--------|
| Max lease update duration | 23,707ms | 30,000ms (retry timeout) | 6.3 seconds |
| Lease updates exceeding interval | 28 occurrences | - | N/A |
| Replication lag events | 986 occurrences | - | N/A |

The system operated with 6.3 seconds of margin before the worst lease update would have exceeded the retry timeout. Any additional I/O pressure could push operations over the threshold.

## Configuration Recommendations

### Required for Production DataStreamer Workloads

**1. RAFT Retry Timeout (Node Configuration)**

```
node config update ignite.raft '{retryTimeoutMillis=30000}'
```

This setting must be applied to each node and requires a restart. The test demonstrated this configuration is required.

**2. Lease Expiration Interval (Cluster Configuration)**

```
cluster config update ignite.replication.leaseExpirationIntervalMillis=30000
```

Increase from the default 10 seconds to 30 seconds. This provides additional headroom for lease renewal during I/O contention. The current 10-second interval was exceeded 28 times during this test.

## Monitoring Recommendations

### Warnings to Alert On

**High priority:**

```
Lease update invocation took longer than lease interval [duration=X, leaseInterval=Y]
```

This warning appeared 28 times during the test. When `duration` exceeds `leaseInterval`, the system is under stress. When `duration` approaches `raft.retryTimeoutMillis`, failures become likely.

**Transaction failures:**

```
The primary replica has changed [expectedEnlistmentConsistencyToken=X, currentEnlistmentConsistencyToken=Y]
```

This error indicates a lease expired during a transaction. Increase timeouts or reduce load.

### Performance Indicators

**Replication health:**

```
Received entries of which the lastLog=X is not greater than appliedIndex=Y
```

Frequent occurrences indicate follower nodes are falling behind. Node 3 showed 767 instances, suggesting it may need additional resources or faster storage.

**Checkpoint performance:**

Monitor `avgWriteSpeed` in checkpoint completion messages. Speeds below 20 MB/s indicate I/O bottlenecks.

## Infrastructure Considerations

### Disk I/O

The fsync times (2-6 seconds) and lease update durations (up to 24 seconds) indicate disk I/O constraints. Consider:

- Using NVMe storage for improved IOPS
- Separating RAFT log storage from data storage
- Monitoring disk utilization during streaming operations

### Node Capacity

Node 3 showed more replication lag than other nodes. Investigate:

- Disk performance on this node
- Network latency between nodes
- CPU utilization during streaming

## Summary

The 20 million record load succeeded but revealed the system was operating near its limits:

- 28 lease update operations exceeded the lease interval
- The longest lease update took 23.7 seconds (2.4x the lease interval)
- 986 replication lag warnings occurred, concentrated on Node 3
- The run succeeded because `raft.retryTimeoutMillis=30000` provided sufficient headroom

For production stability, apply both configuration changes:

```
node config update ignite.raft '{retryTimeoutMillis=30000}'
cluster config update ignite.replication.leaseExpirationIntervalMillis=30000
```

Monitor for the warnings identified above and consider infrastructure improvements to reduce I/O contention.
