package singleflight;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;

/**
 * Internal task wrapper that ensures thread-safe, single execution of a supplier function.
 * This class is the core implementation detail that enables the single-flight pattern by
 * coordinating multiple threads to share the result of a single execution.
 *
 * @param <T> the type of the result produced by the supplier function.
 *            Can be any type including {@code null}.
 * @author Freeman Liu
 */
final class Task<T> {

    private final FutureTask<T> task;

    public Task(Supplier<? extends T> supplier) {
        if (supplier == null) {
            throw new IllegalArgumentException("supplier must not be null");
        }
        this.task = new FutureTask<>(supplier::get);
    }

    public T run() {
        // Ensure only one run() invocation executes the supplier
        // Multiple calls to FutureTask.run() are safe - only the first one executes
        if (!task.isDone()) {
            task.run();
        }

        try {
            // Block until the result is available
            // This is efficient - threads are parked, not spinning
            return task.get();
        } catch (InterruptedException ie) {
            // Restore the interrupt flag and wrap in unchecked exception
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for task execution", ie);
        } catch (ExecutionException ee) {
            // Unwrap and re-throw the original exception
            Throwable cause = ee.getCause();

            // Preserve the original exception type when possible
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            } else if (cause instanceof Error) {
                throw (Error) cause;
            } else {
                // Wrap checked exceptions in unchecked exception
                throw new IllegalStateException("Task execution failed", cause);
            }
        }
    }
}
