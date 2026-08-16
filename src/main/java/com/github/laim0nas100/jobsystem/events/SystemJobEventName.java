package com.github.laim0nas100.jobsystem.events;

import com.github.laim0nas100.jobsystem.JobExecutor;
import java.io.Serializable;
import java.util.concurrent.ExecutionException;

/**
 * Main documentation to understand the event loop.
 *
 * Job System events. Job System uses ON_DONE and ON_FAILED_TO_START listeners
 * to rescan for new jobs. Every event can be (and is intended to be) listened
 * and reacted to by users.
 *
 * @author laim0nas100
 */
public enum SystemJobEventName implements Serializable {

    /**
     * When Job becomes done after an attempt. This happens after job logic,
     * regardless of the logic outcome.
     *
     * executed and (exceptional or successful or cancelled or interrupted)
     */
    ON_ATTEMPTED("onAttempted", true, false),
    /**
     * When Job becomes done.
     *
     * (after attempted or discarded). The last possible event (unless the
     * exceptional event happens inside it or same job was resubmitted). Always
     * fires only once, unlike the discarded event. Can be fired inside a job,
     * or by a JobExecutor.
     */
    ON_DONE("onDone", true, true),
    /**
     * When job becomes canceled without being executed.
     *
     * (canceled and not executed)
     */
    ON_ABORTED("onAborted", true, false),
    /**
     * When Job becomes cancelled (doesn't matter if job was done before it
     * became cancelled).
     */
    ON_CANCEL("onCancel", true, false),
    /**
     * When Job becomes fails with ExecutionExcption. Provides
     * {@link ExecutionException} as data.
     */
    ON_EXCEPTIONAL("onExceptional", true, false),
    /**
     * When Job becomes discarded by {@link lt.lb.jobsystem.JobExecutor}. Job
     * can be discarded when it's done or any of its direct dependencies becomes
     * impossible. Dependencies linked by listeners doesn't count.
     */
    ON_DISCARDED("onDiscarded", false, true),
    /**
     * When Job becomes successful. Logic ends normally.
     */
    ON_SUCCESSFUL("onSuccessful", true, false),
    /**
     * When Job becomes scheduled, by {@link lt.lb.jobsystem.JobExecutor}.
     */
    ON_SCHEDULED("onScheduled", false, false),
    /**
     * When Job fails to start after being scheduled and then de-scheduled.
     *
     * That happens when dependencies are dynamic and the job becomes not ready
     * (but being ready for a short while) before a thread could pick it up. For
     * example resource sharing.
     */
    ON_FAILED_TO_START("onFailedToStart", false, false),
    /**
     * When job was interrupted while executing. Usually (but not necessary) it
     * means that job has been cancelled.
     */
    ON_INTERRUPTED("onInterrupted", true, false),
    /**
     * When exception occurs during any event. User should avoid this pattern.
     * Re-throws only once. Provides {@link Throwable} as data.
     */
    ON_EXCEPTIONAL_EVENT("onExceptionalEvent", false, false),
    /**
     * When Job practically starts. Picked up by a worker/carrier thread and the
     * logic is at least attempted, not to be confused by
     * {@link SystemJobEventName##ON_ATTEMPTED}. This happens before job logic
     * regardless of job outcome.
     */
    ON_EXECUTE("onExecute", true, false);

    /**
     * The event name;
     */
    public final String eventName;
    /**
     * Whether this event can occur only once or multiple times per job using
     * the Job System, of course the user can fire any event any amount of
     * times.
     */
    public final boolean oncePerJob;

    /**
     * Whether this event must occur if the Job enters the {@link JobExecutor}.
     */
    public final boolean inevitable;

    private SystemJobEventName(String eventName) {
        this(eventName, true, false);
    }

    private SystemJobEventName(String eventName, boolean oncePerJob, boolean inevitable) {
        this.eventName = eventName;
        this.oncePerJob = oncePerJob;
        this.inevitable = inevitable;
    }

}
