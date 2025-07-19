package singleflight;

import java.util.function.Supplier;

/**
 * A task to be executed by a {@link SingleFlight} instance.
 *
 * @author Freeman
 */
final class Task {

    private final Supplier<?> task;
    private final SingleFlight.Options options;

    private volatile Result result;

    public Task(Supplier<?> supplier, SingleFlight.Options options) {
        this.task = supplier;
        this.options = options;
    }

    public synchronized Result run() {
        Result r = result;
        if (r == null) {
            try {
                return result = new Result(task.get(), null);
            } catch (Throwable e) {
                return result = new Result(null, e);
            }
        }

        // If no exception, return the successful result
        if (r.getException() == null) {
            return r;
        }

        if (options.isCacheException()) {
            return r;
        } else {
            try {
                // Cache the successful result
                return result = new Result(task.get(), null);
            } catch (Throwable e) {
                // Do NOT cache the exception, because we already have one
                return new Result(null, e);
            }
        }
    }
}
