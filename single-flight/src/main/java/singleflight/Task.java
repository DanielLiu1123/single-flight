package singleflight;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * A task to be executed by a {@link SingleFlight} instance.
 *
 * @author Freeman
 */
final class Task {

    private final Supplier<?> task;
    private final SingleFlight.Options options;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile Result result;

    public Task(Supplier<?> supplier, SingleFlight.Options options) {
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
                    if (r.getException() == null || options.isCacheException()) {
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
