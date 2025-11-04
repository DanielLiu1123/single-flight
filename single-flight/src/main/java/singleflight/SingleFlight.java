package singleflight;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
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
 *
 * // Using a dedicated instance with custom options
 * SingleFlight<String, User> userSingleFlight = new SingleFlight<>(
 *     SingleFlight.Options.builder()
 *         .cacheException(true)
 *         .build()
 * );
 *
 * User user = userSingleFlight.run("123", () -> {
 *     return fetchUserFromDatabase("123");
 * });
 *
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
    private final ConcurrentMap<K, Task> invocationMap = new ConcurrentHashMap<>();

    /**
     * Creates a new SingleFlight instance with default options.
     */
    public SingleFlight() {
        this(Options.DEFAULT);
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
     * @throws IllegalStateException    if the execution throws an unexpected exception type
     * @see #run(Object, Supplier)
     */
    @SuppressWarnings("unchecked")
    public static <K, V> V runDefault(K key, Supplier<V> supplier) {
        return ((SingleFlight<K, V>) DEFAULT).run(key, supplier);
    }

    private static void handleException(Throwable e) {
        if (e instanceof Exception) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            } else {
                throw new IllegalStateException("Execution failed", e);
            }
        } else if (e instanceof Error) {
            throw (Error) e;
        } else {
            throw new IllegalStateException("Unexpected exception type", e);
        }
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
     * @throws IllegalStateException    if the execution throws an unexpected exception type
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
        Task task = invocationMap.computeIfAbsent(key, k -> new Task(supplier, options));

        try {
            // 2) Execute the task - first thread runs the task, others wait
            //    The Task class ensures thread-safe execution and result sharing
            Result result = task.run();

            Throwable e = result.exception();
            if (e == null) {
                return getValue(result);
            }

            handleException(e);

            // Never happen
            return null;
        } finally {
            // 3) Clean up the task from the map
            //    Ensures that the next call with the same key will execute the task again
            invocationMap.remove(key, task);
        }
    }

    private V getValue(Result result) {
        try {
            @SuppressWarnings("unchecked")
            V v = (V) result.value();
            return v;
        } catch (ClassCastException e) {
            throw new IllegalStateException("Result type mismatch", e);
        }
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
         * Default options.
         */
        public static final Options DEFAULT =
                Options.builder().cacheException(false).build();

        /**
         * Whether to cache exceptions.
         *
         * <p> If {@code true}, exceptions thrown by the task
         * will be cached and subsequent calls with the same key will immediately throw the
         * cached exception without re-executing the task.
         *
         * <p> If {@code false}, subsequent calls will re-execute the task.
         *
         * <p>Default is {@code false}.
         *
         * @since 0.2.0
         */
        private final boolean cacheException;

        Options(OptionsBuilder builder) {
            this.cacheException = builder.cacheException;
        }

        public static OptionsBuilder builder() {
            return new OptionsBuilder();
        }

        public boolean isCacheException() {
            return this.cacheException;
        }

        public OptionsBuilder toBuilder() {
            return new OptionsBuilder().cacheException(this.cacheException);
        }

        public static class OptionsBuilder {
            private boolean cacheException;

            OptionsBuilder() {}

            public OptionsBuilder cacheException(boolean cacheException) {
                this.cacheException = cacheException;
                return this;
            }

            public Options build() {
                return new Options(this);
            }

            public String toString() {
                return "SingleFlight.Options.OptionsBuilder(cacheException=" + this.cacheException + ")";
            }
        }
    }

    private record Result(Object value, Throwable exception) {}

    private static final class Task {

        private final Supplier<?> task;
        private final Options options;
        private final ReentrantLock lock = new ReentrantLock();

        private volatile Result result;

        public Task(Supplier<?> supplier, Options options) {
            this.task = supplier;
            this.options = options;
        }

        public Result run() {
            Result r = result;
            if (r == null) {
                lock.lock();
                try {
                    r = result;
                    if (r == null) {
                        try {
                            r = new Result(task.get(), null);
                        } catch (Throwable e) {
                            r = new Result(null, e);
                        }
                        if (r.exception() == null || options.isCacheException()) {
                            result = r;
                        }
                    }
                } finally {
                    lock.unlock();
                }
            }
            return r;
        }
    }
}
