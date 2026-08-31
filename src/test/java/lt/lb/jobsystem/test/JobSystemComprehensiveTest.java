package lt.lb.jobsystem.test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Objects;

import com.github.laim0nas100.jobsystem.Dependencies;
import com.github.laim0nas100.jobsystem.Job;
import com.github.laim0nas100.jobsystem.JobExecutor;
import com.github.laim0nas100.jobsystem.ScheduledJobExecutor;
import com.github.laim0nas100.jobsystem.dependency.Dependency;
import com.github.laim0nas100.jobsystem.dependency.JobDependency;
import com.github.laim0nas100.jobsystem.events.SystemJobEventName;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Comprehensive test suite for JobSystem
 * Tests: dynamic dependencies, failed-to-start recovery, listener behavior, 
 * state transitions, stress scenarios, and edge cases.
 */
public class JobSystemComprehensiveTest {

    private ExecutorService executor;
    private JobExecutor jobExecutor;

    @Before
    public void setup() {
        executor = Executors.newFixedThreadPool(8);
        jobExecutor = new JobExecutor(2, 2, true, executor); // Default optimistic submit
    }

    /**
     * Test that jobs are re-queued after failed-to-start.
     * Simulates dynamic dependency becoming satisfied after initial failure.
     */
    @Test
    public void testFailedToStartRecovery() throws InterruptedException {
        AtomicBoolean depSatisfied = new AtomicBoolean(false);
        AtomicInteger executionCount = new AtomicInteger(0);

        Job<Void> job = new Job<>(() -> {
            executionCount.incrementAndGet();
            return null;
        });

        // Dynamic dependency: only satisfied after 100ms
        job.addDependency((Job j) -> depSatisfied.get());

        jobExecutor.submit(job);

        // First scan: job not ready, queued
        jobExecutor.rescanJobs();
        Thread.sleep(50);

        // Dependency becomes satisfied
        depSatisfied.set(true);
        jobExecutor.rescanJobs();

        jobExecutor.awaitJobEmptiness(1, TimeUnit.SECONDS);
        assertEquals("Job should execute after dependency satisfied", 1, executionCount.get());
    }

    /**
     * Test listener exception handling. Ensures one listener's exception 
     * doesn't prevent other listeners from firing.
     */
    @Test
    public void testListenerExceptionHandling() throws InterruptedException {
        AtomicInteger listenerCount = new AtomicInteger(0);
        AtomicBoolean exceptionListenerFired = new AtomicBoolean(false);

        Job<Void> job = new Job<>(() -> {
            return null;
        });

        // First listener throws
        job.addListener(SystemJobEventName.ON_DONE, (j, c, d) -> {
            listenerCount.incrementAndGet();
            throw new RuntimeException("Intentional test exception");
        });

        // Second listener should still fire
        job.addListener(SystemJobEventName.ON_DONE, (j, c, d) -> {
            listenerCount.incrementAndGet();
        });

        // Exception event listener
        job.addListener(SystemJobEventName.ON_EXCEPTIONAL_EVENT, (j, c, d) -> {
            exceptionListenerFired.set(true);
        });

        jobExecutor.submit(job);
        jobExecutor.awaitJobEmptiness(1, TimeUnit.SECONDS);

        assertEquals("Both listeners should fire", 2, listenerCount.get());
        assertTrue("Exception should be caught and fired", exceptionListenerFired.get());
    }

    /**
     * Test dynamic dependency becoming impossible mid-execution flow.
     * Job should be discarded if dependency becomes impossible.
     */
    @Test
    public void testDynamicImpossibleDependency() throws InterruptedException {
        AtomicBoolean depPossible = new AtomicBoolean(false);
        AtomicBoolean jobExecuted = new AtomicBoolean(false);
        AtomicBoolean jobDiscarded = new AtomicBoolean(false);

        Job<Void> job = new Job<>(() -> {
            jobExecuted.set(true);
            return null;
        });

        job.addDependency(new Dependency() {
            @Override
            public boolean isCompleted(Job j) {
                return true;
            }

            @Override
            public boolean isPossible() {
                return depPossible.get();
            }
        });

        job.addListener(SystemJobEventName.ON_DISCARDED, (j, c, d) -> {
            jobDiscarded.set(true);
        });

        jobExecutor.submit(job);

        jobExecutor.awaitJobEmptiness(1, TimeUnit.SECONDS);

        assertFalse("Job should not execute", jobExecuted.get());
        assertTrue("Job should be discarded", jobDiscarded.get());
    }

    /**
     * Test concurrent job submissions with dependencies.
     * Multiple threads submitting jobs simultaneously.
     */
    @Test
    public void testConcurrentSubmissions() throws InterruptedException {
        AtomicInteger executedCount = new AtomicInteger(0);
        int jobCount = 100;

        List<Job> jobs = new ArrayList<>();
        for (int i = 0; i < jobCount; i++) {
            Job<Void> job = new Job<>(() -> {
                executedCount.incrementAndGet();
                Thread.sleep(10);
                return null;
            });
            jobs.add(job);
        }

        // Submit from multiple threads
        Thread[] threads = new Thread[4];
        for (int t = 0; t < 4; t++) {
            final int start = t * (jobCount / 4);
            final int end = (t + 1) * (jobCount / 4);
            threads[t] = new Thread(() -> {
                for (int i = start; i < end; i++) {
                    jobExecutor.submit(jobs.get(i));
                }
            });
            threads[t].start();
        }

        for (Thread t : threads) {
            t.join();
        }

        jobExecutor.awaitJobEmptiness(10, TimeUnit.SECONDS);
        assertEquals("All jobs should execute", jobCount, executedCount.get());
    }

    /**
     * Test job cancellation before execution.
     */
    @Test
    public void testJobCancellation() throws InterruptedException {
        AtomicBoolean jobExecuted = new AtomicBoolean(false);
        AtomicBoolean jobAborted = new AtomicBoolean(false);

        Job<Void> job = new Job<>(() -> {
            jobExecuted.set(true);
            return null;
        });

        job.addListener(SystemJobEventName.ON_ABORTED, (j, c, d) -> {
            jobAborted.set(true);
        });

        jobExecutor.submit(job);
        job.cancel(); // Cancel before scan
        jobExecutor.rescanJobs();

        jobExecutor.awaitJobEmptiness(1, TimeUnit.SECONDS);

        assertFalse("Cancelled job should not execute", jobExecuted.get());
        assertTrue("Cancelled job should fire abort event", jobAborted.get());
    }



    /**
     * Test dependency chain with event-based dependencies.
     * A executes, then B waits for A's ON_SUCCESSFUL, then C waits for B's ON_SUCCESSFUL.
     */
    @Test
    public void testEventBasedDependencyChain() throws InterruptedException {
        AtomicInteger aExecuted = new AtomicInteger(0);
        AtomicInteger bExecuted = new AtomicInteger(0);
        AtomicInteger cExecuted = new AtomicInteger(0);

        Job<Void> jobA = new Job<>(() -> {
            aExecuted.incrementAndGet();
            return null;
        });

        Job<Void> jobB = new Job<>(() -> {
            bExecuted.incrementAndGet();
            return null;
        });

        Job<Void> jobC = new Job<>(() -> {
            cExecuted.incrementAndGet();
            return null;
        });

        // B depends on A's successful completion
        jobB.addDependency(Dependencies.standard(jobA, SystemJobEventName.ON_SUCCESSFUL));

        // C depends on B's successful completion
        jobC.addDependency(Dependencies.standard(jobB, SystemJobEventName.ON_SUCCESSFUL));

        jobExecutor.submit(jobA);
        jobExecutor.submit(jobB);
        jobExecutor.submit(jobC);

        jobExecutor.awaitJobEmptiness(2, TimeUnit.SECONDS);

        assertEquals("A should execute", 1, aExecuted.get());
        assertEquals("B should execute after A", 1, bExecuted.get());
        assertEquals("C should execute after B", 1, cExecuted.get());
    }

    /**
     * Test mutual exclusion with many jobs.
     * Ensures only one job executes at a time from a set.
     */
    @Test
    public void testMutualExclusionScaling() throws InterruptedException {
        AtomicInteger concurrentExecuting = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger totalExecuted = new AtomicInteger(0);

        int jobCount = 50;
        List<Job> jobs = new ArrayList<>();

        for (int i = 0; i < jobCount; i++) {
            Job<Void> job = new Job<>(() -> {
                int current = concurrentExecuting.incrementAndGet();
                maxConcurrent.set(Math.max(maxConcurrent.get(), current));
                totalExecuted.incrementAndGet();

                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                concurrentExecuting.decrementAndGet();
                return null;
            });
            jobs.add(job);
        }

        Dependencies.mutuallyExclusive(jobs);
        jobExecutor.submitAll(jobs);

        jobExecutor.awaitJobEmptiness(5, TimeUnit.SECONDS);

        assertEquals("All jobs should execute", jobCount, totalExecuted.get());
        assertEquals("Only one job at a time", 1, maxConcurrent.get());
    }

    /**
     * Test scheduler with periodic rescans (ScheduledJobExecutor).
     * Jobs with time-based dependencies.
     */
    @Test
    public void testScheduledExecutorWithTimeDependency() throws InterruptedException {
        ExecutorService scheduledExec = Executors.newFixedThreadPool(4);
        ScheduledJobExecutor schExecutor = new ScheduledJobExecutor(scheduledExec);

        long startTime = System.currentTimeMillis();
        AtomicBoolean jobExecuted = new AtomicBoolean(false);

        Job<Void> job = new Job<>(() -> {
            jobExecuted.set(true);
            return null;
        });

        // Job only ready after 500ms
        job.addDependency((Job j) -> {
            return System.currentTimeMillis() - startTime >= 500;
        });

        schExecutor.submit(job);
        schExecutor.awaitJobEmptiness(2, TimeUnit.SECONDS);

        assertTrue("Job should execute after time passes", jobExecuted.get());
        long elapsed = System.currentTimeMillis() - startTime;
        assertTrue("Should take at least 500ms", elapsed >= 500);

        schExecutor.shutdown();
        scheduledExec.shutdown();
    }

    /**
     * Test "any" dependency combinator.
     * Job ready when ANY of multiple dependencies satisfied (event fired).
     */
    @Test
    public void testAnyDependency() throws InterruptedException {
        Job<Void> depJob1 = new Job<>(() -> null);
        Job<Void> depJob2 = new Job<>(() -> null);
        AtomicInteger executed = new AtomicInteger(0);

        Job<Void> mainJob = new Job<>(() -> {
            executed.incrementAndGet();
            return null;
        });

        // Main job ready if EITHER depJob1 OR depJob2 succeeds
        mainJob.addDependency(Dependencies.any(
            Dependencies.standard(depJob1, SystemJobEventName.ON_SUCCESSFUL),
            Dependencies.standard(depJob2, SystemJobEventName.ON_SUCCESSFUL)
        ));

        // Only execute depJob1, leave depJob2 unexecuted
        jobExecutor.submit(depJob1);
        jobExecutor.submit(mainJob);

        jobExecutor.awaitJobEmptiness(1, TimeUnit.SECONDS);

        assertEquals("Main job should execute when ANY dependency met", 1, executed.get());
    }

    /**
     * Test "all" dependency combinator.
     * Job ready only when ALL dependencies satisfied (events fired).
     */
    @Test
    public void testAllDependency() throws InterruptedException {
        Job<Void> depJob1 = new Job<>(() -> null);
        Job<Void> depJob2 = new Job<>(() -> null);
        AtomicInteger executed = new AtomicInteger(0);

        Job<Void> mainJob = new Job<>(() -> {
            executed.incrementAndGet();
            return null;
        });

        // Main job ready only if BOTH depJob1 AND depJob2 succeed
        mainJob.addDependency(Dependencies.all(
            Dependencies.standard(depJob1, SystemJobEventName.ON_SUCCESSFUL),
            Dependencies.standard(depJob2, SystemJobEventName.ON_SUCCESSFUL)
        ));

        jobExecutor.submit(depJob1);
        jobExecutor.submit(depJob2);
        jobExecutor.submit(mainJob);

        jobExecutor.awaitJobEmptiness(1, TimeUnit.SECONDS);

        assertEquals("Main job should execute when ALL dependencies met", 1, executed.get());
    }

    /**
     * Test that jobs are properly removed from pending list after completion.
     * Verifies no memory leak.
     */
    @Test
    public void testJobRemovalAfterCompletion() throws InterruptedException {
        int jobCount = 1000;
        AtomicInteger executed = new AtomicInteger(0);

        for (int i = 0; i < jobCount; i++) {
            Job<Void> job = new Job<>(() -> {
                executed.incrementAndGet();
                return null;
            });
            jobExecutor.submit(job);
        }

        jobExecutor.awaitJobEmptiness(10, TimeUnit.SECONDS);

        assertEquals("All jobs executed", jobCount, executed.get());
        assertTrue("Executor should be empty", jobExecutor.isEmpty());
    }

    /**
     * Test forward chaining with many jobs.
     */
    @Test
    public void testLargeForwardChain() throws InterruptedException {
        int chainLength = 100;
        List<Job> chain = new ArrayList<>();
        AtomicInteger executionOrder = new AtomicInteger(0);
        AtomicInteger[] orderTracker = new AtomicInteger[chainLength];

        for (int i = 0; i < chainLength; i++) {
            final int index = i;
            Job<Void> job = new Job<>(() -> {
                orderTracker[index] = new AtomicInteger(executionOrder.incrementAndGet());
                Thread.sleep(5);
                return null;
            });
            chain.add(job);
            orderTracker[i] = new AtomicInteger(0);
        }

        Dependencies.forwardChain(chain, SystemJobEventName.ON_SUCCESSFUL);
        jobExecutor.submitAll(chain);

        jobExecutor.awaitJobEmptiness(30, TimeUnit.SECONDS);

        assertEquals("All jobs executed", chainLength, executionOrder.get());
        for (int i = 1; i < chainLength; i++) {
            assertTrue("Jobs should execute in order", 
                orderTracker[i].get() > orderTracker[i - 1].get());
        }
    }



    /**
     * Stress test: many jobs, many dependencies, many listeners.
     */
    @Test
    public void testComplexStressScenario() throws InterruptedException {
        int jobCount = 200;
        ExecutorService stressExecutor = Executors.newFixedThreadPool(16);
        JobExecutor stressJobExecutor = new JobExecutor(4, 4, true, stressExecutor);

        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger eventsFired = new AtomicInteger(0);

        List<Job> jobs = new ArrayList<>();
        for (int i = 0; i < jobCount; i++) {
            final int idx = i;
            Job<Integer> job = new Job<>(() -> {
                Thread.sleep((long) (Math.random() * 20));
                return idx;
            });

            // Add multiple listeners
            job.addListener(SystemJobEventName.ON_DONE, (j, c, d) -> eventsFired.incrementAndGet());
            job.addListener(SystemJobEventName.ON_EXECUTE, (j, c, d) -> eventsFired.incrementAndGet());

            // Random dependencies
            if (i > 0 && Math.random() > 0.7) {
                job.addDependency(Dependencies.whileNotExecuting(jobs.get(i - 1)));
            }

            job.addListener(SystemJobEventName.ON_DONE, (j, c, d) -> completed.incrementAndGet());
            jobs.add(job);
        }

        stressJobExecutor.submitAll(jobs);
        stressJobExecutor.awaitJobEmptiness(30, TimeUnit.SECONDS);

        assertEquals("All jobs completed", jobCount, completed.get());
        assertTrue("Events fired", eventsFired.get() > 0);
        assertTrue("Executor empty", stressJobExecutor.isEmpty());

        stressJobExecutor.shutdown();
        stressExecutor.shutdown();
    }

    /**
     * Test optimistic submit strategy: ready jobs scheduled immediately.
     * Job with no dependencies should go straight to executor, not queue.
     */
    @Test
    public void testOptimisticSubmitReady() throws InterruptedException {
        AtomicBoolean jobScheduledEvent = new AtomicBoolean(false);
        AtomicBoolean jobExecuted = new AtomicBoolean(false);

        Job<Void> job = new Job<>(() -> {
            jobExecuted.set(true);
            return null;
        });

        job.addListener(SystemJobEventName.ON_SCHEDULED, (j, c, d) -> {
            jobScheduledEvent.set(true);
        });

        JobExecutor optimisticExecutor = new JobExecutor(executor);
        optimisticExecutor.submit(job, true); // Optimistic submit

        optimisticExecutor.awaitJobEmptiness(1, TimeUnit.SECONDS);

        assertTrue("Job should be scheduled event fired", jobScheduledEvent.get());
        assertTrue("Job should execute", jobExecuted.get());
    }

    /**
     * Test optimistic submit strategy: jobs with unsatisfied dependencies queued.
     */
    @Test
    public void testOptimisticSubmitQueued() throws InterruptedException {
        AtomicBoolean jobExecuted = new AtomicBoolean(false);

        Job<Void> dependencyJob = new Job<>(() -> null);
        Job<Void> job = new Job<>(() -> {
            jobExecuted.set(true);
            return null;
        });

        // Job depends on dependency job's successful completion
        job.addDependency(Dependencies.standard(dependencyJob, SystemJobEventName.ON_SUCCESSFUL));

        JobExecutor optimisticExecutor = new JobExecutor(executor);
        optimisticExecutor.submit(job, true); // Try optimistic, should queue (dep not met)

        // Job should be queued, not executed yet
        assertFalse("Job should not execute yet", jobExecuted.get());

        // Submit dependency, now job should execute
        optimisticExecutor.submit(dependencyJob);
        optimisticExecutor.awaitJobEmptiness(1, TimeUnit.SECONDS);

        assertTrue("Job should execute after dependency met", jobExecuted.get());
    }

    /**
     * Test non-optimistic submit strategy: all jobs go to queue.
     */
    @Test
    public void testNonOptimisticSubmit() throws InterruptedException {
        AtomicInteger queuedCount = new AtomicInteger(0);
        AtomicInteger executed = new AtomicInteger(0);

        Job<Void> job = new Job<>(() -> {
            executed.incrementAndGet();
            return null;
        });

        JobExecutor nonOptimisticExecutor = new JobExecutor(2, 2, false, executor);

        nonOptimisticExecutor.submit(job, false); // Non-optimistic
        nonOptimisticExecutor.awaitJobEmptiness(1, TimeUnit.SECONDS);

        assertEquals("Job should execute", 1, executed.get());
    }

    /**
     * Test submitAll with dependencies: respects optimistic strategy for varargs.
     */
    @Test
    public void testSubmitAllVarargs() throws InterruptedException {
        AtomicInteger executed = new AtomicInteger(0);

        Job<Void> job1 = new Job<>(() -> {
            executed.incrementAndGet();
            return null;
        });

        Job<Void> job2 = new Job<>(() -> {
            executed.incrementAndGet();
            return null;
        });

        JobExecutor optimisticExecutor = new JobExecutor(executor);
        optimisticExecutor.submitAll(job1, job2); // Uses optimistic strategy

        optimisticExecutor.awaitJobEmptiness(1, TimeUnit.SECONDS);
        assertEquals("Both jobs should execute", 2, executed.get());
    }

    /**
     * Test submitAll with collection: ignores optimistic strategy.
     */
    @Test
    public void testSubmitAllCollection() throws InterruptedException {
        AtomicInteger executed = new AtomicInteger(0);

        List<Job> jobs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Job<Void> job = new Job<>(() -> {
                executed.incrementAndGet();
                return null;
            });
            jobs.add(job);
        }

        JobExecutor nonOptimisticExecutor = new JobExecutor(2, 2, false, executor);
        nonOptimisticExecutor.submitAll(jobs); // Ignores optimistic=false, queues all

        nonOptimisticExecutor.awaitJobEmptiness(1, TimeUnit.SECONDS);
        assertEquals("All jobs should execute", 5, executed.get());
    }

    /**
     * Test optimistic submit with impossible dependency.
     * Job should be discarded immediately without queueing.
     */
    @Test
    public void testOptimisticSubmitImpossible() throws InterruptedException {
        AtomicBoolean jobDiscarded = new AtomicBoolean(false);
        AtomicBoolean jobExecuted = new AtomicBoolean(false);

        Job<Void> job = new Job<>(() -> {
            jobExecuted.set(true);
            return null;
        });

        job.addDependency(new Dependency() {
            @Override
            public boolean isCompleted(Job j) {
                return true;
            }

            @Override
            public boolean isPossible() {
                return false; // Impossible
            }
        });

        job.addListener(SystemJobEventName.ON_DISCARDED, (j, c, d) -> {
            jobDiscarded.set(true);
        });

        JobExecutor optimisticExecutor = new JobExecutor(executor);
        optimisticExecutor.submit(job, true); // Optimistic submit

        optimisticExecutor.awaitJobEmptiness(1, TimeUnit.SECONDS);

        assertFalse("Job should not execute", jobExecuted.get());
        assertTrue("Job should be discarded immediately", jobDiscarded.get());
    }

    /**
     * Test schedulerLogic extraction: verify it works in both submit and rescan paths.
     * Same logic should apply whether job goes optimistic or through queue.
     */
    @Test
    public void testSchedulerLogicConsistency() throws InterruptedException {
        AtomicInteger executedOptimistic = new AtomicInteger(0);
        AtomicInteger executedQueued = new AtomicInteger(0);

        Job<Void> jobOptimistic = new Job<>(() -> {
            executedOptimistic.incrementAndGet();
            return null;
        });

        Job<Void> jobQueued = new Job<>(() -> {
            executedQueued.incrementAndGet();
            return null;
        });

        JobExecutor executor1 = new JobExecutor(executor);
        JobExecutor executor2 = new JobExecutor(2, 2, false, executor);

        // Submit optimistically (uses schedulerLogic in submit path)
        executor1.submit(jobOptimistic, true);

        // Submit non-optimistically (uses schedulerLogic in rescan path)
        executor2.submit(jobQueued, false);

        executor1.awaitJobEmptiness(1, TimeUnit.SECONDS);
        executor2.awaitJobEmptiness(1, TimeUnit.SECONDS);

        assertEquals("Optimistic job executed", 1, executedOptimistic.get());
        assertEquals("Queued job executed", 1, executedQueued.get());
    }
}