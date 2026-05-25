package com.github.laim0nas100.jobsystem;

import com.github.laim0nas100.fastid.FastID;
import com.github.laim0nas100.fastid.FastIDGen;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

import com.github.laim0nas100.jobsystem.dependency.Dependency;
import com.github.laim0nas100.jobsystem.events.EventListeners;
import com.github.laim0nas100.jobsystem.events.JobEventListener;
import com.github.laim0nas100.jobsystem.events.SystemJobDependency;
import com.github.laim0nas100.jobsystem.events.SystemJobEventName;

/**
 *
 * @author laim0nas100
 * @param <T>
 */
public class Job<T> implements RunnableFuture<T> {

    static FastIDGen idgen = new FastIDGen();

    protected List<Dependency> doBefore;
    protected List<Job> doAfter;
    public final Serializable id;

    protected EventListeners listeners = new EventListeners();
    
    protected JobState state = new JobState();

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
    public Job(Serializable id, Consumer<? super Job<T>> call) {
        this.id = Objects.requireNonNull(id);
        task = new FutureTask<>(() -> call.accept(this), null);
    }

    /**
     *
     * @param id
     * @param call
     */
    public Job(Serializable id, Function<? super Job<T>, ? extends T> call) {
        this.id = Objects.requireNonNull(id);
        task = new FutureTask<>(() -> call.apply(this));

    }

    /**
     *
     * @param call
     */
    public Job(Consumer<? super Job<T>> call) {
        this(Job.getNextID(), call);
    }

    /**
     *
     * @param call
     */
    public Job(Function<? super Job<T>, ? extends T> call) {
        this(Job.getNextID(), call);
    }

    /**
     *
     * @param id
     * @param call
     */
    public Job(Serializable id, Callable<T> call) {
        this.id = Objects.requireNonNull(id);
        task = new FutureTask<>(call);
    }

    /**
     *
     * @param call
     */
    public Job(Callable<T> call) {
        this(Job.getNextID(), call);
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
     * @return ID
     */
    public Serializable getID() {
        return this.id;
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

    protected boolean cancelInner(boolean interrupt, boolean propogate, Job root) {

        if (state.trySetFlag(JobState.CANCELLED)) {
            boolean canceledOk = task.cancel(interrupt);
            fireSystemEvent(SystemJobEventName.ON_CANCEL);
            if (propogate && doAfter != null) {
                for (Job j : this.doAfter) {
                    j.canceledRoot = root;
                    j.canceledParent = this;
                    j.cancelInner(interrupt, propogate, root);
                }
            }
            return canceledOk;
        }

        return false;
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

    /**
     *
     * @return whether this task is even possible to run (all dependencies are
     * possible and is not removable)
     */
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

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_CANCEL}
     *
     * @return
     */
    @Override
    public boolean isCancelled() {
        return state.hasFlag(JobState.CANCELLED);
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_SUCCESSFUL}
     *
     * @return
     */
    public boolean isSuccessfull() {
        return state.hasFlag(JobState.SUCCESSFUL);
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_EXCEPTIONAL}
     *
     * @return
     */
    public boolean isExceptional() {
        return state.hasFlag(JobState.EXCEPTIONAL);
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_DISCARDED}
     *
     * @return
     */
    public boolean isDiscarded() {
        return state.hasFlag(JobState.DISCARDED);
    }

    /**
     *
     * @return whether this is currently running.
     */
    public boolean isRunning() {
        return state.hasFlag(JobState.RUNNING);
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_SCHEDULED}
     *
     * @return
     */
    public boolean isScheduled() {
        return state.hasFlag(JobState.SCHEDULED);
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_INTERRUPTED}
     *
     * @return
     */
    public boolean isInterrupted() {
        return state.hasFlag(JobState.INTERRUPTED);
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_ABORTED}
     *
     * @return
     */
    public boolean isAborted() {
        return state.isAborted();
    }

    /**
     * Returns if isExecuted and done.
     *
     * @return
     */
    public boolean isAttempted() {
        return state.isAttempted();
    }

    /**
     * Returns if discarded or done.
     *
     * @return
     */
    public boolean isRemovable() {
        return state.isRemovable();
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_FAILED_TO_START}
     *
     * @return
     */
    public int getFailedToStart() {
        return state.getFailedToStart();
    }

    /**
     *
     * @return List of canceled jobs in order (on branching path) that starts
     * from the root cause.
     */
    public List<Job> getCanceledChain() {

        ArrayList<Job> chain = new ArrayList<>();

        Job me = this;
        while (true) {
            if (me != null && me.isCancelled()) {
                chain.add(me);
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
        return state.hasFlag(JobState.DONE);
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_EXECUTE}
     *
     * @return
     */
    public boolean isExecuted() {
        return state.hasFlag(JobState.EXECUTED);
    }

    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_EXCEPTIONAL_EVENT}
     *
     * @return
     */
    public boolean isExceptionalEvent() {
        return state.hasFlag(JobState.EXCEPTIONAL_EVENT);
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
     * Chain jobs on success. Given job must successfully execute before this.
     *
     * @param job
     * @return
     */
    public Job chainBackward(Job job) {
        return this.chainBackward(SystemJobEventName.ON_SUCCESSFUL, job);
    }

    /**
     * Chain jobs on success. Given job must successfully execute after this.
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
            state.incrementFailedToStart();
            fireSystemEvent(SystemJobEventName.ON_FAILED_TO_START);
            state.clearFlag(JobState.SCHEDULED);
            return;
        }
        if (state.trySetFlag(JobState.RUNNING)) { // ensure only one running instance
            state.setFlag(JobState.EXECUTED);
            jobThread = Thread.currentThread();
            fireSystemEvent(SystemJobEventName.ON_EXECUTE);

            try {
                runTask();
                T result = task.get();
                state.setFlag(JobState.SUCCESSFUL);
                fireSystemEvent(SystemJobEventName.ON_SUCCESSFUL, Optional.ofNullable(result));
            } catch (InterruptedException e) {
                state.setFlag(JobState.INTERRUPTED);
                fireSystemEvent(SystemJobEventName.ON_INTERRUPTED);
            } catch (Throwable e) { // execution exception or cancellation exception
                state.setFlag(JobState.EXCEPTIONAL);
                fireSystemEvent(SystemJobEventName.ON_EXCEPTIONAL, Optional.of(e));
            }

            fireSystemEvent(SystemJobEventName.ON_ATTEMPTED);
            if (state.trySetFlag(JobState.DONE)) {
                fireSystemEvent(SystemJobEventName.ON_DONE);
            }

            if (!state.tryClearFlag(JobState.RUNNING)) {
                throw new IllegalStateException("After job:" + getID() + " ran, property running was set to false");
            }

        }

    }

    /**
     * You can override to run actual task on a different executor, override
     * with caution.
     */
    protected void runTask() {
        task.run();
    }

    /**
     * You can override this to save a JobExecutor reference, but don't forget
     * to call listeners.assignJobExecutorMap, or just call super.
     */
    protected void executorSubmission(JobExecutor executor) {
        listeners.assignJobExecutorMap(executor.getExecutorJobListeners());
    }

    /**
     * Add custom job listener
     *
     * @param name
     * @param listener
     */
    public void addListener(Serializable name, JobEventListener listener) {
        assertNoChange("listeners");
        listeners.add(name, listener);
    }

    /**
     * Add system job listener
     *
     * @param name
     * @param listener
     */
    public void addListener(SystemJobEventName name, JobEventListener listener) {
        assertNoChange("systemListeners");
        listeners.add(name, listener);
    }

    protected void assertNoChange(String msg) {
        if (isScheduled()) {
            throw new IllegalStateException("Job has been scheduled, " + msg + " should not change");
        }
    }

    /**
     * Fire event with empty data
     *
     * @param event
     */
    public void fireEvent(Serializable classifier) {
        fireEvent(classifier, Optional.empty(), listeners.get(classifier), false);
    }

    /**
     * Fire event with optional data
     *
     * @param event
     */
    public void fireEvent(Serializable classifier, Optional data) {
        fireEvent(classifier, data, listeners.get(classifier), false);
    }

    /**
     * Fire a system event, with empty data
     *
     * @param eventName
     * @param data
     */
    public void fireSystemEvent(SystemJobEventName eventName) {
        fireEvent(eventName, Optional.empty());
    }

    /**
     * Fire a system event, with optional data
     *
     * @param eventName
     * @param data
     */
    public void fireSystemEvent(SystemJobEventName eventName, Optional data) {
        fireEvent(eventName, data, listeners.get(eventName), false);
    }

    /**
     *
     * @param event event
     * @param collection listeners to trigger
     * @param ignore whether to ignore exceptions
     */
    protected void fireEvent(Serializable classifier, Optional data, List<JobEventListener> collection, boolean ignore) {
        if (collection == null) {
            return;
        }
        for (JobEventListener listener : collection) {
            try {
                listener.onEvent(this, classifier, data);
            } catch (Throwable th) {
                if (!ignore) {
                    List<JobEventListener> onExcpetionalEvent = listeners.get(SystemJobEventName.ON_EXCEPTIONAL_EVENT);
                    state.setFlag(JobState.EXCEPTIONAL_EVENT);
                    if (onExcpetionalEvent != null) {
                        fireEvent(SystemJobEventName.ON_EXCEPTIONAL_EVENT, Optional.of(th), onExcpetionalEvent, true);
                    }
                }
            }
        }
    }

    public Runnable asRunnable() {
        return this::run;
    }
}
