/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.util.concurrent;

import org.apache.flink.core.testutils.FlinkMatchers;
import org.apache.flink.core.testutils.OneShotLatch;
import org.apache.flink.testutils.TestingUtils;
import org.apache.flink.testutils.executor.TestExecutorResource;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.FlinkRuntimeException;
import org.apache.flink.util.TestLogger;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Tests for the utility methods in {@link FutureUtils}. */
public class FutureUtilsTest extends TestLogger {

    @ClassRule
    public static final TestExecutorResource<ScheduledExecutorService> EXECUTOR_RESOURCE =
            TestingUtils.defaultExecutorResource();

    /** Tests that we can retry an operation. */
    @Test
    public void testRetrySuccess() throws Exception {
        final int retries = 10;
        final AtomicInteger atomicInteger = new AtomicInteger(0);
        CompletableFuture<Boolean> retryFuture =
                FutureUtils.retry(
                        () ->
                                CompletableFuture.supplyAsync(
                                        () -> {
                                            if (atomicInteger.incrementAndGet() == retries) {
                                                return true;
                                            } else {
                                                throw new CompletionException(
                                                        new FlinkException("Test exception"));
                                            }
                                        },
                                        EXECUTOR_RESOURCE.getExecutor()),
                        retries,
                        EXECUTOR_RESOURCE.getExecutor());

        assertTrue(retryFuture.get());
        assertEquals(retries, atomicInteger.get());
    }

    /** Tests that a retry future is failed after all retries have been consumed. */
    @Test(expected = FutureUtils.RetryException.class)
    public void testRetryFailureFixedRetries() throws Throwable {
        final int retries = 3;

        CompletableFuture<?> retryFuture =
                FutureUtils.retry(
                        () ->
                                FutureUtils.completedExceptionally(
                                        new FlinkException("Test exception")),
                        retries,
                        EXECUTOR_RESOURCE.getExecutor());

        try {
            retryFuture.get();
        } catch (ExecutionException ee) {
            throw ExceptionUtils.stripExecutionException(ee);
        }
    }

    /** Tests that we can cancel a retry future. */
    @Test
    public void testRetryCancellation() throws Exception {
        final int retries = 10;
        final AtomicInteger atomicInteger = new AtomicInteger(0);
        final OneShotLatch notificationLatch = new OneShotLatch();
        final OneShotLatch waitLatch = new OneShotLatch();
        final AtomicReference<Throwable> atomicThrowable = new AtomicReference<>(null);

        CompletableFuture<?> retryFuture =
                FutureUtils.retry(
                        () ->
                                CompletableFuture.supplyAsync(
                                        () -> {
                                            if (atomicInteger.incrementAndGet() == 2) {
                                                notificationLatch.trigger();
                                                try {
                                                    waitLatch.await();
                                                } catch (InterruptedException e) {
                                                    atomicThrowable.compareAndSet(null, e);
                                                }
                                            }

                                            throw new CompletionException(
                                                    new FlinkException("Test exception"));
                                        },
                                        EXECUTOR_RESOURCE.getExecutor()),
                        retries,
                        EXECUTOR_RESOURCE.getExecutor());

        // await that we have failed once
        notificationLatch.await();

        assertFalse(retryFuture.isDone());

        // cancel the retry future
        retryFuture.cancel(false);

        // let the retry operation continue
        waitLatch.trigger();

        assertTrue(retryFuture.isCancelled());
        assertEquals(2, atomicInteger.get());

        if (atomicThrowable.get() != null) {
            throw new FlinkException(
                    "Exception occurred in the retry operation.", atomicThrowable.get());
        }
    }

    /** Test that {@link FutureUtils#retry} should stop at non-retryable exception. */
    @Test
    public void testStopAtNonRetryableException() {
        final int retries = 10;
        final int notRetry = 3;
        final AtomicInteger atomicInteger = new AtomicInteger(0);
        final FlinkRuntimeException nonRetryableException =
                new FlinkRuntimeException("Non-retryable exception");
        CompletableFuture<Boolean> retryFuture =
                FutureUtils.retry(
                        () ->
                                CompletableFuture.supplyAsync(
                                        () -> {
                                            if (atomicInteger.incrementAndGet() == notRetry) {
                                                // throw non-retryable exception
                                                throw new CompletionException(
                                                        nonRetryableException);
                                            } else {
                                                throw new CompletionException(
                                                        new FlinkException("Test exception"));
                                            }
                                        },
                                        EXECUTOR_RESOURCE.getExecutor()),
                        retries,
                        throwable ->
                                ExceptionUtils.findThrowable(throwable, FlinkException.class)
                                        .isPresent(),
                        EXECUTOR_RESOURCE.getExecutor());

        try {
            retryFuture.get();
            fail("Exception should be thrown.");
        } catch (Exception ex) {
            assertThat(ex, FlinkMatchers.containsCause(nonRetryableException));
        }
        assertThat(atomicInteger.get(), is(notRetry));
    }

    /** Tests that retry with delay fails after having exceeded all retries. */
    @Test(expected = FutureUtils.RetryException.class)
    public void testRetryWithDelayRetryStrategyFailure() throws Throwable {
        CompletableFuture<?> retryFuture =
                FutureUtils.retryWithDelay(
                        () ->
                                FutureUtils.completedExceptionally(
                                        new FlinkException("Test exception")),
                        new FixedRetryStrategy(3, Duration.ofMillis(1L)),
                        new ScheduledExecutorServiceAdapter(EXECUTOR_RESOURCE.getExecutor()));

        try {
            retryFuture.get(TestingUtils.TIMEOUT.toMilliseconds(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException ee) {
            throw ExceptionUtils.stripExecutionException(ee);
        }
    }

    /**
     * Tests that the delay is respected between subsequent retries of a retry future with retry
     * delay.
     */
    public void testRetryWithDelayRetryStrategy() throws Exception {
        final int retries = 4;
        final AtomicInteger countDown = new AtomicInteger(retries);

        long start = System.currentTimeMillis();

        CompletableFuture<Boolean> retryFuture =
                FutureUtils.retryWithDelay(
                        () -> {
                            if (countDown.getAndDecrement() == 0) {
                                return CompletableFuture.completedFuture(true);
                            } else {
                                return FutureUtils.completedExceptionally(
                                        new FlinkException("Test exception."));
                            }
                        },
                        new ExponentialBackoffRetryStrategy(
                                retries, Duration.ofMillis(2L), Duration.ofMillis(5L)),
                        new ScheduledExecutorServiceAdapter(EXECUTOR_RESOURCE.getExecutor()));

        Boolean result = retryFuture.get();

        long completionTime = System.currentTimeMillis() - start;

        assertTrue(result);
        assertTrue(
                "The completion time should be at least retries times delay between retries.",
                completionTime >= (2 + 4 + 5 + 5));
    }

    /** Tests that all scheduled tasks are canceled if the retry future is being cancelled. */
    @Test
    public void testRetryWithDelayRetryStrategyCancellation() {
        final ManuallyTriggeredScheduledExecutor scheduledExecutor =
                new ManuallyTriggeredScheduledExecutor();

        CompletableFuture<?> retryFuture =
                FutureUtils.retryWithDelay(
                        () ->
                                FutureUtils.completedExceptionally(
                                        new FlinkException("Test exception")),
                        new FixedRetryStrategy(1, TestingUtils.infiniteDuration()),
                        scheduledExecutor);

        assertFalse(retryFuture.isDone());

        final Collection<ScheduledFuture<?>> scheduledTasks =
                scheduledExecutor.getActiveScheduledTasks();

        assertFalse(scheduledTasks.isEmpty());

        final ScheduledFuture<?> scheduledFuture = scheduledTasks.iterator().next();

        assertFalse(scheduledFuture.isDone());

        retryFuture.cancel(false);

        assertTrue(retryFuture.isCancelled());
        assertTrue(scheduledFuture.isCancelled());
    }

    /** Tests that a future is timed out after the specified timeout. */
    @Test
    public void testOrTimeout() throws Exception {
        final CompletableFuture<String> future = new CompletableFuture<>();
        final long timeout = 10L;

        FutureUtils.orTimeout(future, timeout, TimeUnit.MILLISECONDS);

        try {
            future.get();
        } catch (ExecutionException e) {
            assertTrue(ExceptionUtils.stripExecutionException(e) instanceof TimeoutException);
        }
    }

    @Test
    public void testRetryWithDelayRetryStrategyAndPredicate() throws Exception {
        final ScheduledExecutorService retryExecutor = EXECUTOR_RESOURCE.getExecutor();
        final String retryableExceptionMessage = "first exception";
        class TestStringSupplier implements Supplier<CompletableFuture<String>> {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public CompletableFuture<String> get() {
                if (counter.getAndIncrement() == 0) {
                    return FutureUtils.completedExceptionally(
                            new RuntimeException(retryableExceptionMessage));
                } else {
                    return FutureUtils.completedExceptionally(
                            new RuntimeException("should propagate"));
                }
            }
        }

        try {
            FutureUtils.retryWithDelay(
                            new TestStringSupplier(),
                            new FixedRetryStrategy(1, Duration.ZERO),
                            throwable ->
                                    throwable instanceof RuntimeException
                                            && throwable
                                                    .getMessage()
                                                    .contains(retryableExceptionMessage),
                            new ScheduledExecutorServiceAdapter(retryExecutor))
                    .get();
        } catch (final ExecutionException e) {
            assertThat(e.getMessage(), containsString("should propagate"));
        }
    }

    @Test
    public void testRunAfterwards() throws Exception {
        final CompletableFuture<Void> inputFuture = new CompletableFuture<>();
        final OneShotLatch runnableLatch = new OneShotLatch();

        final CompletableFuture<Void> runFuture =
                FutureUtils.runAfterwards(inputFuture, runnableLatch::trigger);

        assertThat(runnableLatch.isTriggered(), is(false));
        assertThat(runFuture.isDone(), is(false));

        inputFuture.complete(null);

        assertThat(runnableLatch.isTriggered(), is(true));
        assertThat(runFuture.isDone(), is(true));

        // check that this future is not exceptionally completed
        runFuture.get();
    }

    @Test
    public void testRunAfterwardsExceptional() throws Exception {
        final CompletableFuture<Void> inputFuture = new CompletableFuture<>();
        final OneShotLatch runnableLatch = new OneShotLatch();
        final FlinkException testException = new FlinkException("Test exception");

        final CompletableFuture<Void> runFuture =
                FutureUtils.runAfterwards(inputFuture, runnableLatch::trigger);

        assertThat(runnableLatch.isTriggered(), is(false));
        assertThat(runFuture.isDone(), is(false));

        inputFuture.completeExceptionally(testException);

        assertThat(runnableLatch.isTriggered(), is(true));
        assertThat(runFuture.isDone(), is(true));

        try {
            runFuture.get();
            fail("Expected an exceptional completion");
        } catch (ExecutionException ee) {
            assertThat(ExceptionUtils.stripExecutionException(ee), is(testException));
        }
    }

    @Test
    public void testComposeAfterwards() throws ExecutionException, InterruptedException {
        final CompletableFuture<Void> inputFuture = new CompletableFuture<>();
        final OneShotLatch composeLatch = new OneShotLatch();

        final CompletableFuture<Void> composeFuture =
                FutureUtils.composeAfterwards(
                        inputFuture,
                        () -> {
                            composeLatch.trigger();
                            return CompletableFuture.completedFuture(null);
                        });

        assertThat(composeLatch.isTriggered(), is(false));
        assertThat(composeFuture.isDone(), is(false));

        inputFuture.complete(null);

        assertThat(composeLatch.isTriggered(), is(true));
        assertThat(composeFuture.isDone(), is(true));

        // check that tthis future is not exceptionally completed
        composeFuture.get();
    }

    @Test
    public void testComposeAfterwardsFirstExceptional() throws InterruptedException {
        final CompletableFuture<Void> inputFuture = new CompletableFuture<>();
        final OneShotLatch composeLatch = new OneShotLatch();
        final FlinkException testException = new FlinkException("Test exception");

        final CompletableFuture<Void> composeFuture =
                FutureUtils.composeAfterwards(
                        inputFuture,
                        () -> {
                            composeLatch.trigger();
                            return CompletableFuture.completedFuture(null);
                        });

        assertThat(composeLatch.isTriggered(), is(false));
        assertThat(composeFuture.isDone(), is(false));

        inputFuture.completeExceptionally(testException);

        assertThat(composeLatch.isTriggered(), is(true));
        assertThat(composeFuture.isDone(), is(true));

        // check that this future is not exceptionally completed
        try {
            composeFuture.get();
            fail("Expected an exceptional completion");
        } catch (ExecutionException ee) {
            assertThat(ExceptionUtils.stripExecutionException(ee), is(testException));
        }
    }

    @Test
    public void testComposeAfterwardsSecondExceptional() throws InterruptedException {
        final CompletableFuture<Void> inputFuture = new CompletableFuture<>();
        final OneShotLatch composeLatch = new OneShotLatch();
        final FlinkException testException = new FlinkException("Test exception");

        final CompletableFuture<Void> composeFuture =
                FutureUtils.composeAfterwards(
                        inputFuture,
                        () -> {
                            composeLatch.trigger();
                            return FutureUtils.completedExceptionally(testException);
                        });

        assertThat(composeLatch.isTriggered(), is(false));
        assertThat(composeFuture.isDone(), is(false));

        inputFuture.complete(null);

        assertThat(composeLatch.isTriggered(), is(true));
        assertThat(composeFuture.isDone(), is(true));

        // check that this future is not exceptionally completed
        try {
            composeFuture.get();
            fail("Expected an exceptional completion");
        } catch (ExecutionException ee) {
            assertThat(ExceptionUtils.stripExecutionException(ee), is(testException));
        }
    }

    @Test
    public void testComposeAfterwardsBothExceptional() throws InterruptedException {
        final CompletableFuture<Void> inputFuture = new CompletableFuture<>();
        final FlinkException testException1 = new FlinkException("Test exception1");
        final FlinkException testException2 = new FlinkException("Test exception2");
        final OneShotLatch composeLatch = new OneShotLatch();

        final CompletableFuture<Void> composeFuture =
                FutureUtils.composeAfterwards(
                        inputFuture,
                        () -> {
                            composeLatch.trigger();
                            return FutureUtils.completedExceptionally(testException2);
                        });

        assertThat(composeLatch.isTriggered(), is(false));
        assertThat(composeFuture.isDone(), is(false));

        inputFuture.completeExceptionally(testException1);

        assertThat(composeLatch.isTriggered(), is(true));
        assertThat(composeFuture.isDone(), is(true));

        // check that this future is not exceptionally completed
        try {
            composeFuture.get();
            fail("Expected an exceptional completion");
        } catch (ExecutionException ee) {
            final Throwable actual = ExceptionUtils.stripExecutionException(ee);
            assertThat(actual, is(testException1));
            assertThat(actual.getSuppressed(), arrayWithSize(1));
            assertThat(actual.getSuppressed()[0], is(testException2));
        }
    }

    @Test
    public void testCompleteAll() throws Exception {
        final CompletableFuture<String> inputFuture1 = new CompletableFuture<>();
        final CompletableFuture<Integer> inputFuture2 = new CompletableFuture<>();

        final List<CompletableFuture<?>> futuresToComplete =
                Arrays.asList(inputFuture1, inputFuture2);
        final FutureUtils.ConjunctFuture<Void> completeFuture =
                FutureUtils.completeAll(futuresToComplete);

        assertThat(completeFuture.isDone(), is(false));
        assertThat(completeFuture.getNumFuturesCompleted(), is(0));
        assertThat(completeFuture.getNumFuturesTotal(), is(futuresToComplete.size()));

        inputFuture2.complete(42);

        assertThat(completeFuture.isDone(), is(false));
        assertThat(completeFuture.getNumFuturesCompleted(), is(1));

        inputFuture1.complete("foobar");

        assertThat(completeFuture.isDone(), is(true));
        assertThat(completeFuture.getNumFuturesCompleted(), is(2));

        completeFuture.get();
    }

    @Test
    public void testCompleteAllPartialExceptional() throws Exception {
        final CompletableFuture<String> inputFuture1 = new CompletableFuture<>();
        final CompletableFuture<Integer> inputFuture2 = new CompletableFuture<>();

        final List<CompletableFuture<?>> futuresToComplete =
                Arrays.asList(inputFuture1, inputFuture2);
        final FutureUtils.ConjunctFuture<Void> completeFuture =
                FutureUtils.completeAll(futuresToComplete);

        assertThat(completeFuture.isDone(), is(false));
        assertThat(completeFuture.getNumFuturesCompleted(), is(0));
        assertThat(completeFuture.getNumFuturesTotal(), is(futuresToComplete.size()));

        final FlinkException testException1 = new FlinkException("Test exception 1");
        inputFuture2.completeExceptionally(testException1);

        assertThat(completeFuture.isDone(), is(false));
        assertThat(completeFuture.getNumFuturesCompleted(), is(1));

        inputFuture1.complete("foobar");

        assertThat(completeFuture.isDone(), is(true));
        assertThat(completeFuture.getNumFuturesCompleted(), is(2));

        try {
            completeFuture.get();
            fail("Expected an exceptional completion");
        } catch (ExecutionException ee) {
            assertThat(ExceptionUtils.stripExecutionException(ee), is(testException1));
        }
    }

    @Test
    public void testCompleteAllExceptional() throws Exception {
        final CompletableFuture<String> inputFuture1 = new CompletableFuture<>();
        final CompletableFuture<Integer> inputFuture2 = new CompletableFuture<>();

        final List<CompletableFuture<?>> futuresToComplete =
                Arrays.asList(inputFuture1, inputFuture2);
        final FutureUtils.ConjunctFuture<Void> completeFuture =
                FutureUtils.completeAll(futuresToComplete);

        assertThat(completeFuture.isDone(), is(false));
        assertThat(completeFuture.getNumFuturesCompleted(), is(0));
        assertThat(completeFuture.getNumFuturesTotal(), is(futuresToComplete.size()));

        final FlinkException testException1 = new FlinkException("Test exception 1");
        inputFuture1.completeExceptionally(testException1);

        assertThat(completeFuture.isDone(), is(false));
        assertThat(completeFuture.getNumFuturesCompleted(), is(1));

        final FlinkException testException2 = new FlinkException("Test exception 2");
        inputFuture2.completeExceptionally(testException2);

        assertThat(completeFuture.isDone(), is(true));
        assertThat(completeFuture.getNumFuturesCompleted(), is(2));

        try {
            completeFuture.get();
            fail("Expected an exceptional completion");
        } catch (ExecutionException ee) {
            final Throwable actual = ExceptionUtils.stripExecutionException(ee);

            final Throwable[] suppressed = actual.getSuppressed();
            final FlinkException suppressedException;

            if (actual.equals(testException1)) {
                suppressedException = testException2;
            } else {
                suppressedException = testException1;
            }

            assertThat(suppressed, is(not(emptyArray())));
            assertThat(suppressed, arrayContaining(suppressedException));
        }
    }

    @Test
    public void testSupplyAsyncFailure() throws Exception {
        final String exceptionMessage = "Test exception";
        final FlinkException testException = new FlinkException(exceptionMessage);
        final CompletableFuture<Object> future =
                FutureUtils.supplyAsync(
                        () -> {
                            throw testException;
                        },
                        EXECUTOR_RESOURCE.getExecutor());

        try {
            future.get();
            fail("Expected an exception.");
        } catch (ExecutionException e) {
            assertThat(
                    ExceptionUtils.findThrowableWithMessage(e, exceptionMessage).isPresent(),
                    is(true));
        }
    }

    @Test
    public void testSupplyAsync() throws Exception {
        final Object expectedResult = new Object();
        final CompletableFuture<Object> future =
                FutureUtils.supplyAsync(() -> expectedResult, EXECUTOR_RESOURCE.getExecutor());

        assertEquals(future.get(), expectedResult);
    }

    @Test
    public void testHandleAsyncIfNotDone() {
        testFutureContinuation(
                (CompletableFuture<?> future, Executor executor) ->
                        FutureUtils.handleAsyncIfNotDone(future, executor, (o, t) -> null));
    }

    @Test
    public void testApplyAsyncIfNotDone() {
        testFutureContinuation(
                (CompletableFuture<?> future, Executor executor) ->
                        FutureUtils.thenApplyAsyncIfNotDone(future, executor, o -> null));
    }

    @Test
    public void testComposeAsyncIfNotDone() {
        testFutureContinuation(
                (CompletableFuture<?> future, Executor executor) ->
                        FutureUtils.thenComposeAsyncIfNotDone(future, executor, o -> null));
    }

    @Test
    public void testWhenCompleteAsyncIfNotDone() {
        testFutureContinuation(
                (CompletableFuture<?> future, Executor executor) ->
                        FutureUtils.whenCompleteAsyncIfNotDone(
                                future, executor, (o, throwable) -> {}));
    }

    @Test
    public void testThenAcceptAsyncIfNotDone() {
        testFutureContinuation(
                (CompletableFuture<?> future, Executor executor) ->
                        FutureUtils.thenAcceptAsyncIfNotDone(future, executor, o -> {}));
    }

    private void testFutureContinuation(
            BiFunction<CompletableFuture<?>, Executor, CompletableFuture<?>>
                    testFunctionGenerator) {

        CompletableFuture<?> startFuture = new CompletableFuture<>();
        final AtomicBoolean runWithExecutor = new AtomicBoolean(false);

        Executor executor =
                r -> {
                    r.run();
                    runWithExecutor.set(true);
                };

        // branch for a start future that has not completed
        CompletableFuture<?> continuationFuture =
                testFunctionGenerator.apply(startFuture, executor);
        Assert.assertFalse(continuationFuture.isDone());

        startFuture.complete(null);

        Assert.assertTrue(runWithExecutor.get());
        Assert.assertTrue(continuationFuture.isDone());

        // branch for a start future that was completed
        runWithExecutor.set(false);

        continuationFuture = testFunctionGenerator.apply(startFuture, executor);

        Assert.assertFalse(runWithExecutor.get());
        Assert.assertTrue(continuationFuture.isDone());
    }

    @Test
    public void testHandleExceptionWithCompletedFuture() {
        final CompletableFuture<String> future = CompletableFuture.completedFuture("foobar");
        final CompletableFuture<String> handled =
                FutureUtils.handleException(future, Exception.class, exception -> "handled");
        assertEquals("foobar", handled.join());
    }

    @Test
    public void testHandleExceptionWithNormalCompletion() {
        final CompletableFuture<String> future = new CompletableFuture<>();
        final CompletableFuture<String> handled =
                FutureUtils.handleException(future, Exception.class, exception -> "handled");
        future.complete("foobar");
        assertEquals("foobar", handled.join());
    }

    @Test
    public void testHandleExceptionWithMatchingExceptionallyCompletedFuture() {
        final CompletableFuture<String> future = new CompletableFuture<>();
        final CompletableFuture<String> handled =
                FutureUtils.handleException(
                        future, UnsupportedOperationException.class, exception -> "handled");
        future.completeExceptionally(new UnsupportedOperationException("foobar"));
        assertEquals("handled", handled.join());
    }

    @Test
    public void testHandleExceptionWithNotMatchingExceptionallyCompletedFuture() {
        final CompletableFuture<String> future = new CompletableFuture<>();
        final CompletableFuture<String> handled =
                FutureUtils.handleException(
                        future, UnsupportedOperationException.class, exception -> "handled");
        final IllegalArgumentException futureException = new IllegalArgumentException("foobar");
        future.completeExceptionally(futureException);
        final CompletionException completionException =
                assertThrows(CompletionException.class, handled::join);
        assertEquals(futureException, completionException.getCause());
    }

    @Test
    public void testHandleExceptionWithThrowingExceptionHandler() {
        final CompletableFuture<String> future = new CompletableFuture<>();
        final IllegalStateException handlerException =
                new IllegalStateException("something went terribly wrong");
        final CompletableFuture<String> handled =
                FutureUtils.handleException(
                        future,
                        UnsupportedOperationException.class,
                        exception -> {
                            throw handlerException;
                        });
        future.completeExceptionally(new UnsupportedOperationException("foobar"));
        final CompletionException completionException =
                assertThrows(CompletionException.class, handled::join);
        assertEquals(handlerException, completionException.getCause());
    }

    @Test
    public void testHandleUncaughtExceptionWithCompletedFuture() {
        final CompletableFuture<String> future = CompletableFuture.completedFuture("foobar");
        final TestingUncaughtExceptionHandler uncaughtExceptionHandler =
                new TestingUncaughtExceptionHandler();

        FutureUtils.handleUncaughtException(future, uncaughtExceptionHandler);
        assertThat(uncaughtExceptionHandler.hasBeenCalled(), is(false));
    }

    @Test
    public void testHandleUncaughtExceptionWithNormalCompletion() {
        final CompletableFuture<String> future = new CompletableFuture<>();

        final TestingUncaughtExceptionHandler uncaughtExceptionHandler =
                new TestingUncaughtExceptionHandler();

        FutureUtils.handleUncaughtException(future, uncaughtExceptionHandler);
        future.complete("barfoo");
        assertThat(uncaughtExceptionHandler.hasBeenCalled(), is(false));
    }

    @Test
    public void testHandleUncaughtExceptionWithExceptionallyCompletedFuture() {
        final CompletableFuture<String> future =
                FutureUtils.completedExceptionally(new FlinkException("foobar"));

        final TestingUncaughtExceptionHandler uncaughtExceptionHandler =
                new TestingUncaughtExceptionHandler();

        FutureUtils.handleUncaughtException(future, uncaughtExceptionHandler);
        assertThat(uncaughtExceptionHandler.hasBeenCalled(), is(true));
    }

    @Test
    public void testHandleUncaughtExceptionWithExceptionallyCompletion() {
        final CompletableFuture<String> future = new CompletableFuture<>();

        final TestingUncaughtExceptionHandler uncaughtExceptionHandler =
                new TestingUncaughtExceptionHandler();

        FutureUtils.handleUncaughtException(future, uncaughtExceptionHandler);
        assertThat(uncaughtExceptionHandler.hasBeenCalled(), is(false));
        future.completeExceptionally(new FlinkException("barfoo"));
        assertThat(uncaughtExceptionHandler.hasBeenCalled(), is(true));
    }

    /**
     * Tests the behavior of {@link FutureUtils#handleUncaughtException(CompletableFuture,
     * Thread.UncaughtExceptionHandler)} with a custom fallback exception handler to avoid
     * triggering {@code System.exit}.
     */
    @Test
    public void testHandleUncaughtExceptionWithBuggyErrorHandlingCode() {
        final Exception actualProductionCodeError =
                new Exception(
                        "Actual production code error that should be caught by the error handler.");

        final RuntimeException errorHandlingException =
                new RuntimeException("Expected test error in error handling code.");
        final Thread.UncaughtExceptionHandler buggyActualExceptionHandler =
                (thread, ignoredActualException) -> {
                    throw errorHandlingException;
                };

        final AtomicReference<Throwable> caughtErrorHandlingException = new AtomicReference<>();
        final Thread.UncaughtExceptionHandler fallbackExceptionHandler =
                (thread, errorHandlingEx) -> caughtErrorHandlingException.set(errorHandlingEx);

        FutureUtils.handleUncaughtException(
                FutureUtils.completedExceptionally(actualProductionCodeError),
                buggyActualExceptionHandler,
                fallbackExceptionHandler);

        final Throwable actualError = caughtErrorHandlingException.get();
        assertThat(actualError, instanceOf(IllegalStateException.class));
        assertThat(actualError.getCause(), is(errorHandlingException));
        assertThat(actualError.getSuppressed(), arrayContaining(actualProductionCodeError));
    }

    private static class TestingUncaughtExceptionHandler
            implements Thread.UncaughtExceptionHandler {

        private Throwable exception = null;

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            exception = e;
        }

        private boolean hasBeenCalled() {
            return exception != null;
        }
    }

    @Test
    public void testForwardNormal() throws Exception {
        final CompletableFuture<String> source = new CompletableFuture<>();
        final CompletableFuture<String> target = new CompletableFuture<>();

        FutureUtils.forward(source, target);

        assertThat(target.isDone(), is(source.isDone()));

        source.complete("foobar");

        assertThat(target.isDone(), is(source.isDone()));
        assertThat(target.get(), is(equalTo(source.get())));
    }

    @Test
    public void testForwardExceptionally() {
        final CompletableFuture<String> source = new CompletableFuture<>();
        final CompletableFuture<String> target = new CompletableFuture<>();

        FutureUtils.forward(source, target);

        assertThat(target.isDone(), is(source.isDone()));

        source.completeExceptionally(new FlinkException("foobar"));

        assertThat(target.isDone(), is(source.isDone()));

        Throwable targetException = getThrowable(target);
        Throwable actualException = getThrowable(source);

        assertThat(targetException, is(equalTo(actualException)));
    }

    @Test
    public void testForwardAsync() throws Exception {
        final CompletableFuture<String> source = new CompletableFuture<>();
        final CompletableFuture<String> target = new CompletableFuture<>();
        final ManuallyTriggeredScheduledExecutor executor =
                new ManuallyTriggeredScheduledExecutor();

        FutureUtils.forwardAsync(source, target, executor);

        source.complete("foobar");

        assertThat(target.isDone(), is(false));

        // execute the forward action
        executor.triggerAll();

        assertThat(target.get(), is(equalTo(source.get())));
    }

    @Test
    public void testGetWithoutException() {
        final CompletableFuture<Integer> completableFuture = new CompletableFuture<>();
        completableFuture.complete(1);

        assertEquals(new Integer(1), FutureUtils.getWithoutException(completableFuture));
    }

    @Test
    public void testGetWithoutExceptionWithAnException() {
        final CompletableFuture<Integer> completableFuture = new CompletableFuture<>();
        completableFuture.completeExceptionally(new RuntimeException("expected"));

        assertNull(FutureUtils.getWithoutException(completableFuture));
    }

    @Test
    public void testGetWithoutExceptionWithoutFinishing() {
        final CompletableFuture<Integer> completableFuture = new CompletableFuture<>();

        assertNull(FutureUtils.getWithoutException(completableFuture));
    }

    @Test
    public void testSwitchExecutorForNormallyCompletedFuture() {
        final CompletableFuture<String> source = new CompletableFuture<>();

        final ExecutorService singleThreadExecutor = EXECUTOR_RESOURCE.getExecutor();

        final CompletableFuture<String> resultFuture =
                FutureUtils.switchExecutor(source, singleThreadExecutor);

        final String expectedThreadName =
                FutureUtils.supplyAsync(
                                () -> Thread.currentThread().getName(), singleThreadExecutor)
                        .join();
        final String expectedValue = "foobar";

        final CompletableFuture<Void> assertionFuture =
                resultFuture.handle(
                        (s, throwable) -> {
                            assertThat(s, is(expectedValue));
                            assertThat(Thread.currentThread().getName(), is(expectedThreadName));

                            return null;
                        });
        source.complete(expectedValue);

        assertionFuture.join();
    }

    @Test
    public void testSwitchExecutorForExceptionallyCompletedFuture() {
        final CompletableFuture<String> source = new CompletableFuture<>();

        final ExecutorService singleThreadExecutor = EXECUTOR_RESOURCE.getExecutor();

        final CompletableFuture<String> resultFuture =
                FutureUtils.switchExecutor(source, singleThreadExecutor);

        final String expectedThreadName =
                FutureUtils.supplyAsync(
                                () -> Thread.currentThread().getName(), singleThreadExecutor)
                        .join();
        final Exception expectedException = new Exception("foobar");

        final CompletableFuture<Void> assertionFuture =
                resultFuture.handle(
                        (s, throwable) -> {
                            assertThat(throwable, FlinkMatchers.containsCause(expectedException));
                            assertThat(Thread.currentThread().getName(), is(expectedThreadName));

                            return null;
                        });
        source.completeExceptionally(expectedException);

        assertionFuture.join();
    }

    private static Throwable getThrowable(CompletableFuture<?> completableFuture) {
        try {
            completableFuture.join();
        } catch (CompletionException e) {
            return e.getCause();
        }

        throw new AssertionError("Future has not been completed exceptionally.");
    }
}
