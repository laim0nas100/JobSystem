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
    public boolean isPossible(){
        return !impossible();
    }
    
    public boolean impossible(){
        switch (enumName) {
            case ON_FAILED_TO_START: {
                return job.isDiscardedOrDone() && job.getFailedToStart() == 0;
            }
            case ON_ABORTED: {
                return job.isExecuted() || (job.isDiscarded() && !job.isAborted());
            }
            case ON_INTERRUPTED: {
                return job.isExecuted() || job.isSuccessfull() || job.isExceptional() || (job.isDiscarded() && !job.isInterrupted());
            }
            case ON_SCHEDULED: {
                return job.isDiscardedOrDone()&& !job.isScheduled();
            }

            case ON_EXECUTE: {
                return job.isDiscardedOrDone() && !job.isExecuted();
            }

            case ON_EXCEPTIONAL_EVENT: { //can always fire events
                return false;
            }

            case ON_DISCARDED: { //can always discard a job
                return false;
            }
            case ON_EXCEPTIONAL: {
                return job.isDiscardedOrDone()&& !job.isExceptional();
            }
            case ON_CANCEL: { // can always cancel
                return false;
            }
            case ON_SUCCESSFUL: {
                return job.isDiscardedOrDone() && !job.isSuccessfull();
            }
            case ON_DONE: {
                return job.isDiscarded() && !job.isDone();
            }
            default: {
                throw new IllegalArgumentException("Failed to qualify enum " + enumName);
            }
        }
    }

    @Override
    public boolean isCompleted(Job caller) {
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
