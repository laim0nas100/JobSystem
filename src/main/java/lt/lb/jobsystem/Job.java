package lt.lb.jobsystem;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lt.lb.jobsystem.events.JobEventListener;
import lt.lb.jobsystem.events.JobEvent;
import lt.lb.jobsystem.dependency.Dependency;
import lt.lb.jobsystem.events.SystemJobEvent;
import lt.lb.jobsystem.events.SystemJobEventName;
import lt.lb.jobsystem.events.SystemJobDependency;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 *
 * @author laim0nas100
 * @param <T>
 */
public class Job<T> implements RunnableFuture<T> {

    Collection<Dependency> doBefore = new HashSet<>();
    Collection<Job> doAfter = new HashSet<>();
    final String uuid;

    Map<String, Collection<JobEventListener>> listeners = new HashMap<>();
    EnumMap<SystemJobEventName, Collection<JobEventListener>> systemListeners = new EnumMap<>(SystemJobEventName.class);
    AtomicBoolean cancelled = new AtomicBoolean(false);
    volatile boolean exceptional = false;
    volatile boolean successfull = false;
    volatile boolean interupted = false;
    volatile boolean exceptionalEvent = false;
    volatile boolean executed = false;
    AtomicInteger failedToStart = new AtomicInteger(0);
    AtomicBoolean scheduled = new AtomicBoolean(false);
    AtomicBoolean discarded = new AtomicBoolean(false);
    AtomicBoolean repeatedDiscard = new AtomicBoolean(false);
    AtomicBoolean running = new AtomicBoolean(false);

    Job canceledParent;
    Job canceledRoot;

    protected final FutureTask<T> task;
    Thread jobThread;

    /**
     *
     * @param uuid
     * @param call
     */
    public Job(String uuid, Consumer<? super Job<T>> call) {
        this.uuid = Objects.requireNonNull(uuid);
        task = new FutureTask<>(() -> call.accept(this), null);
    }

    /**
     *
     * @param uuid
     * @param call
     */
    public Job(String uuid, Function<? super Job<T>, ? extends T> call) {
        this.uuid = Objects.requireNonNull(uuid);
        task = new FutureTask<>(() -> call.apply(this));

    }

    /**
     *
     * @param call
     */
    public Job(Consumer<? super Job<T>> call) {
        this(UUID.randomUUID().toString(), call);
    }

    /**
     *
     * @param call
     */
    public Job(Function<? super Job<T>, ? extends T> call) {
        this(UUID.randomUUID().toString(), call);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T get() throws InterruptedException, ExecutionException {
        return task.get();
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public T get(long time, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return task.get(time, unit);
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        int hash = 3;
        hash = 67 * hash + uuid.hashCode();
        return hash;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof Job) {
            Job other = (Job) o;
            return other.uuid.equals(this.uuid);
        }
        return false;
    }

    /**
     *
     * @return UUID string
     */
    public String getUUID() {
        return this.uuid;
    }

    /**
     *
     * @return thread that this job executed on.
     */
    public Optional<Thread> getJobThread() {
        return Optional.ofNullable(this.jobThread);
    }

    /**
     * Cancel while interrupting and propagating to all child tasks that. Can
     * cancel even if task is done.
     */
    public void cancel() {
        this.cancel(true, true);
    }

    /**
     * Cancel while interrupting and propagating to all child tasks that. Can
     * cancel even if task is done.
     *
     * @param interrupt wether to interrupt this and child tasks.
     * @param propogate wether to propagate to child tasks.
     * @return
     */
    public boolean cancel(boolean interrupt, boolean propogate) {
        return cancelInner(interrupt, propogate, this);
    }

    private boolean cancelInner(boolean interrupt, boolean propogate, Job root) {

        if (!cancelled.compareAndSet(false, true)) {
            return false;
        }

        boolean canceledOk = task.cancel(interrupt);
        this.fireSystemEvent(new SystemJobEvent(SystemJobEventName.ON_CANCEL, this));
        if (propogate) {
            for (Job j : this.doAfter) {
                j.canceledRoot = root;
                j.canceledParent = this;
                j.cancelInner(interrupt, propogate, root);

            }
        }
        return canceledOk;
    }

    /**
     * Cancel while interrupting and propagating to all child tasks that. Can
     * cancel even if task is done.
     *
     * @param interrupt wether to interrupt this and child tasks.
     * @return
     */
    @Override
    public boolean cancel(boolean interrupt) {
        return cancel(interrupt, true);
    }

    /**
     *
     * @return wether this task is ready to run (all dependencies are satisfied)
     */
    public boolean canRun() {

        if (this.isDone()) {
            return false;
        }
        for (Dependency dep : this.doBefore) {
            if (!dep.isCompleted(this)) {
                return false;
            }
        }
        return true;
    }

    public boolean isPossibleToRun() {
        if (this.isDiscardedOrDone()) {
            return false;
        }
        for (Dependency dep : this.doBefore) {
            if (!dep.isPossible()) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_CANCEL}
     *
     * @return
     */
    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_SUCCESSFUL}
     *
     * @return
     */
    public boolean isSuccessfull() {
        return successfull;
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_EXCEPTIONAL}
     *
     * @return
     */
    public boolean isExceptional() {
        return exceptional;
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_DISCARDED}
     *
     * @return
     */
    public boolean isDiscarded() {
        return discarded.get();
    }

    /**
     *
     * @return wether this is currently running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_SCHEDULED}
     *
     * @return
     */
    public boolean isScheduled() {
        return scheduled.get();
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_INTERRUPTED}
     *
     * @return
     */
    public boolean isInterrupted() {
        return interupted;
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_ABORTED}
     *
     * @return
     */
    public boolean isAborted() {
        return isCancelled() && !isExecuted();
    }

    /**
     * Returns if discarded or done.
     *
     * @return
     */
    public boolean isDiscardedOrDone() {
        return isDiscarded() || isDone();
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_FAILED_TO_START}
     *
     * @return
     */
    public int getFailedToStart() {
        return this.failedToStart.get();
    }

    /**
     *
     * @return List of canceled jobs in order (on branching path) that starts
     * from the root cause.
     */
    public List<Job> getCanceledChain() {

        LinkedList<Job> chain = new LinkedList<>();

        Job me = this;
        while (true) {
            if (me != null && me.isCancelled()) {
                chain.addLast(me);
                me = me.canceledParent;

            } else {
                return chain;
            }
        }

    }

    /**
     *
     * @return
     */
    public Optional<Job> getCanceledRoot() {
        return Optional.ofNullable(this.canceledRoot);
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_DONE}
     *
     * @return
     */
    @Override
    public boolean isDone() {
        return (isCancelled() || isExceptional() || isSuccessfull());
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_EXECUTE}
     *
     * @return
     */
    public boolean isExecuted() {
        return executed;
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_EXCEPTIONAL_EVENT}
     *
     * @return
     */
    public boolean isExceptionalEvent() {
        return exceptionalEvent;
    }

    /**
     * Chain jobs on given event type. Given job must execute before this.
     *
     * @param onEvent
     * @param job
     * @return
     */
    public Job chainBackward(SystemJobEventName onEvent, Job job) {
        job.addAfter(this);
        this.addDependency(new SystemJobDependency(job, onEvent));
        return this;
    }

    /**
     * Chain jobs on given event type. Given job must execute after this.
     *
     * @param onEvent
     * @param job
     * @return
     */
    public Job chainForward(SystemJobEventName onEvent, Job job) {
        this.addAfter(job);
        job.addDependency(new SystemJobDependency(this, onEvent));
        return this;
    }

    /**
     * Chain jobs on success. Given job must execute before this.
     *
     * @param job
     * @return
     */
    public Job chainBackward(Job job) {
        return this.chainBackward(SystemJobEventName.ON_SUCCESSFUL, job);
    }

    /**
     * Chain jobs on success. Given job must execute after this.
     *
     * @param job
     * @return
     */
    public Job chainForward(Job job) {
        return this.chainForward(SystemJobEventName.ON_SUCCESSFUL, job);
    }

    /**
     * Add dependency manually
     *
     * @param dep
     * @return
     */
    public Job addDependency(Dependency dep) {
        assertNoChange("dependencies");
        this.doBefore.add(dep);
        return this;
    }

    /**
     * Add job, that can be canceled via propagation, if this job were to be
     * canceled.
     *
     * @param dep
     * @return
     */
    public Job addAfter(Job dep) {
        assertNoChange("child jobs");
        this.doAfter.add(dep);
        return this;
    }

    /**
     * Run job manually
     */
    @Override
    public void run() {
        if (isExecuted()) {
            return;
        }
        if (!this.canRun()) {
            this.failedToStart.incrementAndGet();
            this.fireSystemEvent(new SystemJobEvent(SystemJobEventName.ON_FAILED_TO_START, this));
            this.scheduled.set(false);
            return;
        }
        if (this.running.compareAndSet(false, true)) { // ensure only one running instance
            this.executed = true;
            this.jobThread = Thread.currentThread();
            this.fireSystemEvent(new SystemJobEvent(SystemJobEventName.ON_EXECUTE, this));
            runTask();
            ExecutionException ex = null;
            try {
                task.get();
                this.successfull = true;
            } catch (ExecutionException e) {
                this.exceptional = true;
                ex = e;

            } catch (InterruptedException e) {
                this.interupted = true;
            }

            if (isSuccessfull()) {
                this.fireSystemEvent(new SystemJobEvent(SystemJobEventName.ON_SUCCESSFUL, this));
            }
            if (isExceptional()) {
                this.fireSystemEvent(new SystemJobEvent(SystemJobEventName.ON_EXCEPTIONAL, this, ex));
            }

            if (isInterrupted()) {
                this.fireSystemEvent(new SystemJobEvent(SystemJobEventName.ON_INTERRUPTED, this));
            }
            this.fireSystemEvent(new SystemJobEvent(SystemJobEventName.ON_DONE, this));

            if (!this.running.compareAndSet(true, false)) {
                throw new IllegalStateException("After job:" + this.getUUID() + " ran, property running was set to false");
            }

        }

    }

    /**
     * You can overwrite to run actual task on a different executor, override
     * with caution.
     */
    protected void runTask() {
        task.run();
    }

    /**
     * Add custom job listener
     *
     * @param name
     * @param listener
     */
    public void addListener(String name, JobEventListener listener) {
        assertNoChange("listeners");
        listeners.computeIfAbsent(name, n -> new LinkedList<>()).add(listener);
    }

    /**
     * Add system job listener
     *
     * @param name
     * @param listener
     */
    public void addListener(SystemJobEventName name, JobEventListener listener) {
        assertNoChange("systemListeners");
        systemListeners.computeIfAbsent(name, n -> new LinkedList<>()).add(listener);
    }

    private void assertNoChange(String msg) {
        if (this.isScheduled()) {
            throw new IllegalStateException("Job has been scheduled, " + msg + " should not change");
        }
    }

    /**
     *
     * @param event
     */
    public void fireEvent(JobEvent event) {
        Objects.requireNonNull(event);
        if (event instanceof SystemJobEvent) {
            fireSystemEvent((SystemJobEvent) event);
        } else {
            fireEvent(event, listeners.getOrDefault(event.getEventName(), null), false);
        }

    }

    /**
     *
     * @param event SystemEvent
     */
    public void fireSystemEvent(SystemJobEvent event) {

        Objects.requireNonNull(event);
        fireEvent(event, systemListeners.getOrDefault(event.enumName, null), false);
    }

    /**
     *
     * @param event event
     * @param collection listeners to trigger
     * @param ignore wether to ignore exceptions
     */
    protected void fireEvent(JobEvent event, Collection<JobEventListener> collection, boolean ignore) {
        if (collection == null) {
            return;
        }
        for (JobEventListener listener : collection) {
            try {
                listener.onEvent(event);
            } catch (Throwable th) {
                if (!ignore) {
                    Collection<JobEventListener> onExcpetionalEvent = systemListeners.getOrDefault(SystemJobEventName.ON_EXCEPTIONAL_EVENT, null);
                    exceptionalEvent = true;
                    SystemJobEvent systemJobEvent = new SystemJobEvent(SystemJobEventName.ON_EXCEPTIONAL_EVENT, event.getCreator(), th);
                    fireEvent(systemJobEvent, onExcpetionalEvent, true);
                }
            }
        }
    }

    /**
     *
     * @return
     */
    public Runnable asRunnable() {
        return this::run;
    }
}
