package singleflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class SingleFlightTest {

    @Test
    void testBasicFunctionality() {
        AtomicInteger counter = new AtomicInteger(0);
        String key = "test-key";

        String result1 = SingleFlight.runDefault(key, () -> {
            counter.incrementAndGet();
            return "result";
        });

        String result2 = SingleFlight.runDefault(key, () -> {
            counter.incrementAndGet();
            return "different-result";
        });

        assertThat(result1).isEqualTo("result");
        assertThat(result2).isEqualTo("different-result"); // Should execute again after first completion
        assertThat(counter.get()).isEqualTo(2); // Both executions should happen
    }

    @Test
    void testConcurrentExecution() throws InterruptedException {
        AtomicInteger executionCount = new AtomicInteger(0);
        AtomicInteger resultCount = new AtomicInteger(0);
        String key = "concurrent-key";
        int threadCount = 10;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);
        List<String> results = Collections.synchronizedList(new ArrayList<>());

        // Create multiple threads that will execute concurrently
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                        try {
                            startLatch.await(); // Wait for all threads to be ready
                            String result = SingleFlight.runDefault(key, () -> {
                                executionCount.incrementAndGet();

                                sleep(100); // Simulate some work

                                return "concurrent-result-" + resultCount.incrementAndGet();
                            });
                            results.add(result);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            completeLatch.countDown();
                        }
                    })
                    .start();
        }

        startLatch.countDown(); // Start all threads
        assertThat(completeLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Verify that supplier was executed only once
        assertThat(executionCount.get()).isEqualTo(1);
        assertThat(resultCount.get()).isEqualTo(1);

        // Verify all threads got the same result
        assertThat(results).hasSize(threadCount);
        String expectedResult = results.get(0);
        assertThat(results).allSatisfy(result -> assertThat(result).isEqualTo(expectedResult));
    }

    @Test
    void testExceptionHandling() {
        String key = "exception-key";
        RuntimeException expectedException = new RuntimeException("Test exception");

        assertThatThrownBy(() -> {
                    SingleFlight.runDefault(key, () -> {
                        throw expectedException;
                    });
                })
                .isSameAs(expectedException);
    }

    @Test
    void testConcurrentExceptionHandling() throws InterruptedException {
        String key = "concurrent-exception-key";
        RuntimeException expectedException = new RuntimeException("Concurrent test exception");
        int threadCount = 5;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger executionCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                        try {
                            startLatch.await();
                            SingleFlight.runDefault(key, () -> {
                                sleep(100); // Simulate some work

                                executionCount.incrementAndGet();
                                throw expectedException;
                            });
                        } catch (Exception e) {
                            exceptions.add(e);
                        } finally {
                            completeLatch.countDown();
                        }
                    })
                    .start();
        }

        startLatch.countDown();
        assertThat(completeLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // With exception caching, supplier should execute only once
        assertThat(executionCount.get()).isEqualTo(1);

        // Verify all threads received the same exception
        assertThat(exceptions).hasSize(threadCount);
        assertThat(exceptions).allSatisfy(exception -> assertThat(exception).isSameAs(expectedException));
    }

    @Test
    void testDifferentKeysExecuteIndependently() throws InterruptedException {
        AtomicInteger counter1 = new AtomicInteger(0);
        AtomicInteger counter2 = new AtomicInteger(0);

        CountDownLatch latch = new CountDownLatch(2);

        // Execute with different keys concurrently
        new Thread(() -> {
                    try {
                        SingleFlight.runDefault("key1", () -> {
                            counter1.incrementAndGet();

                            sleep(100);

                            return "result1";
                        });
                    } finally {
                        latch.countDown();
                    }
                })
                .start();

        new Thread(() -> {
                    try {
                        SingleFlight.runDefault("key2", () -> {
                            counter2.incrementAndGet();

                            sleep(100);

                            return "result2";
                        });
                    } finally {
                        latch.countDown();
                    }
                })
                .start();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Both suppliers should have been executed
        assertThat(counter1.get()).isEqualTo(1);
        assertThat(counter2.get()).isEqualTo(1);
    }

    @Test
    void testSequentialExecutionsWithSameKey() {
        AtomicInteger counter = new AtomicInteger(0);
        String key = "sequential-key";

        // First execution
        String result1 = SingleFlight.runDefault(key, () -> {
            counter.incrementAndGet();
            return "result-" + counter.get();
        });

        // Second execution (should happen after first completes)
        String result2 = SingleFlight.runDefault(key, () -> {
            counter.incrementAndGet();
            return "result-" + counter.get();
        });

        assertThat(result1).isEqualTo("result-1");
        assertThat(result2).isEqualTo("result-2");
        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    @Timeout(10)
    void testNoDeadlock() throws InterruptedException {
        String key = "deadlock-test-key";
        int threadCount = 20;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                        try {
                            String result = SingleFlight.runDefault(key, () -> {
                                sleep(50); // Simulate work
                                return "no-deadlock-result";
                            });

                            if ("no-deadlock-result".equals(result)) {
                                successCount.incrementAndGet();
                            }
                        } finally {
                            latch.countDown();
                        }
                    })
                    .start();
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(successCount.get()).isEqualTo(threadCount);
    }

    @Test
    void testDifferentValueTypes() {
        // Test with Integer
        Integer intResult = SingleFlight.runDefault("int-key", () -> 42);
        assertThat(intResult).isEqualTo(42);

        // Test with custom object
        TestObject objResult = SingleFlight.runDefault("obj-key", () -> new TestObject("test"));
        assertThat(objResult.getValue()).isEqualTo("test");

        // Test with null result
        String nullResult = SingleFlight.runDefault("null-key", () -> null);
        assertThat(nullResult).isNull();
    }

    @Test
    void testMemoryCleanup() {
        // This test verifies that internal map entries are cleaned up
        String key = "cleanup-key";
        AtomicInteger executionCount = new AtomicInteger(0);

        // First execution
        SingleFlight.runDefault(key, () -> {
            executionCount.incrementAndGet();
            return "first";
        });

        // Second execution should happen (proving cleanup occurred)
        SingleFlight.runDefault(key, () -> {
            executionCount.incrementAndGet();
            return "second";
        });

        assertThat(executionCount.get()).isEqualTo(2);
    }

    @Test
    void testHighConcurrencyStressTest() throws InterruptedException {
        String key = "stress-test-key";
        int threadCount = 100;
        AtomicInteger executionCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);
        List<String> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                        try {
                            startLatch.await();
                            String result = SingleFlight.runDefault(key, () -> {
                                int count = executionCount.incrementAndGet();
                                sleep(100);
                                return "stress-result-" + count;
                            });
                            results.add(result);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            completeLatch.countDown();
                        }
                    })
                    .start();
        }

        startLatch.countDown();
        assertThat(completeLatch.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(executionCount.get()).isEqualTo(1);
        assertThat(results).hasSize(threadCount);

        // All results should be identical
        String expectedResult = results.get(0);
        assertThat(results).allSatisfy(result -> assertThat(result).isEqualTo(expectedResult));
    }

    @Test
    void testInterruptedThread() throws InterruptedException {
        String key = "interrupt-key";
        AtomicReference<Exception> caughtException = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread testThread = new Thread(() -> {
            try {
                SingleFlight.runDefault(key, () -> {
                    try {
                        Thread.sleep(1000); // Long operation
                        return "should-not-complete";
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during execution", e);
                    }
                });
            } catch (Exception e) {
                caughtException.set(e);
            } finally {
                latch.countDown();
            }
        });

        testThread.start();
        Thread.sleep(100); // Let the thread start execution
        testThread.interrupt(); // Interrupt the thread

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        Exception exception = caughtException.get();
        assertThat(exception).isNotNull();
        assertThat(exception).isInstanceOf(RuntimeException.class);
        assertThat(exception.getMessage()).contains("Interrupted");
    }

    @Test
    void testMultipleKeysWithSameHashCode() {
        // Create keys that might have hash collisions
        String key1 = "Aa";
        String key2 = "BB"; // These strings have the same hashCode in Java

        AtomicInteger counter1 = new AtomicInteger(0);
        AtomicInteger counter2 = new AtomicInteger(0);

        String result1 = SingleFlight.runDefault(key1, () -> {
            counter1.incrementAndGet();
            return "result-for-Aa";
        });

        String result2 = SingleFlight.runDefault(key2, () -> {
            counter2.incrementAndGet();
            return "result-for-BB";
        });

        assertThat(result1).isEqualTo("result-for-Aa");
        assertThat(result2).isEqualTo("result-for-BB");
        assertThat(counter1.get()).isEqualTo(1);
        assertThat(counter2.get()).isEqualTo(1);
    }

    @Test
    void testLongRunningOperation() throws InterruptedException {
        String key = "long-running-key";
        AtomicInteger executionCount = new AtomicInteger(0);
        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);
        List<String> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                        try {
                            startLatch.await();
                            String result = SingleFlight.runDefault(key, () -> {
                                executionCount.incrementAndGet();
                                sleep(500); // Longer operation
                                return "long-running-result";
                            });
                            results.add(result);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            completeLatch.countDown();
                        }
                    })
                    .start();
        }

        startLatch.countDown();
        assertThat(completeLatch.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(executionCount.get()).isEqualTo(1);
        assertThat(results).hasSize(threadCount);
        assertThat(results).allSatisfy(result -> assertThat(result).isEqualTo("long-running-result"));
    }

    @Test
    void testExceptionCachingBehavior() throws InterruptedException {
        String key = "exception-caching-key";
        RuntimeException testException = new RuntimeException("Cached exception test");
        AtomicInteger executionCount = new AtomicInteger(0);
        int threadCount = 3;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);
        List<Exception> caughtExceptions = Collections.synchronizedList(new ArrayList<>());

        // Start multiple threads that will all try to execute the same failing supplier
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                        try {
                            startLatch.await();
                            SingleFlight.runDefault(key, () -> {
                                executionCount.incrementAndGet();
                                sleep(50); // Simulate some work
                                throw testException;
                            });
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

        // Verify exception caching behavior
        assertThat(executionCount.get()).isEqualTo(1); // Supplier executed only once
        assertThat(caughtExceptions).hasSize(threadCount); // All threads got the exception
        assertThat(caughtExceptions)
                .allSatisfy(exception -> assertThat(exception).isSameAs(testException)); // Same exception instance
    }

    @Test
    void testExceptionCachingWithSequentialCalls() {
        String key = "sequential-exception-key";
        RuntimeException testException1 = new RuntimeException("First exception");
        RuntimeException testException2 = new RuntimeException("Second exception");
        AtomicInteger executionCount = new AtomicInteger(0);

        // First call should execute and throw the first exception
        assertThatThrownBy(() -> SingleFlight.runDefault(key, () -> {
                    executionCount.incrementAndGet();
                    throw testException1;
                }))
                .isSameAs(testException1);

        // Second call should execute again (after cleanup) and throw the second exception
        assertThatThrownBy(() -> SingleFlight.runDefault(key, () -> {
                    executionCount.incrementAndGet();
                    throw testException2;
                }))
                .isSameAs(testException2);

        // Verify supplier was executed twice (sequential calls)
        assertThat(executionCount.get()).isEqualTo(2);
    }

    @Test
    void testMixedSuccessAndExceptionBehavior() throws InterruptedException {
        AtomicInteger successExecutionCount = new AtomicInteger(0);
        AtomicInteger exceptionExecutionCount = new AtomicInteger(0);

        // Test successful result behavior (sequential calls execute separately)
        String successKey = "success-key";
        String result1 = SingleFlight.runDefault(successKey, () -> {
            successExecutionCount.incrementAndGet();
            return "success-result";
        });
        String result2 = SingleFlight.runDefault(successKey, () -> {
            successExecutionCount.incrementAndGet();
            return "different-result";
        });

        assertThat(result1).isEqualTo("success-result");
        assertThat(result2).isEqualTo("different-result"); // New execution after cleanup
        assertThat(successExecutionCount.get()).isEqualTo(2);

        // Test exception behavior (sequential calls execute separately)
        String exceptionKey = "exception-key";
        RuntimeException testException1 = new RuntimeException("First exception");
        RuntimeException testException2 = new RuntimeException("Second exception");

        assertThatThrownBy(() -> SingleFlight.runDefault(exceptionKey, () -> {
                    exceptionExecutionCount.incrementAndGet();
                    throw testException1;
                }))
                .isSameAs(testException1);

        assertThatThrownBy(() -> SingleFlight.runDefault(exceptionKey, () -> {
                    exceptionExecutionCount.incrementAndGet();
                    throw testException2;
                }))
                .isSameAs(testException2);

        assertThat(exceptionExecutionCount.get()).isEqualTo(2);
    }

    @Data
    private static class TestObject {
        private final String value;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
