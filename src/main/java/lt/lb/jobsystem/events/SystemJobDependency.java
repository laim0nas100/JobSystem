package lt.lb.jobsystem.events;

import lt.lb.jobsystem.Job;
import lt.lb.jobsystem.dependency.AbstractJobDependency;

/**
 * System job dependency support all system events defined in
 * SystemJobEventName.
 *
 * @author laim0nas100
 */
public class SystemJobDependency extends AbstractJobDependency {

    public final SystemJobEventName enumName;

    public SystemJobDependency(Job j, SystemJobEventName event) {
        super(j, event.eventName);
        this.enumName = event;

    }

    @Override
    public boolean isPossible() {
        return !impossible(job, enumName);
    }

    @Override
    public boolean isCompleted(Job caller) {
        return isCompleted(job, enumName);
    }

    public static boolean impossible(Job job, SystemJobEventName enumName) {
        switch (enumName) {
            case ON_FAILED_TO_START: {
                return job.isRemovable()&& job.getFailedToStart() == 0;
            }
            case ON_ABORTED: {
                return job.isExecuted() || (job.isDiscarded() && !job.isAborted());
            }
            case ON_INTERRUPTED: {
                return job.isExecuted() || job.isSuccessfull() || job.isExceptional() || (job.isDiscarded() && !job.isInterrupted());
            }
            case ON_SCHEDULED: {
                return job.isRemovable() && !job.isScheduled();
            }

            case ON_EXECUTE: {
                return job.isRemovable() && !job.isExecuted();
            }

            case ON_EXCEPTIONAL_EVENT: { //can always fire events
                return false;
            }

            case ON_DISCARDED: { //can always discard a job
                return false;
            }
            case ON_EXCEPTIONAL: {
                return job.isRemovable() && !job.isExceptional();
            }
            case ON_CANCEL: { // can always cancel
                return false;
            }
            case ON_SUCCESSFUL: {
                return job.isRemovable() && !job.isSuccessfull();
            }
            case ON_DONE: { // can always be done
                return false;
            }
            case ON_ATTEMPTED: {
                return job.isAborted();
            }
            default: {
                throw new IllegalArgumentException("Failed to qualify enum " + enumName);
            }
        }
    }

    public static boolean isCompleted(Job job, SystemJobEventName enumName) {
        switch (enumName) {
            case ON_FAILED_TO_START: {
                return job.getFailedToStart() > 0;
            }
            case ON_ABORTED: {
                return job.isAborted();
            }
            case ON_INTERRUPTED: {
                return job.isInterrupted();
            }
            case ON_SCHEDULED: {
                return job.isScheduled();
            }

            case ON_EXECUTE: {
                return job.isExecuted();
            }

            case ON_EXCEPTIONAL_EVENT: {
                return job.isExceptionalEvent();
            }

            case ON_DISCARDED: {
                return job.isDiscarded();
            }
            case ON_EXCEPTIONAL: {
                return job.isExceptional();
            }
            case ON_CANCEL: {
                return job.isCancelled();
            }
            case ON_SUCCESSFUL: {
                return job.isSuccessfull();
            }
            case ON_DONE: {
                return job.isDone();
            }
            case ON_ATTEMPTED: {
                return job.isAttempted();
            }
            default: {
                throw new IllegalArgumentException("Failed to qualify enum " + enumName);
            }
        }
    }

}
