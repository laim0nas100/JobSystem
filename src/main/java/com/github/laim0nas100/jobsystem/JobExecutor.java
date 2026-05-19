package com.github.laim0nas100.jobsystem;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static com.github.laim0nas100.jobsystem.Job.DONE;
import com.github.laim0nas100.jobsystem.events.JobEventListener;
import com.github.laim0nas100.jobsystem.events.SystemJobEventName;

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
    
    protected JobEventListener rescanJobs = (j, c, d) -> addScanRequest();
    protected final Map<Serializable, List<JobEventListener>> jobExecutorProvidedListeners;
    
    protected final CompletableFuture[] awaitJobEmpty = new CompletableFuture[2];
    protected final AtomicBoolean jobEmptyFlip = new AtomicBoolean(false);
    protected final AtomicInteger scanRequest = new AtomicInteger(0);
    protected final AtomicInteger inScan = new AtomicInteger(0);
    protected final int rescanThrottle;

    /**
     *
     * @param exe Main executor
     */
    public JobExecutor(Executor exe) {
        this(2, exe);
    }

    /**
     *
     * @param rescanThrottle how many concurrent rescan jobs can be happening (2
     * by default)
     * @param exe Main executor
     */
    public JobExecutor(int rescanThrottle, Executor exe) {
        this.exe = exe;
        this.rescanThrottle = Math.max(1, rescanThrottle);
        this.jobExecutorProvidedListeners = defaultListenerMap();
        for (int i = 0; i < awaitJobEmpty.length; i++) {
            awaitJobEmpty[i] = new CompletableFuture();
        }
        
    }
    
    protected Map<Serializable, List<JobEventListener>> defaultListenerMap() {
        Map<Serializable, List<JobEventListener>> map = new HashMap<>();
        List<JobEventListener> listFailed = new ArrayList<>(1);
        listFailed.add(rescanJobs);
        List<JobEventListener> listDone = new ArrayList<>(1);
        listDone.add(rescanJobs);
        
        map.put(SystemJobEventName.ON_FAILED_TO_START, listFailed);
        map.put(SystemJobEventName.ON_DONE, listDone);
        return map;
    }

    /**
     * Add job in job list. Does not become scheduled instantly.
     *
     * @param job
     */
    public void submit(Job job) {
        if (isShutdown) {
            throw new IllegalStateException("Shutdown was called");
        }
        job.executorSubmission(this);
        jobs.add(job);
        addScanRequest();
    }

    /**
     * Submits all jobs
     *
     * @param iter
     */
    public void submitAll(Iterable<? extends Job> iter) {
        if (isShutdown) {
            throw new IllegalStateException("Shutdown was called");
        }
        for (Job job : iter) {
            job.executorSubmission(this);
            jobs.add(job);
        }
        addScanRequest();
    }

    /**
     * Submits all jobs
     *
     * @param jobArray
     */
    public void submitAll(Job... jobArray) {
        if (isShutdown) {
            throw new IllegalStateException("Shutdown was called");
        }
        for (Job job : jobArray) {
            job.executorSubmission(this);
            jobs.add(job);
        }
        addScanRequest();
    }
    
    public Map<Serializable, List<JobEventListener>> getExecutorJobListeners() {
        return jobExecutorProvidedListeners;
    }
    
    protected void addScanRequest() {
        if (scanRequest.incrementAndGet() <= rescanThrottle) {
            exe.execute(this::rescanJobsIter);
        } else {
            scanRequest.decrementAndGet();
        }
        
    }

    /**
     * Rescans after a job becomes done or a new job is submitted. Schedule
     * ready jobs and discard discardable. If no more jobs are left, completes
     * emptiness waiter.
     *
     * Doesn't run automatically. If you are using special dependencies, for
     * example "run only if current day is Christmas", it will not check every
     * day. It's the responsibility of the user to rescan periodically if such
     * dependencies are used.
     */
    public void rescanJobs() {
        addScanRequest();
    }
    
    private void rescanJobsIter() {
        scanRequest.decrementAndGet();
        inScan.incrementAndGet();
        try {
            Iterator<Job> iterator = jobs.iterator();
            while (iterator.hasNext()) {
                Job job = iterator.next();
                if (job == null) {
                    continue;
                }
                if (!job.isPossibleToRun()) {
                    if (job.trySetFlag(Job.DISCARDED)) {
                        iterator.remove();
                        job.fireSystemEvent(SystemJobEventName.ON_DISCARDED);
                    } else { // job was allready discarded but reinserted so don't fire event again
                        if (job.trySetFlag(Job.REPEATED_DISCARD)) { // thread safety
                            iterator.remove();
                            job.clearFlag(Job.REPEATED_DISCARD);
                        }
                    }
                    if (job.isAborted()) {// cancelled and not executed
                        job.fireSystemEvent(SystemJobEventName.ON_ABORTED);
                    }
                    if (job.trySetFlag(DONE)) {
                        job.fireSystemEvent(SystemJobEventName.ON_DONE);
                    }
                } else if (!job.isExecuted() && !job.isScheduled() && job.canRun()) {
                    if (job.trySetFlag(Job.SCHEDULED)) {
                        job.fireSystemEvent(SystemJobEventName.ON_SCHEDULED);
                        try {
                            //we dont control executor, so just in case it is bad
                            exe.execute(job);
                        } catch (Throwable t) {
                        }
                    }
                }
            }
        } finally {
            inScan.decrementAndGet();
            
        }
        
        if (scanRequest.get() == 0 && inScan.get() == 0 && isEmpty()) {
            boolean flip = jobEmptyFlip.get();
            jobEmptyFlip.set(!flip);
            int i = flip ? 0 : 1;
            awaitJobEmpty[i].complete(null);
            awaitJobEmpty[i] = new CompletableFuture();
        }
        
    }

    /**
     *
     * @return true if no more jobs left.
     */
    public boolean isEmpty() {
        for (Job j : jobs) {
            if (j != null) {
                return false;
            }
        }
        return true;
        
    }

    /**
     *
     * @return Current non-discarded Job stream.
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
            this.awaitJobEmpty[jobEmptyFlip.get() ? 0 : 1].get(time, unit);
        } catch (ExecutionException | TimeoutException ex) {
            return false;
        }
        return true;
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
