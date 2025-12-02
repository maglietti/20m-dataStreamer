# DataStreamer Performance Test

High-volume DataStreamer test application for GridGain 9.1.14 demonstrating proper backpressure handling and reactive streaming patterns.

## Overview

This application tests DataStreamer throughput by streaming synthetic data to a GridGain cluster. It implements demand-driven data generation using a custom `Flow.Publisher` that respects backpressure signals from the cluster.

## Requirements

- Java 17+
- Maven 3.8+
- Docker and Docker Compose (for local cluster)
- GridGain 9.1.14

### Docker Desktop Resources (macOS)

The 5-node cluster requires significant resources. Each node allocates 4 GB heap and 4 GB direct memory.

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| Memory | 40 GB | 44-48 GB |
| CPUs | 8 | 10-12 |
| Swap | 2 GB | 4 GB |
| Disk | 30 GB | 50 GB |

Configure in Docker Desktop: Settings → Resources → Advanced

For Macs with less than 64 GB RAM, reduce the cluster to 3 nodes or lower per-node memory settings in `docker-compose.yml`.

## Project Structure

```
.
├── docker-compose.yml                    # 5-node GridGain cluster
├── cluster-init.sh                       # Initialize cluster with license
├── cluster-state.sh                      # Check cluster state
├── cluster-config.sh                     # View cluster configuration
├── license.example.conf                  # Example license format (no secrets)
├── pom.xml                               # Maven build configuration
├── src/main/java/com/gridgain/examples/datastreamer/
│   ├── DataStreamerTest.java             # Main application
│   ├── SyntheticDataPublisher.java       # Custom Flow.Publisher
│   ├── StreamingMetrics.java             # Metrics tracking
│   └── ManagedExecutorService.java       # Executor lifecycle management
└── src/main/resources/
    └── log4j2.xml                        # Logging configuration
```

## Quick Start

### 1. Start the GridGain Cluster

```bash
docker compose up -d
```

Wait for all nodes to start (check with `docker compose ps`).

### 2. Set Up Your License

Copy your GridGain license file to the project directory:

```bash
cp /path/to/your/license.conf my-gridgain-9.1.license.conf
```

The license file is gitignored. See `license.example.conf` for the expected format.

### 3. Initialize the Cluster

```bash
./cluster-init.sh
```

This script:
- Reads the license from `my-gridgain-9.1.license.conf`
- Initializes the cluster via the REST API
- Displays the cluster state

Other helper scripts:
- `./cluster-state.sh` - Check cluster state
- `./cluster-config.sh` - View cluster configuration

### 4. Build the Application

```bash
mvn clean package
```

### 5. Run the Test

With defaults (20 million records):

```bash
mvn exec:java
```

With custom configuration:

```bash
RECORD_COUNT=1000000 PAGE_SIZE=10000 mvn exec:java
```

Or run the JAR directly:

```bash
java -jar target/datastreamer-test-1.0.0-SNAPSHOT.jar
```

## Configuration

Configure the test via environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `IGNITE_CONNECT_ADDRESS` | 127.0.0.1:10800 | Cluster client endpoint |
| `RECORD_COUNT` | 20,000,000 | Total records to stream |
| `PAGE_SIZE` | 5,000 | DataStreamer page size |
| `PARALLEL_OPS` | 4 | Per-partition parallel operations |
| `MONITOR_INTERVAL_SECONDS` | 10 | Progress report interval |

## Architecture

### Custom Flow.Publisher

The `SyntheticDataPublisher` implements `Flow.Publisher<DataStreamerItem<Tuple>>` with demand-driven generation:

- Records are generated when the subscriber requests them via `request(n)`
- Prevents memory bloat by matching production rate to consumption capacity
- Tracks backpressure events when demand reaches zero

### Metrics Collection

`StreamingMetrics` tracks:

- Events published and requested
- Backpressure events
- Current and peak throughput rates
- Heap memory usage

### Executor Management

`ManagedExecutorService` wraps `ExecutorService` with `AutoCloseable` for proper resource cleanup using try-with-resources.

## Cluster Configuration

The docker-compose configuration includes RAFT retry timeout settings for high-throughput workloads:

```
ignite.raft {
  retryTimeoutMillis = 30000
}
```

This prevents "primary replica has changed" errors during sustained I/O load.

## Output

The application outputs phase-based progress:

```
=== DataStreamer Test Configuration ===
    Connect Address: 127.0.0.1:10800
    Record Count:    20,000,000
    Page Size:       5,000
    Parallel Ops:    4
    Monitor Interval: 10 seconds

=== [1/4] Connecting to Cluster ===
<<< Connected to 127.0.0.1:10800

=== [2/4] Preparing Schema ===
>>> Dropping existing table if present
>>> Creating table without indexes
<<< Table 'TestTable' created (indexes deferred)

=== [3/4] Streaming Data ===
>>> DataStreamer configured: pageSize=5000, parallelOps=4
>>> Starting stream of 20,000,000 records
--- Published: 2,500,000 | Rate: 45,000/s (peak: 48,000/s) | Backpressure: 12 | Heap: 512MB/2048MB
...
<<< Streaming completed in 445.23 seconds

=== [4/4] Creating Indexes ===
>>> Creating secondary indexes
    Created index: idx_testtable_name
    Created index: idx_testtable_value
<<< Indexes created in 23.45 seconds

=== DataStreamer Metrics Report ===
    Total Events Published: 20,000,000
    Total Events Requested: 20,000,000
    Backpressure Events:    1,234
    Elapsed Time:           468.68 seconds
    Average Rate:           42,669 events/sec
    Peak Rate:              48,123 events/sec
===================================
```

## Cleanup

Stop and remove the cluster:

```bash
docker compose down -v
```

Remove data volumes:

```bash
rm -rf data/
```

## Troubleshooting

### Connection Refused

Ensure the cluster is initialized and nodes are running:

```bash
docker compose ps
docker logs gridgain9-datastreamer-test-node1-1
```

### Primary Replica Changed Errors

Increase RAFT retry timeout in the cluster configuration. The docker-compose already sets `retryTimeoutMillis=30000`.

### Out of Memory

Reduce `RECORD_COUNT` or increase heap size:

```bash
MAVEN_OPTS="-Xmx4g" mvn exec:java
```

## Analysis

For detailed analysis of DataStreamer performance and configuration recommendations, see `datastreamer-analysis.md`.
