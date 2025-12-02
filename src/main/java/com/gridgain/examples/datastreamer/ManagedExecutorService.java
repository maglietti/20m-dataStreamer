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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;

/**
 * AutoCloseable wrapper for ExecutorService enabling try-with-resources pattern.
 *
 * Provides graceful shutdown with configurable timeout, ensuring proper resource
 * cleanup even when exceptions occur during streaming operations.
 *
 * Usage:
 * <pre>
 * try (var executor = ManagedExecutorService.newFixedThreadPool(4, "streamer")) {
 *     // Use executor for streaming operations
 * } // Automatic graceful shutdown
 * </pre>
 */
public class ManagedExecutorService implements ExecutorService, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ManagedExecutorService.class);

    private final ExecutorService delegate;
    private final String name;
    private final long shutdownTimeoutSeconds;

    private ManagedExecutorService(ExecutorService delegate, String name, long shutdownTimeoutSeconds) {
        this.delegate = delegate;
        this.name = name;
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
    }

    /**
     * Creates a managed fixed thread pool.
     */
    public static ManagedExecutorService newFixedThreadPool(int threads, String name) {
        return new ManagedExecutorService(
            Executors.newFixedThreadPool(threads, new NamedThreadFactory(name)),
            name,
            30
        );
    }

    /**
     * Creates a managed cached thread pool.
     */
    public static ManagedExecutorService newCachedThreadPool(String name) {
        return new ManagedExecutorService(
            Executors.newCachedThreadPool(new NamedThreadFactory(name)),
            name,
            30
        );
    }

    /**
     * Creates a managed single-threaded executor.
     */
    public static ManagedExecutorService newSingleThreadExecutor(String name) {
        return new ManagedExecutorService(
            Executors.newSingleThreadExecutor(new NamedThreadFactory(name)),
            name,
            30
        );
    }

    /**
     * Wraps an existing ExecutorService with managed lifecycle.
     */
    public static ManagedExecutorService wrap(ExecutorService executor, String name, long shutdownTimeoutSeconds) {
        return new ManagedExecutorService(executor, name, shutdownTimeoutSeconds);
    }

    @Override
    public void close() {
        LOG.debug("Shutting down executor '{}'", name);
        shutdown();
        try {
            if (!awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                LOG.warn("Executor '{}' did not terminate gracefully, forcing shutdown", name);
                shutdownNow();
                if (!awaitTermination(10, TimeUnit.SECONDS)) {
                    LOG.error("Executor '{}' did not terminate after force shutdown", name);
                }
            } else {
                LOG.debug("Executor '{}' shut down successfully", name);
            }
        } catch (InterruptedException e) {
            LOG.warn("Interrupted while waiting for executor '{}' to terminate", name);
            shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Gets the underlying ExecutorService.
     */
    public ExecutorService getDelegate() {
        return delegate;
    }

    // Delegate all ExecutorService methods

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(task, result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(task);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(tasks);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.invokeAll(tasks, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return delegate.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(tasks, timeout, unit);
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }

    /**
     * Thread factory that names threads for easier debugging.
     */
    private static class NamedThreadFactory implements ThreadFactory {
        private final String baseName;
        private int count = 0;

        NamedThreadFactory(String baseName) {
            this.baseName = baseName;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, baseName + "-" + (count++));
            t.setDaemon(false);
            return t;
        }
    }
}
