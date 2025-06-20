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
 * // Using a dedicated instance
 * SingleFlight<String, User> userSingleFlight = new SingleFlight<>();
 * User user = singleFlight.run("123", () -> {
 *     return fetchUserFromDatabase("123");
 * });
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

    private final ConcurrentMap<K, Task<V>> invocationMap = new ConcurrentHashMap<>();

    public SingleFlight() {}

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

        try {
            // 2) Execute the task - first thread runs the task, others wait
            //    The Task class ensures thread-safe execution and result sharing
            return task.run();
        } finally {
            // 3) Remove the task from the map to allow future re-computations
            //    This cleanup is essential to prevent memory leaks and enable
            //    sequential calls with the same key to execute again
            invocationMap.remove(key, task);
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
}
