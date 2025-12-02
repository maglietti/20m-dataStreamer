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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * - IGNITE_CONNECT_ADDRESSES: Comma-separated cluster addresses (default: all 5 local nodes)
 * - RECORD_COUNT: Number of records to stream (default: 20,000,000)
 * - PAGE_SIZE: DataStreamer page size (default: 5000)
 * - PARALLEL_OPS: Per-partition parallel operations (default: 4)
 * - MONITOR_INTERVAL_SECONDS: Progress report interval (default: 10)
 */
public class DataStreamerTest {

    private static final Logger LOG = LoggerFactory.getLogger(DataStreamerTest.class);

    // Configuration with environment variable overrides
    // Multiple addresses enable load balancing and failover
    private static final String[] CONNECT_ADDRESSES = getEnv(
        "IGNITE_CONNECT_ADDRESSES",
        "127.0.0.1:10800,127.0.0.1:10801,127.0.0.1:10802,127.0.0.1:10803,127.0.0.1:10804"
    ).split(",");
    private static final long RECORD_COUNT = getLongEnv("RECORD_COUNT", 20_000_000L);
    private static final int PAGE_SIZE = getIntEnv("PAGE_SIZE", 5000);
    private static final int PARALLEL_OPS = getIntEnv("PARALLEL_OPS", 4);
    private static final int MONITOR_INTERVAL = getIntEnv("MONITOR_INTERVAL_SECONDS", 10);

    private static final String TABLE_NAME = "TestTable";

    public static void main(String[] args) {
        printConfiguration();

        StreamingMetrics metrics = new StreamingMetrics();

        // Phase 1: Connect and prepare schema
        LOG.info("=== [1/4] Connecting to Cluster ===");

        try (IgniteClient client = IgniteClient.builder()
                .addresses(CONNECT_ADDRESSES)
                .build()) {

            LOG.info("<<< Connected to {} node(s)", CONNECT_ADDRESSES.length);

            // Phase 2: Create table without indexes (deferred index creation)
            LOG.info("=== [2/4] Preparing Schema ===");
            prepareSchema(client);

            // Phase 3: Stream data with monitoring
            LOG.info("=== [3/4] Streaming Data ===");
            streamData(client, metrics);

            // Phase 4: Create indexes after data load
            LOG.info("=== [4/4] Creating Indexes ===");
            createIndexes(client);

            // Verify data
            verifyData(client);

            // Final report
            LOG.info(metrics.getDetailedReport());

        } catch (Exception e) {
            LOG.error("!!! Fatal error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    /**
     * Prints configuration at startup for verification.
     */
    private static void printConfiguration() {
        LOG.info("=== DataStreamer Test Configuration ===");
        LOG.info("    Connect Addresses: {}", String.join(", ", CONNECT_ADDRESSES));
        LOG.info("    Record Count:      {}", String.format("%,d", RECORD_COUNT));
        LOG.info("    Page Size:         {}", String.format("%,d", PAGE_SIZE));
        LOG.info("    Parallel Ops:      {}", PARALLEL_OPS);
        LOG.info("    Monitor Interval:  {} seconds", MONITOR_INTERVAL);
    }

    /**
     * Creates the test table without indexes for faster bulk loading.
     */
    private static void prepareSchema(IgniteClient client) {
        LOG.info(">>> Dropping existing table if present");

        try {
            client.sql().execute(null, "DROP TABLE IF EXISTS " + TABLE_NAME);
        } catch (Exception e) {
            LOG.debug("    Note: Table drop returned: {}", e.getMessage());
        }

        LOG.info(">>> Creating table without indexes");

        // Create table without secondary indexes for optimal bulk load performance
        String createTableSql = String.format(
            "CREATE TABLE %s (" +
            "    id BIGINT PRIMARY KEY, " +
            "    label VARCHAR(100), " +
            "    amount DOUBLE" +
            ")",
            TABLE_NAME
        );

        client.sql().execute(null, createTableSql);
        LOG.info("<<< Table '{}' created (indexes deferred)", TABLE_NAME);
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

        LOG.info(">>> DataStreamer configured: pageSize={}, parallelOps={}", PAGE_SIZE, PARALLEL_OPS);

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
                () -> LOG.info("--- {}", metrics.getProgressReport()),
                MONITOR_INTERVAL,
                MONITOR_INTERVAL,
                TimeUnit.SECONDS
            );

            LOG.info(">>> Starting stream of {} records", String.format("%,d", RECORD_COUNT));
            long startTime = System.currentTimeMillis();

            try {
                // Stream with backpressure-aware publisher
                CompletableFuture<Void> streamFuture = view.streamData(publisher, options);
                streamFuture.join();

                long elapsed = System.currentTimeMillis() - startTime;
                LOG.info("<<< Streaming completed in {} seconds", String.format("%.2f", elapsed / 1000.0));

            } finally {
                // Clean up monitor
                monitorTask.cancel(false);
                monitorExecutor.shutdown();
            }

        } catch (Exception e) {
            LOG.error("!!! Streaming failed: {}", e.getMessage(), e);
            throw new RuntimeException("Data streaming failed", e);
        }
    }

    /**
     * Creates secondary indexes after bulk data load.
     */
    private static void createIndexes(IgniteClient client) {
        LOG.info(">>> Creating secondary indexes");

        long startTime = System.currentTimeMillis();

        // Create index on label column
        try {
            client.sql().execute(null,
                String.format("CREATE INDEX IF NOT EXISTS idx_%s_label ON %s (label)",
                    TABLE_NAME.toLowerCase(), TABLE_NAME));
            LOG.info("    Created index: idx_testtable_label");
        } catch (Exception e) {
            LOG.warn("!!! Index creation warning: {}", e.getMessage());
        }

        // Create index on amount column
        try {
            client.sql().execute(null,
                String.format("CREATE INDEX IF NOT EXISTS idx_%s_amount ON %s (amount)",
                    TABLE_NAME.toLowerCase(), TABLE_NAME));
            LOG.info("    Created index: idx_testtable_amount");
        } catch (Exception e) {
            LOG.warn("!!! Index creation warning: {}", e.getMessage());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        LOG.info("<<< Indexes created in {} seconds", String.format("%.2f", elapsed / 1000.0));
    }

    /**
     * Verifies data was loaded correctly.
     */
    private static void verifyData(IgniteClient client) {
        LOG.info(">>> Verifying data load");

        try {
            var result = client.sql().execute(null,
                "SELECT COUNT(*) as cnt FROM " + TABLE_NAME);

            if (result.hasNext()) {
                long count = result.next().longValue("cnt");
                LOG.info("<<< Verification: {} records in table", String.format("%,d", count));

                if (count != RECORD_COUNT) {
                    LOG.warn("!!! Warning: Expected {} records but found {}",
                        String.format("%,d", RECORD_COUNT), String.format("%,d", count));
                }
            }
        } catch (Exception e) {
            LOG.error("!!! Verification failed: {}", e.getMessage(), e);
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
                LOG.warn("!!! Invalid value for {}: {}, using default: {}", name, value, defaultValue);
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
                LOG.warn("!!! Invalid value for {}: {}, using default: {}", name, value, defaultValue);
            }
        }
        return defaultValue;
    }
}
