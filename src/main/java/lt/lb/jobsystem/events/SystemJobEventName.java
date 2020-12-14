package lt.lb.jobsystem.events;

import java.util.concurrent.ExecutionException;

/**
 * Default Job System events. Job System uses ON_DONE and ON_FAILED_TO_START
 * listeners to rescan for new jobs. The rest can be listened to by users.
 *
 * @author laim0nas100
 */
public enum SystemJobEventName {
    /**
     * When Job becomes done.
     *
     * (exceptional or successful or cancelled)
     */
    ON_DONE("onDone"),
    /**
     * When job becomes canceled without being executed.
     *
     * (canceled and not executed)
     */
    ON_ABORTED("onAborted"),
    /**
     * When Job becomes cancelled (doesn't matter if job was done before it
     * became cancelled).
     */
    ON_CANCEL("onCancel"),
    /**
     * When Job becomes fails with ExecutionExcption. Provides
     * {@link ExecutionException} as data.
     */
    ON_EXCEPTIONAL("onExceptional"),
    /**
     * When Job becomes discarded by {@link lt.lb.jobsystem.JobExecutor}. Job
     * can be discarded when it's done some of its dependencies becomes impossible.
     */
    ON_DISCARDED("onDiscarded"),
    /**
     * When Job becomes successful.
     */
    ON_SUCCESSFUL("onSuccessful"),
    /**
     * When Job becomes scheduled, by {@link lt.lb.jobsystem.JobExecutor}.
     */
    ON_SCHEDULED("onScheduled", false),
    /**
     * When Job fails to start after being scheduled and then de-scheduled.
     *
     * That happens when dependencies are dynamic and the job becomes not ready
     * (but being ready for a short while) before a thread could pick it up.
     */
    ON_FAILED_TO_START("onFailedToStart", false),
    /**
     * When job was interrupted while executing. Usually (but not necessary) it
     * means that job has been cancelled.
     */
    ON_INTERRUPTED("onInterrupted"),
    /**
     * When exception occurs during event. Provides {@link Throwable} as data.
     */
    ON_EXCEPTIONAL_EVENT("onExceptionalEvent", false),
    /**
     * When Job starts actually starts and gets a Job thread. (running or done)
     */
    ON_EXECUTE("onExecute");

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

    private SystemJobEventName(String eventName) {
        this(eventName, true);
    }

    private SystemJobEventName(String eventName, boolean oncePerJob) {
        this.eventName = eventName;
        this.oncePerJob = oncePerJob;
    }

}
