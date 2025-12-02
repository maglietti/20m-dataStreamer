/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gridgain.examples.datastreamer;

import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.table.DataStreamerOptions;
import org.apache.ignite.table.RecordView;
import org.apache.ignite.table.Tuple;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * High-volume DataStreamer test application demonstrating proper backpressure handling.
 *
 * This implementation incorporates patterns from production-ready reference applications:
 * - Custom Flow.Publisher with demand-driven data generation
 * - Dedicated executor management with proper lifecycle
 * - Metrics tracking
 * - Deferred index creation for optimal bulk load performance
 * - Configurable streaming options via environment variables
 * - Phase-based progress monitoring
 *
 * Configuration via environment variables:
 * - IGNITE_CONNECT_ADDRESS: Cluster address (default: 127.0.0.1:10800)
 * - RECORD_COUNT: Number of records to stream (default: 20,000,000)
 * - PAGE_SIZE: DataStreamer page size (default: 5000)
 * - PARALLEL_OPS: Per-partition parallel operations (default: 4)
 * - MONITOR_INTERVAL_SECONDS: Progress report interval (default: 10)
 */
public class DataStreamerTest {

    // Configuration with environment variable overrides
    private static final String CONNECT_ADDRESS = getEnv("IGNITE_CONNECT_ADDRESS", "127.0.0.1:10800");
    private static final long RECORD_COUNT = getLongEnv("RECORD_COUNT", 20_000_000L);
    private static final int PAGE_SIZE = getIntEnv("PAGE_SIZE", 5000);
    private static final int PARALLEL_OPS = getIntEnv("PARALLEL_OPS", 4);
    private static final int MONITOR_INTERVAL = getIntEnv("MONITOR_INTERVAL_SECONDS", 10);

    private static final String TABLE_NAME = "TestTable";

    public static void main(String[] args) {
        printConfiguration();

        StreamingMetrics metrics = new StreamingMetrics();

        // Phase 1: Connect and prepare schema
        System.out.println("\n=== [1/4] Connecting to Cluster ===");

        try (IgniteClient client = IgniteClient.builder()
                .addresses(CONNECT_ADDRESS)
                .build()) {

            System.out.printf("<<< Connected to %s%n", CONNECT_ADDRESS);

            // Phase 2: Create table without indexes (deferred index creation)
            System.out.println("\n=== [2/4] Preparing Schema ===");
            prepareSchema(client);

            // Phase 3: Stream data with monitoring
            System.out.println("\n=== [3/4] Streaming Data ===");
            streamData(client, metrics);

            // Phase 4: Create indexes after data load
            System.out.println("\n=== [4/4] Creating Indexes ===");
            createIndexes(client);

            // Verify data
            verifyData(client);

            // Final report
            System.out.println(metrics.getDetailedReport());

        } catch (Exception e) {
            System.err.println("!!! Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Prints configuration at startup for verification.
     */
    private static void printConfiguration() {
        System.out.println("=== DataStreamer Test Configuration ===");
        System.out.printf("    Connect Address: %s%n", CONNECT_ADDRESS);
        System.out.printf("    Record Count:    %,d%n", RECORD_COUNT);
        System.out.printf("    Page Size:       %,d%n", PAGE_SIZE);
        System.out.printf("    Parallel Ops:    %d%n", PARALLEL_OPS);
        System.out.printf("    Monitor Interval: %d seconds%n", MONITOR_INTERVAL);
    }

    /**
     * Creates the test table without indexes for faster bulk loading.
     */
    private static void prepareSchema(IgniteClient client) {
        System.out.println(">>> Dropping existing table if present");

        try {
            client.sql().execute(null, "DROP TABLE IF EXISTS " + TABLE_NAME);
        } catch (Exception e) {
            System.out.println("    Note: Table drop returned: " + e.getMessage());
        }

        System.out.println(">>> Creating table without indexes");

        // Create table without secondary indexes for optimal bulk load performance
        String createTableSql = String.format(
            "CREATE TABLE %s (" +
            "    id BIGINT PRIMARY KEY, " +
            "    name VARCHAR(100), " +
            "    value DOUBLE" +
            ")",
            TABLE_NAME
        );

        client.sql().execute(null, createTableSql);
        System.out.printf("<<< Table '%s' created (indexes deferred)%n", TABLE_NAME);
    }

    /**
     * Streams data using custom publisher with proper backpressure.
     */
    private static void streamData(IgniteClient client, StreamingMetrics metrics) {
        RecordView<Tuple> view = client.tables().table(TABLE_NAME).recordView();

        // Configure DataStreamer options
        DataStreamerOptions options = DataStreamerOptions.builder()
            .pageSize(PAGE_SIZE)
            .perPartitionParallelOperations(PARALLEL_OPS)
            .autoFlushInterval(1000)
            .build();

        System.out.printf(">>> DataStreamer configured: pageSize=%d, parallelOps=%d%n",
            PAGE_SIZE, PARALLEL_OPS);

        // Use managed executors for proper lifecycle management
        try (ManagedExecutorService publisherExecutor =
                 ManagedExecutorService.newFixedThreadPool(2, "publisher")) {

            // Create custom publisher with demand-driven generation
            SyntheticDataPublisher publisher = new SyntheticDataPublisher(
                RECORD_COUNT,
                metrics,
                publisherExecutor
            );

            // Start progress monitoring
            ScheduledExecutorService monitorExecutor = Executors.newSingleThreadScheduledExecutor();
            ScheduledFuture<?> monitorTask = monitorExecutor.scheduleAtFixedRate(
                () -> System.out.println("--- " + metrics.getProgressReport()),
                MONITOR_INTERVAL,
                MONITOR_INTERVAL,
                TimeUnit.SECONDS
            );

            System.out.printf(">>> Starting stream of %,d records%n", RECORD_COUNT);
            long startTime = System.currentTimeMillis();

            try {
                // Stream with backpressure-aware publisher
                CompletableFuture<Void> streamFuture = view.streamData(publisher, options);
                streamFuture.join();

                long elapsed = System.currentTimeMillis() - startTime;
                System.out.printf("<<< Streaming completed in %.2f seconds%n", elapsed / 1000.0);

            } finally {
                // Clean up monitor
                monitorTask.cancel(false);
                monitorExecutor.shutdown();
            }

        } catch (Exception e) {
            System.err.println("!!! Streaming failed: " + e.getMessage());
            throw new RuntimeException("Data streaming failed", e);
        }
    }

    /**
     * Creates secondary indexes after bulk data load.
     */
    private static void createIndexes(IgniteClient client) {
        System.out.println(">>> Creating secondary indexes");

        long startTime = System.currentTimeMillis();

        // Create index on name column
        try {
            client.sql().execute(null,
                String.format("CREATE INDEX IF NOT EXISTS idx_%s_name ON %s (name)",
                    TABLE_NAME.toLowerCase(), TABLE_NAME));
            System.out.println("    Created index: idx_testtable_name");
        } catch (Exception e) {
            System.out.println("!!! Index creation warning: " + e.getMessage());
        }

        // Create index on value column
        try {
            client.sql().execute(null,
                String.format("CREATE INDEX IF NOT EXISTS idx_%s_value ON %s (value)",
                    TABLE_NAME.toLowerCase(), TABLE_NAME));
            System.out.println("    Created index: idx_testtable_value");
        } catch (Exception e) {
            System.out.println("!!! Index creation warning: " + e.getMessage());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.printf("<<< Indexes created in %.2f seconds%n", elapsed / 1000.0);
    }

    /**
     * Verifies data was loaded correctly.
     */
    private static void verifyData(IgniteClient client) {
        System.out.println("\n>>> Verifying data load");

        try {
            var result = client.sql().execute(null,
                "SELECT COUNT(*) as cnt FROM " + TABLE_NAME);

            if (result.hasNext()) {
                long count = result.next().longValue("cnt");
                System.out.printf("<<< Verification: %,d records in table%n", count);

                if (count != RECORD_COUNT) {
                    System.out.printf("!!! Warning: Expected %,d records but found %,d%n",
                        RECORD_COUNT, count);
                }
            }
        } catch (Exception e) {
            System.out.println("!!! Verification failed: " + e.getMessage());
        }
    }

    // Environment variable helpers

    private static String getEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null ? value : defaultValue;
    }

    private static int getIntEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                System.out.printf("!!! Invalid value for %s: %s, using default: %d%n",
                    name, value, defaultValue);
            }
        }
        return defaultValue;
    }

    private static long getLongEnv(String name, long defaultValue) {
        String value = System.getenv(name);
        if (value != null) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                System.out.printf("!!! Invalid value for %s: %s, using default: %d%n",
                    name, value, defaultValue);
            }
        }
        return defaultValue;
    }
}
