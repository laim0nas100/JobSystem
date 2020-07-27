package lt.lb.jobsystem;

import java.util.Collection;
import lt.lb.jobsystem.events.JobEventListener;
import lt.lb.jobsystem.events.SystemJobEvent;
import lt.lb.jobsystem.events.SystemJobEventName;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

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

    protected JobEventListener rescanJobs = l -> addScanRequest();

    protected volatile CompletableFuture awaitJobEmpty = new CompletableFuture();
    protected AtomicInteger rrc;
    protected final int rescanThreshold;
    protected AtomicBoolean rescanDept = new AtomicBoolean(false);

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
        this.rescanThreshold = Math.max(1, rescanThrottle);
        this.rrc = new AtomicInteger(rescanThreshold);

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
        job.addListener(SystemJobEventName.ON_DONE, rescanJobs);
        job.addListener(SystemJobEventName.ON_FAILED_TO_START, rescanJobs);
        jobs.add(job);

        addScanRequest();
    }

    /**
     * Submits all jobs
     *
     * @param jobs
     */
    public void submitAll(Iterable<Job> jobs) {
        jobs.forEach(this::submit);
    }

    /**
     * Submits all jobs
     *
     * @param jobs
     */
    public void submitAll(Job... jobs) {
        for (Job job : jobs) {
            this.submit(job);
        }
    }

    protected void addScanRequest() {
        if (rrc.getAndDecrement() > 0) {
            exe.execute(() -> rescanJobs0());
        } else {
            rrc.incrementAndGet();
            rescanDept.set(true);
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
        this.addScanRequest();
    }

    private void rescanJobs0() {
        Iterator<Job> iterator = jobs.iterator();
        while (iterator.hasNext()) {
            Job job = iterator.next();
            if (job == null) {
                continue;
            }
            if (job.isDone()) {
                if (job.discarded.compareAndSet(false, true)) {
                    iterator.remove();
                    job.fireSystemEvent(new SystemJobEvent(SystemJobEventName.ON_DISCARDED, job));

                }
            } else if (!job.isExecuted() && !job.isScheduled() && job.canRun()) {
                if (job.scheduled.compareAndSet(false, true)) {
                    job.fireSystemEvent(new SystemJobEvent(SystemJobEventName.ON_SCHEDULED, job));
                    try {
                        //we dont control executor, so just in case it is bad
                        exe.execute(job);
                    } catch (Throwable t) {
                    }
                }
            }
        }
        if (isEmpty()) {
            this.awaitJobEmpty.complete(null);
            this.awaitJobEmpty = new CompletableFuture();
        }
        if (rescanThreshold <= rrc.incrementAndGet()) {
            if (rescanDept.compareAndSet(true, false)) {
                addScanRequest();
            }
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
        this.isShutdown = true;
        this.rescanJobs();
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
            this.awaitJobEmpty.get(time, unit);
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

}
