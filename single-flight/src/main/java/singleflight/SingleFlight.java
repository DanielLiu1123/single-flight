package singleflight;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * A high-performance, thread-safe "single-flight" utility that prevents duplicate execution
 * of expensive operations. When multiple threads request the same resource simultaneously,
 * only one thread executes the operation while others wait and share the result.
 *
 * <h2>Usage Examples</h2>
 *
 * <pre>{@code
 * // Using the static convenience method
 * String result = SingleFlight.runDefault("user:123", () -> {
 *     return fetchUserFromDatabase("123");
 * });
 *
 * // Using a dedicated instance with default options
 * SingleFlight<String, User> userSingleFlight = new SingleFlight<>();
 * User user = userSingleFlight.run("123", () -> {
 *     return fetchUserFromDatabase("123");
 * });
 *
 * // Using custom options to cache exceptions
 * SingleFlight<String, User> cachingExceptionsSF = new SingleFlight<>(
 *     SingleFlight.Options.builder()
 *         .recomputeOnException(false)
 *         .build()
 * );
 * }</pre>
 *
 * <h2>When to Use</h2>
 * <h3>Perfect For:</h3>
 * <ul>
 *   <li>Database queries with high cache miss rates</li>
 *   <li>External API calls that are expensive or rate-limited</li>
 *   <li>Complex computations that are CPU-intensive</li>
 *   <li>File I/O operations that are slow or resource-intensive</li>
 *   <li>Prevent cache stampedes</li>
 * </ul>
 *
 * <h3>Not Suitable For:</h3>
 * <ul>
 *   <li>Operations that should always execute (like logging)</li>
 *   <li>Operations with side effects that must happen for each call</li>
 *   <li>Very fast operations where coordination overhead exceeds benefits</li>
 * </ul>
 *
 * <h2>Implementation Details</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    Single Flight Execution Flow             │
 * ├─────────────────────────────────────────────────────────────┤
 * │                                                             │
 * │  Thread 1 ──┐                                               │
 * │  Thread 2 ──┼─► ConcurrentHashMap.computeIfAbsent() ──┐     │
 * │  Thread 3 ──┘                                         │     │
 * │                                                       ▼     │
 * │              ┌─────────────────────────────────────────────┐│
 * │              │ First thread creates Task with Supplier     ││
 * │              │ Other threads get existing Task             ││
 * │              └─────────────────────────────────────────────┘│
 * │                                          │                  │
 * │                                          ▼                  │
 * │              ┌─────────────────────────────────────────────┐│
 * │              │ FutureTask ensures single execution         ││
 * │              │ - First thread: runs supplier               ││
 * │              │ - Other threads: wait for result            ││
 * │              └─────────────────────────────────────────────┘│
 * │                                          │                  │
 * │                                          ▼                  │
 * │              ┌─────────────────────────────────────────────┐│
 * │              │ All threads receive same result/exception   ││
 * │              │ Task is removed from map (cleanup)          ││
 * │              └─────────────────────────────────────────────┘│
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * @param <K> the type of the key used to identify unique operations.
 *            Keys must implement {@link Object#equals(Object)} and {@link Object#hashCode()}
 *            correctly to ensure proper deduplication behavior.
 * @param <V> the type of the value returned by the supplier function.
 *            Can be any type including {@code null}.
 * @author Freeman Liu
 * @since 0.1.0
 */
public final class SingleFlight<K, V> {

    /**
     * Shared default instance used by the static {@link #runDefault(Object, Supplier)} method.
     */
    private static final SingleFlight<Object, Object> DEFAULT = new SingleFlight<>();

    private final Options options;
    private final ConcurrentMap<K, Task<V>> invocationMap = new ConcurrentHashMap<>();

    /**
     * Creates a new SingleFlight instance with default options.
     */
    public SingleFlight() {
        this(Options.DEFAULTS);
    }

    /**
     * Creates a new SingleFlight instance with the specified options.
     *
     * @param options the options to use for this instance
     */
    public SingleFlight(Options options) {
        this.options = options;
    }

    /**
     * Executes the given supplier for the specified key, ensuring that concurrent calls
     * with the same key will result in only one execution of the supplier. Other threads
     * will wait for the result and share it.
     *
     * <h3>Usage Examples</h3>
     * <pre>{@code
     * SingleFlight<String, User> userSingleFlight = new SingleFlight<>();
     * User user = userSingleFlight.run(userId, () -> {
     *     return database.findUserById(userId);
     * });
     * }</pre>
     *
     * @param key      the unique identifier for this operation. Must not be {@code null}.
     *                 Keys should implement {@link Object#equals(Object)} and {@link Object#hashCode()}
     *                 correctly. Consider using meaningful, descriptive keys to avoid collisions.
     * @param supplier the operation to execute. Must not be {@code null}.
     * @return the result of the supplier execution. Can be {@code null} if the supplier returns {@code null}.
     * @throws IllegalArgumentException if {@code key} or {@code supplier} is {@code null}
     * @throws RuntimeException         if the supplier throws a {@code RuntimeException}
     * @throws Error                    if the supplier throws an {@code Error}
     * @throws IllegalStateException    if the execution is interrupted or fails unexpectedly
     * @see #runDefault(Object, Supplier)
     */
    public V run(K key, Supplier<V> supplier) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (supplier == null) {
            throw new IllegalArgumentException("supplier must not be null");
        }

        // 1) Atomically create or fetch a Task for the key
        //    Only the first thread will create the task; others will get the existing one
        Task<V> task = invocationMap.computeIfAbsent(key, k -> new Task<>(supplier));

        boolean exceptionOccurred = false;
        try {
            // 2) Execute the task - first thread runs the task, others wait
            //    The Task class ensures thread-safe execution and result sharing
            return task.run();
        } catch (Exception | Error e) {
            exceptionOccurred = true;
            throw e;
        } finally {
            // 3) Cleanup logic based on execution outcome and configuration
            //    - For successful executions: always remove to prevent memory leaks
            //    - For exceptions with recomputeOnException=true: remove to allow retry
            //    - For exceptions with recomputeOnException=false: keep to cache exception
            if (!exceptionOccurred || options.isRecomputeOnException()) {
                invocationMap.remove(key, task);
            }
        }
    }

    /**
     * Convenience method that executes the supplier using a shared default SingleFlight instance.
     * This is equivalent to creating a static SingleFlight instance and calling {@link #run(Object, Supplier)}.
     *
     * <p>This method is perfect for simple use cases where you don't need multiple isolated
     * SingleFlight instances. All calls to this method share the same key space.
     *
     * <h3>Usage Examples</h3>
     *
     * <pre>{@code
     * User user = SingleFlight.runDefault("user:" + userId, () -> {
     *     return database.findUserById(userId);
     * });
     * }</pre>
     *
     * @param <K>      the type of the key
     * @param <V>      the type of the value returned by the supplier
     * @param key      the unique identifier for this operation. Must not be {@code null}.
     * @param supplier the operation to execute. Must not be {@code null}.
     * @return the result of the supplier execution
     * @throws IllegalArgumentException if {@code key} or {@code supplier} is {@code null}
     * @throws RuntimeException         if the supplier throws a {@code RuntimeException}
     * @throws Error                    if the supplier throws an {@code Error}
     * @throws IllegalStateException    if the execution is interrupted or fails unexpectedly
     * @see #run(Object, Supplier)
     */
    @SuppressWarnings("unchecked")
    public static <K, V> V runDefault(K key, Supplier<V> supplier) {
        return ((SingleFlight<K, V>) DEFAULT).run(key, supplier);
    }

    /**
     * Configuration options for SingleFlight behavior.
     *
     * <p>This class provides configuration options to customize how SingleFlight handles
     * different scenarios, particularly exception handling and caching behavior.
     *
     * @since 0.2.0
     */
    public static final class Options {

        /**
         * Default options with recomputeOnException set to true (backward compatible behavior).
         */
        public static final Options DEFAULTS = new Options(true);

        private final boolean recomputeOnException;

        private Options(boolean recomputeOnException) {
            this.recomputeOnException = recomputeOnException;
        }

        /**
         * Creates a new Options builder.
         *
         * @return a new OptionsBuilder instance
         */
        public static OptionsBuilder builder() {
            return new OptionsBuilder();
        }

        /**
         * Returns whether exceptions should trigger recomputation on subsequent calls.
         *
         * <p>When {@code true} (default), if a supplier throws an exception, the task
         * is removed from the cache and subsequent calls with the same key will execute
         * the supplier again.
         *
         * <p>When {@code false}, exceptions are cached and subsequent calls with the
         * same key will immediately throw the cached exception without re-executing
         * the supplier.
         *
         * @return true if exceptions should trigger recomputation, false if exceptions should be cached
         */
        public boolean isRecomputeOnException() {
            return recomputeOnException;
        }

        /**
         * Builder for creating Options instances.
         */
        public static final class OptionsBuilder {
            private boolean recomputeOnException = true; // Default to backward compatible behavior

            /**
             * Sets whether exceptions should trigger recomputation on subsequent calls.
             *
             * @param recomputeOnException true to recompute on exception, false to cache exceptions
             * @return this builder instance
             */
            public OptionsBuilder recomputeOnException(boolean recomputeOnException) {
                this.recomputeOnException = recomputeOnException;
                return this;
            }

            /**
             * Builds the Options instance.
             *
             * @return a new Options instance with the configured settings
             */
            public Options build() {
                return new Options(recomputeOnException);
            }
        }
    }
}
