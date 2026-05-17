package lt.lb.jobsystem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;
import lt.lb.fastid.FastID;
import lt.lb.fastid.FastIDGen;
import lt.lb.jobsystem.dependency.Dependency;
import lt.lb.jobsystem.events.JobEvent;
import lt.lb.jobsystem.events.JobEventListener;
import lt.lb.jobsystem.events.SystemJobDependency;
import lt.lb.jobsystem.events.SystemJobEvent;
import lt.lb.jobsystem.events.SystemJobEventName;

/**
 *
 * @author laim0nas100
 * @param <T>
 */
public class Job<T> implements RunnableFuture<T> {

    static FastIDGen idgen = new FastIDGen();

    protected Collection<Dependency> doBefore;
    protected Collection<Job> doAfter;
    public final String id;

    protected Map<String, Collection<JobEventListener>> listeners;
    protected EnumMap<SystemJobEventName, Collection<JobEventListener>> systemListeners = new EnumMap<>(SystemJobEventName.class);

    protected final AtomicInteger flags = new AtomicInteger(0);

    // Bit constants
    static final int EXCEPTIONAL = 1;
    static final int SUCCESSFUL = 1 << 1;
    static final int INTERRUPTED = 1 << 2;
    static final int EXCEPTIONAL_EVENT = 1 << 3;
    static final int EXECUTED = 1 << 4;
    static final int SCHEDULED = 1 << 5;
    static final int DISCARDED = 1 << 6;
    static final int REPEATED_DISCARD = 1 << 7;
    static final int RUNNING = 1 << 8;
    static final int CANCELLED = 1 << 9;
    static final int DONE = 1 << 10;

    static final int REMOVABLE_MASK
            = DISCARDED
            | SUCCESSFUL
            | CANCELLED
            | EXCEPTIONAL
            | INTERRUPTED
            | DONE;

    protected AtomicInteger failedToStart = new AtomicInteger(0);

    protected Job canceledParent;
    protected Job canceledRoot;

    protected final FutureTask<T> task;
    protected Thread jobThread;

    public static FastID getNextID() {
        return idgen.getAndIncrement();
    }

    /**
     *
     * @param id
     * @param call
     */
    public Job(String id, Consumer<? super Job<T>> call) {
        this.id = Objects.requireNonNull(id);
        task = new FutureTask<>(() -> call.accept(this), null);
    }

    /**
     *
     * @param id
     * @param call
     */
    public Job(String id, Function<? super Job<T>, ? extends T> call) {
        this.id = Objects.requireNonNull(id);
        task = new FutureTask<>(() -> call.apply(this));

    }

    /**
     *
     * @param call
     */
    public Job(Consumer<? super Job<T>> call) {
        this(Job.getNextID() + "-Job", call);
    }

    /**
     *
     * @param call
     */
    public Job(Function<? super Job<T>, ? extends T> call) {
        this(Job.getNextID() + "-Job", call);
    }

    /**
     *
     * @param id
     * @param call
     */
    public Job(String id, Callable<T> call) {
        this.id = Objects.requireNonNull(id);
        task = new FutureTask<>(call);
    }

    /**
     *
     * @param call
     */
    public Job(Callable<T> call) {
        this(Job.getNextID() + "-Job", call);
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
        hash = 67 * hash + id.hashCode();
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
            return other.id.equals(this.id);
        }
        return false;
    }

    /**
     *
     * @return ID string
     */
    public String getID() {
        return this.id;
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
     * @param interrupt whether to interrupt this and child tasks.
     * @param propogate whether to propagate to child tasks.
     * @return
     */
    public boolean cancel(boolean interrupt, boolean propogate) {
        return cancelInner(interrupt, propogate, this);
    }

    private boolean cancelInner(boolean interrupt, boolean propogate, Job root) {

        if (!trySetFlag(CANCELLED)) {
            return false;
        }
        boolean canceledOk = task.cancel(interrupt);
        this.fireSystemEvent(new SystemJobEvent(SystemJobEventName.ON_CANCEL, this));
        if (propogate && doAfter != null) {
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
     * @param interrupt whether to interrupt this and child tasks.
     * @return
     */
    @Override
    public boolean cancel(boolean interrupt) {
        return cancel(interrupt, true);
    }

    /**
     *
     * @return whether this task is ready to run (all dependencies are
     * satisfied)
     */
    public boolean canRun() {

        if (this.isDone()) {
            return false;
        }
        if (doBefore == null) {
            return true;
        }
        for (Dependency dep : this.doBefore) {
            if (!dep.isCompleted(this)) {
                return false;
            }
        }
        return true;
    }

    public boolean isPossibleToRun() {
        if (isRemovable()) {
            return false;
        }
        if (doBefore == null) {
            return true;
        }
        for (Dependency dep : this.doBefore) {
            if (!dep.isPossible()) {
                return false;
            }
        }
        return true;
    }

    protected boolean trySetFlag(int flag) {
        int current;
        int updated;

        do {
            current = flags.get();

            if ((current & flag) != 0) {
                return false; // already set
            }

            updated = current | flag;
            boolean ok = flags.compareAndSet(current, updated);
            if (ok) {
                return ok;
            }
            LockSupport.parkNanos(1);

        } while (true);
    }

    protected boolean tryClearFlag(int flag) {
        int current;
        int updated;

        do {
            current = flags.get();

            if ((current & flag) == 0) {
                return false; // already cleared
            }

            updated = current & ~flag;
            boolean ok = flags.compareAndSet(current, updated);
            if (ok) {
                return ok;
            }
            LockSupport.parkNanos(1);

        } while (true);
    }

    protected void setFlag(int flag) {
        setFlag(flag, true);
    }

    protected void setFlag(int flag, boolean repeated) {
        int current;
        int updated;

        do {
            current = flags.get();
            updated = current | flag;
            if (current == updated) {
                return;
            }
            boolean ok = flags.compareAndSet(current, updated);
            if (ok) {
                return;
            }
            LockSupport.parkNanos(1);// backoff

        } while (repeated);
    }

    protected void clearFlag(int flag) {
        clearFlag(flag, true);
    }

    protected void clearFlag(int flag, boolean repeated) {
        int current;
        int updated;

        do {
            current = flags.get();
            updated = current & ~flag;

            // already cleared
            if (current == updated) {
                return;
            }

            boolean ok = flags.compareAndSet(current, updated);
            if (ok) {
                return;
            }
            LockSupport.parkNanos(1);// backoff
        } while (repeated);
    }

    protected boolean hasFlag(int flag) {
        return (flags.get() & flag) != 0;
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_CANCEL}
     *
     * @return
     */
    @Override
    public boolean isCancelled() {
        return hasFlag(CANCELLED);
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_SUCCESSFUL}
     *
     * @return
     */
    public boolean isSuccessfull() {
        return hasFlag(SUCCESSFUL);
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_EXCEPTIONAL}
     *
     * @return
     */
    public boolean isExceptional() {
        return hasFlag(EXCEPTIONAL);
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_DISCARDED}
     *
     * @return
     */
    public boolean isDiscarded() {
        return hasFlag(DISCARDED);
    }

    /**
     *
     * @return whether this is currently running.
     */
    public boolean isRunning() {
        return hasFlag(RUNNING);
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_SCHEDULED}
     *
     * @return
     */
    public boolean isScheduled() {
        return hasFlag(SCHEDULED);
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_INTERRUPTED}
     *
     * @return
     */
    public boolean isInterrupted() {
        return hasFlag(INTERRUPTED);
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_ABORTED}
     *
     * @return
     */
    public boolean isAborted() {
        int state = flags.get();
        return (state & CANCELLED) != 0
                && (state & EXECUTED) == 0;
    }

    /**
     * Returns if isExecuted and done.
     *
     * @return
     */
    public boolean isAttempted() {
        int state = flags.get();
        return (state & EXECUTED) != 0
                && (state & DONE) != 0;
    }

    /**
     * Returns if discarded or done.
     *
     * @return
     */
    public boolean isRemovable() {
        return (flags.get() & REMOVABLE_MASK) != 0;
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
        return hasFlag(DONE);
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_EXECUTE}
     *
     * @return
     */
    public boolean isExecuted() {
        return hasFlag(EXECUTED);
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_EXCEPTIONAL_EVENT}
     *
     * @return
     */
    public boolean isExceptionalEvent() {
        return hasFlag(EXCEPTIONAL_EVENT);
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
        if (doBefore == null) {
            doBefore = new ArrayList<>();
        }
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
        if (doAfter == null) {
            doAfter = new ArrayList<>();
        }
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
        if (!canRun()) {
            failedToStart.incrementAndGet();
            fireSystemEvent(new SystemJobEvent(SystemJobEventName.ON_FAILED_TO_START, this));
            clearFlag(SCHEDULED);
            return;
        }
        if (trySetFlag(RUNNING)) { // ensure only one running instance
            setFlag(EXECUTED);
            jobThread = Thread.currentThread();
            fireSystemEvent(new SystemJobEvent(SystemJobEventName.ON_EXECUTE, this));

            try {
                runTask();
                task.get();
                setFlag(SUCCESSFUL);
                fireSystemEvent(new SystemJobEvent(SystemJobEventName.ON_SUCCESSFUL, this));
            } catch (InterruptedException e) {
                setFlag(INTERRUPTED);
                fireSystemEvent(new SystemJobEvent(SystemJobEventName.ON_INTERRUPTED, this));
            } catch (Throwable e) { // execution exception or cancellation exception
                setFlag(EXCEPTIONAL);
                fireSystemEvent(new SystemJobEvent(SystemJobEventName.ON_EXCEPTIONAL, this, e));
            }

            fireSystemEvent(new SystemJobEvent(SystemJobEventName.ON_ATTEMPTED, this));
            if (trySetFlag(DONE)) {
                fireSystemEvent(new SystemJobEvent(SystemJobEventName.ON_DONE, this));
            }

            if (!tryClearFlag(RUNNING)) {
                throw new IllegalStateException("After job:" + getID() + " ran, property running was set to false");
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
        if (listeners == null) {
            listeners = new HashMap<>();
        }
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
        if (isScheduled()) {
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
            if (listeners != null) {
                fireEvent(event, listeners.getOrDefault(event.getEventName(), null), false);
            }
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
     * @param ignore whether to ignore exceptions
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
                    setFlag(EXCEPTIONAL_EVENT);
                    if (onExcpetionalEvent != null) {
                        SystemJobEvent systemJobEvent = new SystemJobEvent(SystemJobEventName.ON_EXCEPTIONAL_EVENT, event.getCreator(), th);
                        fireEvent(systemJobEvent, onExcpetionalEvent, true);
                    }

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
