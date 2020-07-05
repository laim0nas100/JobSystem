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
    public boolean isCompleted() {
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
            default: {
                throw new IllegalArgumentException("Failed to qualify enum " + enumName);
            }
        }
    }

}
