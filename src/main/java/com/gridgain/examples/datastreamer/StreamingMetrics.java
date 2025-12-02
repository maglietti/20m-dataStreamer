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

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics tracking for DataStreamer operations.
 *
 * Tracks publisher/subscriber metrics, backpressure events, throughput rates,
 * and system resource utilization. Provides detailed reporting for performance
 * analysis and tuning.
 *
 * Thread-safe implementation using atomic operations for concurrent updates
 * from multiple streaming threads.
 */
public class StreamingMetrics {

    // Event counters
    private final AtomicLong eventsGenerated = new AtomicLong(0);
    private final AtomicLong eventsPublished = new AtomicLong(0);
    private final AtomicLong eventsRequested = new AtomicLong(0);
    private final AtomicLong backpressureEvents = new AtomicLong(0);

    // Timing
    private final long startTimeNanos;
    private volatile long lastReportTimeNanos;
    private volatile long lastEventsPublished = 0;

    // Rate tracking
    private volatile double currentPublishRate = 0.0;
    private volatile double peakPublishRate = 0.0;

    // Batch tracking
    private final AtomicLong batchesCompleted = new AtomicLong(0);
    private final AtomicLong totalBatchTimeNanos = new AtomicLong(0);

    // System monitoring
    private final MemoryMXBean memoryBean;
    private final OperatingSystemMXBean osBean;

    public StreamingMetrics() {
        this.startTimeNanos = System.nanoTime();
        this.lastReportTimeNanos = startTimeNanos;
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
    }

    /**
     * Records a generated event (before publishing).
     */
    public void recordEventGenerated() {
        eventsGenerated.incrementAndGet();
    }

    /**
     * Records a successfully published event.
     */
    public void recordEventPublished() {
        eventsPublished.incrementAndGet();
    }

    /**
     * Records demand from the subscriber.
     */
    public void recordEventsRequested(long n) {
        eventsRequested.addAndGet(n);
    }

    /**
     * Records a backpressure event (when demand reaches zero).
     */
    public void recordBackpressureEvent() {
        backpressureEvents.incrementAndGet();
    }

    /**
     * Records batch completion with timing.
     */
    public void recordBatchComplete(long batchTimeNanos) {
        batchesCompleted.incrementAndGet();
        totalBatchTimeNanos.addAndGet(batchTimeNanos);
    }

    /**
     * Updates rate calculations. Call periodically for accurate rates.
     */
    public void updateRates() {
        long currentTime = System.nanoTime();
        long currentPublished = eventsPublished.get();

        double elapsedSeconds = (currentTime - lastReportTimeNanos) / 1_000_000_000.0;
        if (elapsedSeconds > 0) {
            long eventsDelta = currentPublished - lastEventsPublished;
            currentPublishRate = eventsDelta / elapsedSeconds;

            if (currentPublishRate > peakPublishRate) {
                peakPublishRate = currentPublishRate;
            }
        }

        lastReportTimeNanos = currentTime;
        lastEventsPublished = currentPublished;
    }

    // Getters
    public long getEventsGenerated() { return eventsGenerated.get(); }
    public long getEventsPublished() { return eventsPublished.get(); }
    public long getEventsRequested() { return eventsRequested.get(); }
    public long getBackpressureEvents() { return backpressureEvents.get(); }
    public long getBatchesCompleted() { return batchesCompleted.get(); }
    public double getCurrentPublishRate() { return currentPublishRate; }
    public double getPeakPublishRate() { return peakPublishRate; }

    /**
     * Gets elapsed time in seconds since metrics collection started.
     */
    public double getElapsedSeconds() {
        return (System.nanoTime() - startTimeNanos) / 1_000_000_000.0;
    }

    /**
     * Gets average events per second over entire run.
     */
    public double getAverageRate() {
        double elapsed = getElapsedSeconds();
        return elapsed > 0 ? eventsPublished.get() / elapsed : 0;
    }

    /**
     * Gets average batch processing time in milliseconds.
     */
    public double getAverageBatchTimeMs() {
        long batches = batchesCompleted.get();
        return batches > 0 ? (totalBatchTimeNanos.get() / batches) / 1_000_000.0 : 0;
    }

    /**
     * Gets current heap memory usage in MB.
     */
    public long getHeapUsedMB() {
        return memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024);
    }

    /**
     * Gets maximum heap memory in MB.
     */
    public long getHeapMaxMB() {
        return memoryBean.getHeapMemoryUsage().getMax() / (1024 * 1024);
    }

    /**
     * Gets system CPU load (0.0 to 1.0).
     */
    public double getSystemCpuLoad() {
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) osBean).getCpuLoad();
        }
        return -1;
    }

    /**
     * Gets the outstanding demand (requested minus published).
     */
    public long getOutstandingDemand() {
        return eventsRequested.get() - eventsPublished.get();
    }

    /**
     * Generates a progress report for logging.
     */
    public String getProgressReport() {
        updateRates();
        return String.format(
            "Published: %,d | Rate: %,.0f/s (peak: %,.0f/s) | Backpressure: %,d | Heap: %,dMB/%,dMB",
            eventsPublished.get(),
            currentPublishRate,
            peakPublishRate,
            backpressureEvents.get(),
            getHeapUsedMB(),
            getHeapMaxMB()
        );
    }

    /**
     * Generates a detailed final report.
     */
    public String getDetailedReport() {
        updateRates();
        double elapsed = getElapsedSeconds();

        StringBuilder sb = new StringBuilder();
        sb.append("\n=== DataStreamer Metrics Report ===\n");
        sb.append(String.format("    Total Events Published: %,d%n", eventsPublished.get()));
        sb.append(String.format("    Total Events Requested: %,d%n", eventsRequested.get()));
        sb.append(String.format("    Backpressure Events:    %,d%n", backpressureEvents.get()));
        sb.append(String.format("    Batches Completed:      %,d%n", batchesCompleted.get()));
        sb.append(String.format("    Elapsed Time:           %.2f seconds%n", elapsed));
        sb.append(String.format("    Average Rate:           %,.0f events/sec%n", getAverageRate()));
        sb.append(String.format("    Peak Rate:              %,.0f events/sec%n", peakPublishRate));
        sb.append(String.format("    Avg Batch Time:         %.2f ms%n", getAverageBatchTimeMs()));
        sb.append(String.format("    Final Heap Usage:       %,dMB / %,dMB%n", getHeapUsedMB(), getHeapMaxMB()));
        sb.append("===================================\n");

        return sb.toString();
    }
}
