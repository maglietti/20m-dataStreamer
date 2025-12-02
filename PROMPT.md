# DataStreamer Test Application - Session Context

Use this prompt to start a new Claude Code session for working on this application.

## Project Overview

This is a high-volume DataStreamer test application for GridGain 9.1.14. It demonstrates proper backpressure handling using a custom `Flow.Publisher` implementation that generates synthetic data on demand.

## Key Files

- `src/main/java/com/gridgain/examples/datastreamer/DataStreamerTest.java` - Main application
- `src/main/java/com/gridgain/examples/datastreamer/SyntheticDataPublisher.java` - Custom Flow.Publisher with demand-driven generation
- `src/main/java/com/gridgain/examples/datastreamer/StreamingMetrics.java` - Metrics tracking
- `src/main/java/com/gridgain/examples/datastreamer/ManagedExecutorService.java` - AutoCloseable executor wrapper
- `docker-compose.yml` - 3-node GridGain 9.1.14 cluster with RAFT timeout configuration
- `cluster-init.sh` - Initialize cluster with license (reads from external file)
- `license.example.conf` - Example license format (safe to commit)
- `datastreamer-analysis.md` - Performance analysis and configuration recommendations

## Technology Stack

- Java 17
- GridGain 9.1.14 (client)
- Maven
- Docker Compose

## Reference Applications

This project is based on patterns from:
- `/Users/michael.aglietti/Code/magliettiGit/ignite3-java-api-primer/ignite3-reference-apps/08-data-streaming-app`
- `/Users/michael.aglietti/Code/magliettiGit/ignite3-java-api-primer/ignite3-reference-apps/10-file-streaming-app`

## Background

The application was created to test and demonstrate proper DataStreamer usage after a customer encountered "primary replica has changed" errors when streaming 20 million records. The root cause was I/O contention causing lease update timeouts. The solution involved:

1. Increasing `ignite.raft.retryTimeoutMillis` to 30000ms
2. Implementing proper backpressure handling with custom Flow.Publisher
3. Deferring index creation until after bulk data load

## Common Tasks

### Build and Run

```bash
mvn clean package
mvn exec:java
```

### Start Local Cluster

```bash
docker compose up -d
# Copy your license file (not committed to git)
cp /path/to/your/license.conf my-gridgain-9.1.license.conf
# Initialize cluster with license
./cluster-init.sh
```

### Custom Configuration

```bash
RECORD_COUNT=1000000 PAGE_SIZE=10000 mvn exec:java
```

## Session Start Prompt

Copy and paste this to start a new session:

---

I'm working on the DataStreamer test application in `/Users/michael.aglietti/Downloads/20m-dataStreamer/`. This is a Java 17 Maven project that tests GridGain 9.1.14 DataStreamer throughput using a custom Flow.Publisher with demand-driven data generation.

Please read the README.md and key source files to understand the project structure. The main classes are:
- `DataStreamerTest.java` - Main application with phase-based execution
- `SyntheticDataPublisher.java` - Custom Flow.Publisher for synthetic data
- `StreamingMetrics.java` - Metrics collection
- `ManagedExecutorService.java` - Executor lifecycle management

The `datastreamer-analysis.md` file contains performance analysis from a 20 million record test run.

---

## Notes

- The docker-compose.yml includes RAFT retry timeout configuration (`retryTimeoutMillis=30000`) required for high-throughput DataStreamer workloads
- The application uses deferred index creation for optimal bulk load performance
- Metrics track backpressure events to verify proper Flow.Publisher behavior
