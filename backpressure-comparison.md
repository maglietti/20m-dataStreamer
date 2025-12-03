# DataStreamer Backpressure: SubmissionPublisher vs Custom Flow.Publisher

This document explains why a custom `Flow.Publisher` implementation handles high-volume streaming better than `SubmissionPublisher`.

## The Problem

When streaming 20 million records with 1KB payloads, the producer generates data faster than the cluster can persist it. Without proper backpressure, this mismatch causes buffer overflow, memory exhaustion, and cluster instability.

## Two Approaches

### Original: SubmissionPublisher with Blocking Submit

```java
try (var publisher = new SubmissionPublisher<DataStreamerItem<ggTestTableA>>()) {
    streamerFut = view.streamData(publisher, options);

    for (int i = startKeyId; i <= endKeyId; i++) {
        ggTestTableA entry = new ggTestTableA(id, column1, column2, ldt);
        publisher.submit(DataStreamerItem.of(entry));  // Blocks when buffer full
    }
}
```

`SubmissionPublisher.submit()` blocks when its internal buffer fills up. This creates backpressure, but the blocking happens at the JVM level with no awareness of downstream capacity. The producer keeps generating records and pushing them into the buffer until it blocks.

### Custom: Demand-Driven Flow.Publisher

```java
public void request(long n) {
    demand.addAndGet(n);
    deliverItems();
}

private void deliverItemsSynchronously() {
    while (demand.get() > 0 && !cancelled.get()) {
        DataStreamerItem<Tuple> item = generateItem(currentGenerated);
        subscriber.onNext(item);
        demand.decrementAndGet();
    }
}
```

The custom publisher generates records only when the DataStreamer requests them. No record is created until there is confirmed capacity to process it.

## Why This Matters

| Aspect | SubmissionPublisher | Custom Flow.Publisher |
|--------|---------------------|----------------------|
| Record generation | Continuous, pushes into buffer | On-demand, pulls when ready |
| Memory usage | Buffer fills, then blocks | Constant, matches consumption |
| Backpressure signal | Buffer full (reactive) | Demand count (proactive) |
| Producer awareness | None until blocked | Knows exact downstream capacity |

### Buffer Behavior

**SubmissionPublisher**: Generates 1M records per batch into a fixed buffer. When the buffer fills (default 256 items), `submit()` blocks. The producer has already allocated memory for records that cannot be processed yet.

**Custom Publisher**: The DataStreamer requests items (e.g., 500 at a time based on pageSize). The publisher generates exactly 500 records, sends them, then waits. No excess records exist in memory.

### Under Heavy Load

When the cluster slows due to checkpoint pressure or RAFT consensus delays:

**SubmissionPublisher**: Continues generating records until buffer blocks. Creates memory pressure on the client. When unblocked, dumps buffered records causing I/O spikes on the cluster.

**Custom Publisher**: Receives fewer `request()` calls from the DataStreamer. Generates fewer records. Client and cluster stay synchronized without intervention.

## Implementation Differences

### Original Application Structure

1. Create `SubmissionPublisher`
2. Start `streamData()`
3. Loop through all records, calling `submit()` for each
4. Close publisher, wait for completion
5. Repeat for next batch

The tight loop pushes records as fast as the CPU can generate them. The only throttle is buffer exhaustion.

### Custom Publisher Structure

1. Create custom `Flow.Publisher` with target record count
2. Start `streamData()` which triggers `subscribe()`
3. DataStreamer calls `request(n)` when ready for more records
4. Publisher generates exactly `n` records and delivers them
5. Repeat until target count reached, then call `onComplete()`

The DataStreamer controls the pace. The publisher responds to demand rather than creating it.

## Configuration Impact

The original used `pageSize=1000, parallelOps=1`. With blocking backpressure, larger batches cause longer blocks and bigger I/O spikes when unblocked.

The custom publisher works well with `pageSize=500, parallelOps=8`. Smaller batches with higher parallelism create steady demand signals, resulting in smooth I/O patterns that the cluster checkpoint system can handle.

## Summary

`SubmissionPublisher` provides backpressure through blocking. This works for moderate loads but creates problems at scale because the producer has no visibility into downstream capacity.

A custom `Flow.Publisher` implements the Reactive Streams pattern where the subscriber (DataStreamer) explicitly requests items. This demand-driven approach keeps the producer and consumer synchronized, preventing the buffer overflow and I/O spikes that caused lease failures in the original implementation.
