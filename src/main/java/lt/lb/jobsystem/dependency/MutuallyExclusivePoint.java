package lt.lb.jobsystem.dependency;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lt.lb.jobsystem.Job;
import lt.lb.jobsystem.events.JobEvent;
import lt.lb.jobsystem.events.JobEventListener;
import lt.lb.jobsystem.events.SystemJobEventName;

/**
 *
 * Simulates shared data that can only be accessed by one thread at a time. A
 * job can be in multiple exclusive points.
 *
 * @author laim0nas100
 */
public class MutuallyExclusivePoint implements Dependency {

    protected Map<String, Job> jobs = new ConcurrentHashMap<>();

    public void addSharingJob(final Job job) {
        job.addDependency(this);
        final String id = job.getUUID();
        jobs.put(id, job);
        JobEventListener jobEventListener = (JobEvent event) -> {
            jobs.remove(id); // remove the job, if such still exists
        };

        job.addListener(SystemJobEventName.ON_DONE, jobEventListener);
        job.addListener(SystemJobEventName.ON_DISCARDED, jobEventListener);
    }

    private boolean jobIsDone(Job j) {
        return j.isDiscardedOrDone() || (!j.isScheduled() && !j.isRunning());
    }

    private boolean callerIsFreeToGo(Job job) {
        for (Job j : jobs.values()) {
            if (j != job && !jobIsDone(j)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isCompleted(Job job) {
        String id = job.getUUID();
        if (!jobs.containsKey(id)) { // check if is in jobs
            return true;
        } else {
            return callerIsFreeToGo(job);
        }
    }
}
