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

Configure in Docker Desktop: Settings > Resources > Advanced

For Macs with less than 64 GB RAM, reduce the cluster to 3 nodes or lower per-node memory settings in `docker-compose.yml`.

## Project Structure

```
.
├── docker-compose.yml                    # 5-node GridGain cluster
├── pom.xml                               # Maven build configuration
├── config/
│   └── gridgain-logging.properties       # GridGain node logging config
├── scripts/
│   ├── cluster-init.sh                   # Initialize cluster with license
│   └── fetch-node-logs.sh                # Fetch logs from Docker containers
├── logs/
│   ├── app/                              # Application logs
│   │   ├── datastreamer.log              # DataStreamer application logs
│   │   ├── ignite-client.log             # Ignite client connection logs
│   │   └── all.log                       # Combined logs
│   └── node{1-5}/                        # GridGain node logs (via script)
├── src/main/java/com/gridgain/examples/datastreamer/
│   ├── DataStreamerTest.java             # Main application
│   ├── SyntheticDataPublisher.java       # Custom Flow.Publisher
│   ├── StreamingMetrics.java             # Metrics tracking
│   └── ManagedExecutorService.java       # Executor lifecycle management
└── src/main/resources/
    └── log4j2.xml                        # Application logging configuration
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
./scripts/cluster-init.sh
```

This script:
- Reads the license from `my-gridgain-9.1.license.conf`
- Initializes the cluster via the REST API
- Displays the cluster state

### 4. Build the Application

```bash
mvn clean package
```

### 5. Run the Test

With defaults (20 million records):

```bash
mvn exec:exec
```

With custom configuration:

```bash
RECORD_COUNT=1000000 PAGE_SIZE=10000 mvn exec:exec
```

Or run the JAR directly:

```bash
java -Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager \
     -jar target/datastreamer-test-1.0.0-SNAPSHOT.jar
```

## Configuration

Configure the test via environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `IGNITE_CONNECT_ADDRESSES` | 127.0.0.1:10800,...10804 | Comma-separated cluster endpoints |
| `RECORD_COUNT` | 20,000,000 | Total records to stream |
| `PAGE_SIZE` | 5,000 | DataStreamer page size |
| `PARALLEL_OPS` | 4 | Per-partition parallel operations |
| `MONITOR_INTERVAL_SECONDS` | 10 | Progress report interval |

## Logging

The application uses SLF4J with Log4j2 and captures logs from multiple sources:

### Application Logs

| Log File | Contents |
|----------|----------|
| `logs/app/datastreamer.log` | Application-specific logs |
| `logs/app/ignite-client.log` | Ignite client connection and partition logs |
| `logs/app/all.log` | Combined logs from all sources |

### GridGain Node Logs

GridGain 9 nodes write to stdout, which Docker captures. Fetch node logs with:

```bash
# Fetch all logs
./scripts/fetch-node-logs.sh

# Fetch last 1000 lines per node
./scripts/fetch-node-logs.sh --tail 1000

# Fetch logs from the last hour
./scripts/fetch-node-logs.sh --since 1h
```

Node logs are saved to `logs/node{1-5}/docker-stdout.log`.

You can also view logs in real-time:

```bash
docker compose logs -f node1
```

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
    Connect Addresses: 127.0.0.1:10800, 127.0.0.1:10801, ...
    Record Count:      20,000,000
    Page Size:         5,000
    Parallel Ops:      4
    Monitor Interval:  10 seconds

=== [1/4] Connecting to Cluster ===
<<< Connected to 5 node(s)

=== [2/4] Preparing Schema ===
>>> Dropping existing table if present
>>> Creating table without indexes
<<< Table 'TestTable' created (indexes deferred)

=== [3/4] Streaming Data ===
>>> DataStreamer configured: pageSize=5000, parallelOps=4
>>> Starting stream of 20,000,000 records
--- Published: 7,159,773 | Rate: 633,026/s (peak: 633,026/s) | Backpressure: 57 | Heap: 3,570MB/12,288MB
--- Published: 15,617,754 | Rate: 845,800/s (peak: 845,800/s) | Backpressure: 236 | Heap: 5,831MB/12,288MB
<<< Streaming completed in 26.04 seconds

=== [4/4] Creating Indexes ===
>>> Creating secondary indexes
    Created index: idx_testtable_label
    Created index: idx_testtable_amount
<<< Indexes created in 52.61 seconds

>>> Verifying data load
<<< Verification: 20,000,000 records in table

=== DataStreamer Metrics Report ===
    Total Events Published: 20,000,000
    Total Events Requested: 20,012,169
    Backpressure Events:    371
    Batches Completed:      0
    Elapsed Time:           80.19 seconds
    Average Rate:           249,419 events/sec
    Peak Rate:              845,800 events/sec
    Avg Batch Time:         0.00 ms
    Final Heap Usage:       5,871MB / 12,288MB
===================================
```

## Cleanup

Stop and remove the cluster:

```bash
docker compose down -v
```

Remove data and log volumes:

```bash
rm -rf data/ logs/
```

## Troubleshooting

### Connection Refused

Ensure the cluster is initialized and nodes are running:

```bash
docker compose ps
docker compose logs node1
```

### Primary Replica Changed Errors

Increase RAFT retry timeout in the cluster configuration. The docker-compose already sets `retryTimeoutMillis=30000`.

### Out of Memory

Reduce `RECORD_COUNT` or increase heap size:

```bash
MAVEN_OPTS="-Xmx4g" mvn exec:exec
```

### Viewing All Logs

For debugging, check the combined log file:

```bash
tail -f logs/app/all.log
```

Or fetch and view node logs:

```bash
./scripts/fetch-node-logs.sh
cat logs/node1/docker-stdout.log

```

## Analysis

For detailed analysis of DataStreamer performance and configuration recommendations, see `datastreamer-analysis.md`.
