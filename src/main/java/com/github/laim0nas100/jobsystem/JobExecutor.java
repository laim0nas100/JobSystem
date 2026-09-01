package com.github.laim0nas100.jobsystem;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import com.github.laim0nas100.jobsystem.events.JobEventListener;
import com.github.laim0nas100.jobsystem.events.SystemJobEventName;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Job executor with provided base executor. No cleanup is necessary. Job
 * scheduling uses same provided executor (usually the same work thread after
 * job was finished).
 *
 * @author laim0nas100
 */
public class JobExecutor {

    protected Executor exe;

    protected boolean isShutdown = false;
    protected Collection<Job> jobs = new ConcurrentLinkedDeque<>();
    protected AtomicInteger scheduledJobs = new AtomicInteger(0);

    //referencable listeners that you can remove from default listener map
    protected JobEventListener rescanJobs = (j, c, d) -> addScanRequest();
    protected JobEventListener reinsertJob = (j, c, d) -> jobs.add(j);
    protected JobEventListener incrementScheduledJobs = (j, c, d) -> scheduledJobs.incrementAndGet();
    protected JobEventListener decrementScheduledJobs = (j, c, d) -> scheduledJobs.decrementAndGet();

    protected final Map<Serializable, List<JobEventListener>> jobExecutorProvidedListeners;

    protected final ReentrantLock lock = new ReentrantLock();
    protected final Condition waiter = lock.newCondition();

    protected final AtomicInteger scanRequest = new AtomicInteger(0);
    protected final AtomicInteger inScan = new AtomicInteger(0);
    protected final int rescanRequestThrottle;
    protected final int rescanThrottle;
    protected final boolean optimisticSubmit;

    /**
     *
     * @param exe Main executor
     */
    public JobExecutor(Executor exe) {
        this(2, 2, true, exe);
    }

    /**
     * @param requestThrottle how many re-scan requests can queue up
     * @param rescanThrottle how many concurrent re-scan jobs can be happening
     * (2 at least)
     * @param optimisticSubmit optimistic submit strategy (try to pass the job
     * straight to the main executor if all dependencies ok)
     * @param exe Main executor
     */
    public JobExecutor(int requestThrottle, int rescanThrottle, boolean optimisticSubmit, Executor exe) {
        this.rescanRequestThrottle = Math.max(2, requestThrottle);
        this.rescanThrottle = Math.max(2, rescanThrottle);
        this.optimisticSubmit = optimisticSubmit;
        this.exe = Objects.requireNonNull(exe);
        this.jobExecutorProvidedListeners = defaultListenerMap();
    }

    protected Map<Serializable, List<JobEventListener>> defaultListenerMap() {
        Map<Serializable, List<JobEventListener>> map = new HashMap<>();
        List<JobEventListener> listFailedStart = new ArrayList<>(3);
        listFailedStart.add(reinsertJob);
        listFailedStart.add(decrementScheduledJobs);
        listFailedStart.add(rescanJobs);

        List<JobEventListener> listDone = new ArrayList<>(1);
        listDone.add(rescanJobs);

        List<JobEventListener> listScheduled = new ArrayList<>(1);
        listScheduled.add(incrementScheduledJobs);

        List<JobEventListener> listAttempted = new ArrayList<>(1);
        listAttempted.add(decrementScheduledJobs);

        map.put(SystemJobEventName.ON_FAILED_TO_START, listFailedStart);
        map.put(SystemJobEventName.ON_DONE, listDone);
        map.put(SystemJobEventName.ON_SCHEDULED, listScheduled);
        map.put(SystemJobEventName.ON_ATTEMPTED, listAttempted);
        return map;
    }

    protected boolean scheduleOrQueue(Job job) {
        return schedulerLogic(job, null, true);
    }

    protected boolean schedulerLogic(Job job, Iterator iterator, boolean insert) {
        int flags = job.state.getFlags();
        if (JobState.hasFlag(flags, JobState.RUNNING)) { // could be running or failing to start and flag not yet cleared
            return false;
        }
        // not running, scheduled and not executed yet but was not yet removed by another thread?
        if (JobState.hasFlag(flags, JobState.SCHEDULED) && !JobState.hasFlag(flags, JobState.EXECUTED)) {
            return false;
        }

        if (JobState.isRemovable(flags) || !job.allDependenciesPossible()) {
            if (job.state.trySetFlag(JobState.DISCARDED)) {
                if (iterator != null) {
                    iterator.remove();
                }
                job.fireSystemEvent(SystemJobEventName.ON_DISCARDED);
            } else { // job was already discarded but reinserted so don't fire event again
                if (job.state.trySetFlag(JobState.REPEATED_DISCARD)) { // thread safety
                    if (iterator != null) {
                        iterator.remove();
                    }
                    job.state.clearFlag(JobState.REPEATED_DISCARD);
                }
            }
            if (job.state.trySetFlag(JobState.DONE)) {
                job.fireSystemEvent(SystemJobEventName.ON_DONE);
            }
            return false;
        } else if (!JobState.hasFlag(flags, JobState.SCHEDULED) && job.allDependenciesCompleted()) {
            if (job.state.trySetFlag(JobState.SCHEDULED)) {
                if (iterator != null) {
                    iterator.remove();
                }
                job.fireSystemEvent(SystemJobEventName.ON_SCHEDULED);
                try {
                    //we dont control executor, so just in case it is bad or in shutdown
                    exe.execute(job);
                } catch (Throwable t) {
                    job.run(); // run some straggling jobs in the same thread
                }
                return false;
            }
        }
        if (insert) {
            jobs.add(job);
        }
        return insert;
    }

    /**
     * Submits the job using the default optimistic strategy.
     *
     * @param job
     */
    public void submit(Job job) {
        submit(job, optimisticSubmit);
    }

    /**
     * Submits the job overriding the default optimistic strategy.
     *
     * @param job
     */
    public void submit(Job job, boolean optimistic) {
        if (isShutdown) {
            throw new IllegalStateException("Shutdown was called");
        }
        job.executorSubmission(this);
        if (!optimistic) {
            jobs.add(job);
            addScanRequest();
        } else if (scheduleOrQueue(job)) {
            addScanRequest();
        }
    }

    /**
     * Submits all jobs. Appends all to the collection and adds a scan request.
     * Ignores optimistic submit strategy.
     *
     * @param collection
     */
    public void submitAll(Collection<? extends Job> collection) {
        if (isShutdown) {
            throw new IllegalStateException("Shutdown was called");
        }
        for (Job job : collection) {
            job.executorSubmission(this);
        }
        jobs.addAll(collection);
        addScanRequest();
    }

    /**
     * Submits all jobs using the default optimistic strategy.
     *
     * @param jobArray
     */
    public void submitAll(Job... jobArray) {
        if (isShutdown) {
            throw new IllegalStateException("Shutdown was called");
        }
        for (Job job : jobArray) {
            job.executorSubmission(this);
            if (!optimisticSubmit) {
                jobs.add(job);
            } else {
                scheduleOrQueue(job);
            }
        }
        addScanRequest();
    }

    public Map<Serializable, List<JobEventListener>> getExecutorJobListeners() {
        return jobExecutorProvidedListeners;
    }

    protected void addScanRequest() {
        for (;;) {
            int get = scanRequest.get();
            if (get > rescanRequestThrottle) {
                return; // don't even try, enough conjestion
            }
            if (scanRequest.compareAndSet(get, get + 1)) {
                try {
                    exe.execute(this::rescanJobsByRequest);
                    //could be in shutdown, su just manually rescan the jobs in the same thread
                } catch (Throwable ex) {
                    rescanJobsByRequest();
                }
                return;
            } else {
                LockSupport.parkNanos(1);
            }
        }
    }

    /**
     * Add a re-scan request. Schedule ready jobs and discard removable. If no
     * more jobs are left, completes emptiness waiter.
     *
     * Doesn't run automatically. If you are using special dependencies, for
     * example "run only if current day is Christmas", it will not check every
     * day. It's the responsibility of the user to re-scan periodically if such
     * dependencies are used.
     */
    public void rescanJobs() {
        addScanRequest();
    }

    protected void rescanJobsByRequest() {
        rescanJobsLogic(true);
    }

    /**
     * Re-scan implementation.
     *
     * @param byRequest wether to respect the throttle and decrement scanRequest
     * counter
     */
    protected void rescanJobsLogic(boolean byRequest) {
        int scanning = inScan.incrementAndGet();

        try {
            if (byRequest) {
                int request = scanRequest.decrementAndGet();
                if (scanning > rescanThrottle && request > 1) {
                    return;
                }
                LockSupport.parkNanos(1L << Math.min(scanning, 20)); // reduce congestion
            }

            Iterator<Job> iterator = jobs.iterator();
            while (iterator.hasNext()) {
                Job job = iterator.next();
                if (job != null) {
                    schedulerLogic(job, iterator, false);
                }

            }
        } finally {
            scanning = inScan.decrementAndGet();
        }

        if (scanning == 0) {
            if ((!byRequest || scanRequest.get() == 0) && isEmpty()) {
                try {
                    lock.lock();
                    waiter.signalAll();
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    /**
     *
     * @return true if no more jobs left.
     */
    public boolean isEmpty() {
        if (scheduledJobs.get() > 0) {
            return false;
        }
        for (Job j : jobs) {
            if (j != null) {
                return false;
            }
        }
        return true;

    }

    /**
     *
     * @return Current pending Job stream.
     */
    public Stream<Job> getJobStream() {
        return jobs.stream();
    }

    /**
     * Only prevents new jobs from being submitted and completes termination
     * waiter.
     */
    public void shutdown() {
        isShutdown = true;
        rescanJobs();
    }

    /**
     * Wait a given time for job list to be empty
     *
     * @param time
     * @param unit
     * @return
     * @throws InterruptedException
     */
    public boolean awaitJobEmptiness(long time, TimeUnit unit)
            throws InterruptedException {
        if (isEmpty()) {
            return true;
        }
        try {
            lock.lock();

            if (isEmpty()) {
                return true;
            }
            addScanRequest();
            return waiter.await(time, unit);
        } finally {
            lock.unlock();
        }
    }

    /**
     * If shutdown was fired, then wait for job list to be empty.
     *
     * @param time
     * @param unit
     * @return
     * @throws InterruptedException
     * @throws IllegalStateException if shutdown was not called
     */
    public boolean awaitTermination(long time, TimeUnit unit)
            throws InterruptedException, IllegalStateException {
        if (!isShutdown) {
            throw new IllegalStateException("Shutdown was not called");
        }
        return awaitJobEmptiness(time, unit);
    }

    /**
     * Calls shutdown and waits for executor to finish. Should be go-to method
     * for closing.
     *
     * @param time
     * @param unit
     * @return
     * @throws InterruptedException
     */
    public boolean shutdownAndWait(long time, TimeUnit unit) throws InterruptedException {
        shutdown();
        return awaitTermination(time, unit);
    }

}
