package singleflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests for exception caching behavior controlled by {@link SingleFlight.Options#isCacheException()}.
 *
 * <p>When cacheException=true: Only the first thread executes the task, others wait and get the same exception.
 * <p>When cacheException=false: Each thread executes the task independently when exceptions occur.
 *
 * @author Freeman
 */
@Execution(ExecutionMode.CONCURRENT)
class OptionsCacheExceptionTest {

    @Test
    void defaultOptionsShouldNotCacheExceptions() {
        // Given: Default options
        SingleFlight.Options defaultOptions = SingleFlight.Options.DEFAULT;

        // Then: Exception caching should be disabled by default
        assertThat(defaultOptions.isCacheException()).isFalse();
    }

    @Test
    void whenCacheExceptionDisabled_allThreadsShouldExecuteTaskIndependently() throws InterruptedException {
        // Given: SingleFlight with exception caching disabled
        SingleFlight<String, String> singleFlight = createSingleFlightWithCacheException(false);
        RuntimeException testException = new RuntimeException("Test exception");

        // When: Multiple threads call the same key concurrently and all throw exceptions
        ConcurrentTestResult result = runConcurrentExceptionTest(singleFlight, "test-key", testException, 5);

        // Then: All threads should execute the task independently
        assertThat(result.executionCount).isEqualTo(5);
        assertThat(result.exceptions).hasSize(5);
        assertThat(result.exceptions)
                .allSatisfy(exception -> assertThat(exception).isSameAs(testException));
    }

    @Test
    void whenCacheExceptionEnabled_onlyFirstThreadShouldExecuteTask() throws InterruptedException {
        // Given: SingleFlight with exception caching enabled
        SingleFlight<String, String> singleFlight = createSingleFlightWithCacheException(true);
        RuntimeException testException = new RuntimeException("Test exception");

        // When: Multiple threads call the same key concurrently and all throw exceptions
        ConcurrentTestResult result = runConcurrentExceptionTest(singleFlight, "test-key", testException, 5);

        // Then: Only the first thread should execute, others should get the cached exception
        assertThat(result.executionCount).isEqualTo(1);
        assertThat(result.exceptions).hasSize(5);
        assertThat(result.exceptions)
                .allSatisfy(exception -> assertThat(exception).isSameAs(testException));
    }

    @Test
    void sequentialCallsShouldAlwaysExecuteSeparately_regardlessOfCacheExceptionSetting() {
        // Given: Two different exceptions
        RuntimeException firstException = new RuntimeException("First exception");
        RuntimeException secondException = new RuntimeException("Second exception");

        // Test with cacheException=false
        testSequentialExceptionCalls(false, firstException, secondException);

        // Test with cacheException=true
        testSequentialExceptionCalls(true, firstException, secondException);
    }

    private void testSequentialExceptionCalls(
            boolean cacheException, RuntimeException firstException, RuntimeException secondException) {
        // Given: SingleFlight with specified cache exception setting
        SingleFlight<String, String> singleFlight = createSingleFlightWithCacheException(cacheException);
        AtomicInteger executionCount = new AtomicInteger(0);
        String key = "sequential-test-key-" + cacheException;

        // When & Then: First call should execute and throw first exception
        assertThatThrownBy(() -> singleFlight.run(key, () -> {
                    executionCount.incrementAndGet();
                    throw firstException;
                }))
                .isSameAs(firstException);

        // When & Then: Second call should execute again (task cleanup happens after each call)
        assertThatThrownBy(() -> singleFlight.run(key, () -> {
                    executionCount.incrementAndGet();
                    throw secondException;
                }))
                .isSameAs(secondException);

        // Then: Both calls should have executed (sequential calls always execute separately)
        assertThat(executionCount.get()).isEqualTo(2);
    }

    @Test
    void exceptionCachingShouldNotAffectSubsequentSuccessfulCalls() throws InterruptedException {
        String key = "exception-then-success-key";
        RuntimeException testException = new RuntimeException("Initial exception");
        AtomicInteger executionCount = new AtomicInteger(0);
        int threadCount = 3;

        SingleFlight<String, String> singleFlight = new SingleFlight<>(
                SingleFlight.Options.builder().cacheException(true).build());

        // First batch: concurrent calls that will all get the exception
        CountDownLatch startLatch1 = new CountDownLatch(1);
        CountDownLatch completeLatch1 = new CountDownLatch(threadCount);
        List<Exception> caughtExceptions = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                        try {
                            startLatch1.await();
                            singleFlight.run(key, () -> {
                                executionCount.incrementAndGet();
                                sleep(50);
                                throw testException;
                            });
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            caughtExceptions.add(e);
                        } finally {
                            completeLatch1.countDown();
                        }
                    })
                    .start();
        }

        startLatch1.countDown();
        assertThat(completeLatch1.await(5, TimeUnit.SECONDS)).isTrue();

        // Verify exception caching worked
        assertThat(executionCount.get()).isEqualTo(1);
        assertThat(caughtExceptions).hasSize(threadCount);
        assertThat(caughtExceptions)
                .allSatisfy(exception -> assertThat(exception).isSameAs(testException));

        // Second batch: concurrent calls that will succeed
        CountDownLatch startLatch2 = new CountDownLatch(1);
        CountDownLatch completeLatch2 = new CountDownLatch(threadCount);
        List<String> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                        try {
                            startLatch2.await();
                            String result = singleFlight.run(key, () -> {
                                executionCount.incrementAndGet();
                                sleep(50);
                                return "success-result";
                            });
                            results.add(result);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            // Should not happen
                            throw new RuntimeException("Unexpected exception", e);
                        } finally {
                            completeLatch2.countDown();
                        }
                    })
                    .start();
        }

        startLatch2.countDown();
        assertThat(completeLatch2.await(5, TimeUnit.SECONDS)).isTrue();

        // Verify success after exception
        assertThat(executionCount.get()).isEqualTo(2); // One exception execution + one success execution
        assertThat(results).hasSize(threadCount);
        assertThat(results).allSatisfy(result -> assertThat(result).isEqualTo("success-result"));
    }

    @Test
    void successfulCallsShouldNotAffectSubsequentExceptionCaching() throws InterruptedException {
        String key = "success-then-exception-key";
        RuntimeException testException = new RuntimeException("Later exception");
        AtomicInteger executionCount = new AtomicInteger(0);
        int threadCount = 3;

        SingleFlight<String, String> singleFlight = new SingleFlight<>(
                SingleFlight.Options.builder().cacheException(true).build());

        // First batch: concurrent calls that will succeed
        CountDownLatch startLatch1 = new CountDownLatch(1);
        CountDownLatch completeLatch1 = new CountDownLatch(threadCount);
        List<String> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                        try {
                            startLatch1.await();
                            String result = singleFlight.run(key, () -> {
                                executionCount.incrementAndGet();
                                sleep(50);
                                return "initial-success";
                            });
                            results.add(result);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            completeLatch1.countDown();
                        }
                    })
                    .start();
        }

        startLatch1.countDown();
        assertThat(completeLatch1.await(5, TimeUnit.SECONDS)).isTrue();

        // Verify success caching worked
        assertThat(executionCount.get()).isEqualTo(1);
        assertThat(results).hasSize(threadCount);
        assertThat(results).allSatisfy(result -> assertThat(result).isEqualTo("initial-success"));

        // Second batch: concurrent calls that will get exception
        CountDownLatch startLatch2 = new CountDownLatch(1);
        CountDownLatch completeLatch2 = new CountDownLatch(threadCount);
        List<Exception> caughtExceptions = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                        try {
                            startLatch2.await();
                            singleFlight.run(key, () -> {
                                executionCount.incrementAndGet();
                                sleep(50);
                                throw testException;
                            });
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            caughtExceptions.add(e);
                        } finally {
                            completeLatch2.countDown();
                        }
                    })
                    .start();
        }

        startLatch2.countDown();
        assertThat(completeLatch2.await(5, TimeUnit.SECONDS)).isTrue();

        // Verify exception caching worked
        assertThat(executionCount.get()).isEqualTo(2); // One success execution + one exception execution
        assertThat(caughtExceptions).hasSize(threadCount);
        assertThat(caughtExceptions)
                .allSatisfy(exception -> assertThat(exception).isSameAs(testException));
    }

    @Test
    void onlyExceptionsAreCached_errorsAreNot() throws InterruptedException {
        // Given: SingleFlight with exception caching enabled
        SingleFlight<String, String> singleFlight = createSingleFlightWithCacheException(true);

        // When & Then: RuntimeException should be cached (only first thread executes)
        RuntimeException runtimeException = new RuntimeException("Runtime exception test");
        ConcurrentTestResult runtimeResult =
                runConcurrentExceptionTest(singleFlight, "runtime-key", runtimeException, 3);
        assertThat(runtimeResult.executionCount).isEqualTo(1);
        assertThat(runtimeResult.exceptions).hasSize(3);

        // When & Then: Error should NOT be cached (all threads execute because Error is not caught by Task.run())
        Error error = new Error("Error test");
        ConcurrentErrorTestResult errorResult = runConcurrentErrorTest(singleFlight, "error-key", error, 3);
        assertThat(errorResult.executionCount).isEqualTo(3);
        assertThat(errorResult.errors).hasSize(3);

        // When & Then: IllegalArgumentException (subclass of RuntimeException) should be cached
        IllegalArgumentException illegalArgException = new IllegalArgumentException("Illegal argument test");
        ConcurrentTestResult illegalArgResult =
                runConcurrentExceptionTest(singleFlight, "illegal-arg-key", illegalArgException, 3);
        assertThat(illegalArgResult.executionCount).isEqualTo(1);
        assertThat(illegalArgResult.exceptions).hasSize(3);
    }

    @Test
    void cachingBehaviorShouldBeIndependentForDifferentKeys() throws InterruptedException {
        // Given: SingleFlight with exception caching enabled
        SingleFlight<String, String> singleFlight = createSingleFlightWithCacheException(true);
        RuntimeException testException = new RuntimeException("Test exception");

        // When: Multiple threads call different keys concurrently
        // Some keys succeed, some throw exceptions
        ConcurrentTestResult successResult = runConcurrentSuccessTest(singleFlight, "success-key", "success-value", 3);
        ConcurrentTestResult exceptionResult =
                runConcurrentExceptionTest(singleFlight, "exception-key", testException, 3);

        // Then: Both success and exception caching should work independently
        assertThat(successResult.executionCount).isEqualTo(1);
        assertThat(successResult.exceptions).isEmpty();

        assertThat(exceptionResult.executionCount).isEqualTo(1);
        assertThat(exceptionResult.exceptions).hasSize(3);
        assertThat(exceptionResult.exceptions)
                .allSatisfy(exception -> assertThat(exception).isSameAs(testException));
    }

    private ConcurrentTestResult runConcurrentSuccessTest(
            SingleFlight<String, String> singleFlight, String key, String successValue, int threadCount)
            throws InterruptedException {
        AtomicInteger executionCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);
        List<Exception> caughtExceptions = Collections.synchronizedList(new ArrayList<>());
        List<String> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                        try {
                            startLatch.await();
                            String result = singleFlight.run(key, () -> {
                                executionCount.incrementAndGet();
                                sleep(50);
                                return successValue;
                            });
                            results.add(result);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            caughtExceptions.add(e);
                        } finally {
                            completeLatch.countDown();
                        }
                    })
                    .start();
        }

        startLatch.countDown();
        assertThat(completeLatch.await(5, TimeUnit.SECONDS)).isTrue();

        return new ConcurrentTestResult(executionCount.get(), caughtExceptions);
    }

    // Helper methods and classes for better test readability

    private SingleFlight<String, String> createSingleFlightWithCacheException(boolean cacheException) {
        return new SingleFlight<>(
                SingleFlight.Options.builder().cacheException(cacheException).build());
    }

    private ConcurrentTestResult runConcurrentExceptionTest(
            SingleFlight<String, String> singleFlight, String key, RuntimeException exception, int threadCount)
            throws InterruptedException {
        AtomicInteger executionCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);
        List<Exception> caughtExceptions = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                        try {
                            startLatch.await();
                            singleFlight.run(key, () -> {
                                executionCount.incrementAndGet();
                                sleep(50); // Simulate some work
                                throw exception;
                            });
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            caughtExceptions.add(e);
                        } finally {
                            completeLatch.countDown();
                        }
                    })
                    .start();
        }

        startLatch.countDown();
        assertThat(completeLatch.await(5, TimeUnit.SECONDS)).isTrue();

        return new ConcurrentTestResult(executionCount.get(), caughtExceptions);
    }

    private ConcurrentErrorTestResult runConcurrentErrorTest(
            SingleFlight<String, String> singleFlight, String key, Error error, int threadCount)
            throws InterruptedException {
        AtomicInteger executionCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);
        List<Throwable> caughtErrors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                        try {
                            startLatch.await();
                            singleFlight.run(key, () -> {
                                executionCount.incrementAndGet();
                                sleep(50);
                                throw error;
                            });
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Throwable e) {
                            caughtErrors.add(e);
                        } finally {
                            completeLatch.countDown();
                        }
                    })
                    .start();
        }

        startLatch.countDown();
        assertThat(completeLatch.await(5, TimeUnit.SECONDS)).isTrue();

        return new ConcurrentErrorTestResult(executionCount.get(), caughtErrors);
    }

    private static class ConcurrentTestResult {
        final int executionCount;
        final List<Exception> exceptions;

        ConcurrentTestResult(int executionCount, List<Exception> exceptions) {
            this.executionCount = executionCount;
            this.exceptions = exceptions;
        }
    }

    private static class ConcurrentErrorTestResult {
        final int executionCount;
        final List<Throwable> errors;

        ConcurrentErrorTestResult(int executionCount, List<Throwable> errors) {
            this.executionCount = executionCount;
            this.errors = errors;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
