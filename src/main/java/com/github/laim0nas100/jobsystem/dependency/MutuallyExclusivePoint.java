package com.github.laim0nas100.jobsystem.dependency;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.github.laim0nas100.jobsystem.Job;
import com.github.laim0nas100.jobsystem.events.JobEventListener;
import com.github.laim0nas100.jobsystem.events.SystemJobEventName;

/**
 *
 * Simulates shared data that can only be accessed by one thread (job) at a time. A
 * job can be in multiple exclusive points. It is paramount that Job IDs are unique.
 *
 * @author laim0nas100
 */
public class MutuallyExclusivePoint implements Dependency {

    protected Map<Serializable, Job> jobs = new ConcurrentHashMap<>();

    public void addSharingJob(final Job job) {
        job.addDependency(this);
        final Serializable id = job.getID();
        jobs.put(id, job);
        JobEventListener jobEventListener = (j,c,d) -> {
            jobs.remove(id); // remove the job, if such still exists
        };

        job.addListener(SystemJobEventName.ON_DONE, jobEventListener);
    }

    private boolean jobIsDone(Job j) {
        return j.isRemovable()|| (!j.isScheduled() && !j.isRunning());
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
        Serializable id = job.getID();
        if (!jobs.containsKey(id)) { // check if it is in jobs
            return true;
        } else {
            return callerIsFreeToGo(job);
        }
    }
}
