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

import org.apache.ignite.table.DataStreamerItem;
import org.apache.ignite.table.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Custom Flow.Publisher that generates synthetic data with backpressure support.
 *
 * This publisher demonstrates demand-driven data generation where synthetic records
 * are created when the DataStreamer subscriber requests them. This prevents
 * memory bloat and ensures the producer rate matches downstream consumption capacity.
 *
 * Key features:
 * - Demand-driven generation: generates when subscriber requests data
 * - Configurable total record count
 * - Thread-safe atomic operations for demand tracking
 * - Metrics integration
 * - Proper completion signaling when target count is reached
 *
 * The implementation follows the Reactive Streams specification for Flow.Publisher.
 */
public class SyntheticDataPublisher implements Flow.Publisher<DataStreamerItem<Tuple>> {

    private static final Logger LOG = LoggerFactory.getLogger(SyntheticDataPublisher.class);

    private final long totalRecords;
    private final StreamingMetrics metrics;
    private final Executor deliveryExecutor;
    private final AtomicBoolean subscribed = new AtomicBoolean(false);

    /**
     * Creates a publisher that generates the specified number of synthetic records.
     *
     * @param totalRecords total number of records to generate
     * @param metrics metrics tracker for monitoring
     * @param deliveryExecutor executor for async delivery operations
     */
    public SyntheticDataPublisher(long totalRecords, StreamingMetrics metrics, Executor deliveryExecutor) {
        this.totalRecords = totalRecords;
        this.metrics = metrics;
        this.deliveryExecutor = deliveryExecutor;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super DataStreamerItem<Tuple>> subscriber) {
        if (subscriber == null) {
            throw new NullPointerException("Subscriber cannot be null");
        }

        if (subscribed.compareAndSet(false, true)) {
            LOG.debug("Subscriber attached, starting data generation for {} records", totalRecords);
            SyntheticDataSubscription subscription = new SyntheticDataSubscription(subscriber);
            subscriber.onSubscribe(subscription);
        } else {
            LOG.error("Publisher already has a subscriber, rejecting new subscription");
            subscriber.onError(new IllegalStateException("Publisher already has a subscriber"));
        }
    }

    /**
     * Gets total records to be generated.
     */
    public long getTotalRecords() {
        return totalRecords;
    }

    /**
     * Subscription implementation providing demand-driven synthetic data generation.
     */
    private class SyntheticDataSubscription implements Flow.Subscription {
        private final Flow.Subscriber<? super DataStreamerItem<Tuple>> subscriber;
        private final AtomicLong demand = new AtomicLong(0);
        private final AtomicLong generated = new AtomicLong(0);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean delivering = new AtomicBoolean(false);

        SyntheticDataSubscription(Flow.Subscriber<? super DataStreamerItem<Tuple>> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                subscriber.onError(new IllegalArgumentException("Request count must be positive"));
                return;
            }

            if (cancelled.get()) {
                return;
            }

            // Track demand for metrics
            metrics.recordEventsRequested(n);

            // Add to demand and trigger delivery
            demand.addAndGet(n);
            deliverItems();
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        /**
         * Triggers async delivery if not already in progress.
         */
        private void deliverItems() {
            if (delivering.compareAndSet(false, true)) {
                CompletableFuture.runAsync(this::deliverItemsSynchronously, deliveryExecutor)
                    .whenComplete((result, error) -> {
                        delivering.set(false);
                        if (error != null) {
                            subscriber.onError(error);
                        }
                    });
            }
        }

        /**
         * Generates and delivers items synchronously to satisfy current demand.
         */
        private void deliverItemsSynchronously() {
            while (demand.get() > 0 && !cancelled.get()) {
                long currentGenerated = generated.get();

                // Check if we've reached the target
                if (currentGenerated >= totalRecords) {
                    LOG.debug("Completed generation of {} records", totalRecords);
                    subscriber.onComplete();
                    return;
                }

                // Generate and deliver one item
                try {
                    DataStreamerItem<Tuple> item = generateItem(currentGenerated);
                    subscriber.onNext(item);

                    metrics.recordEventGenerated();
                    metrics.recordEventPublished();

                    generated.incrementAndGet();
                    demand.decrementAndGet();

                } catch (Exception e) {
                    LOG.error("Failed to generate item at index {}", currentGenerated, e);
                    subscriber.onError(new RuntimeException("Failed to generate item at index " + currentGenerated, e));
                    return;
                }
            }

            // Record backpressure if we stopped due to lack of demand
            if (demand.get() == 0 && !cancelled.get() && generated.get() < totalRecords) {
                metrics.recordBackpressureEvent();
            }
        }

        /**
         * Generates a synthetic data item matching the test schema.
         *
         * Schema: id (BIGINT PK), name (VARCHAR), value (DOUBLE)
         */
        private DataStreamerItem<Tuple> generateItem(long index) {
            Tuple tuple = Tuple.create()
                .set("id", index)
                .set("label", "Label_" + index + "_" + UUID.randomUUID().toString().substring(0, 8))
                .set("amount", Math.random() * 1000);

            return DataStreamerItem.of(tuple);
        }
    }
}
