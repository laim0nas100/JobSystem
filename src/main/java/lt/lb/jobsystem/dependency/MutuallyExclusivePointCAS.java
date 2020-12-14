package lt.lb.jobsystem.dependency;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import lt.lb.jobsystem.Job;
import lt.lb.jobsystem.events.JobEvent;
import lt.lb.jobsystem.events.JobEventListener;
import lt.lb.jobsystem.events.SystemJobEventName;

/**
 *
 * Simulates shared data that can only be accessed by one thread at a time.
 * A job can be in multiple exclusive points. Using compare and swap (CAS) technique.
 *
 * @author laim0nas100
 */
public class MutuallyExclusivePointCAS implements Dependency {

    protected Map<String, Job> jobs = new ConcurrentHashMap<>();
    protected AtomicReference<Job> dibsed = new AtomicReference<>(null);
    protected AtomicReference<Job> scheduled = new AtomicReference<>(null);

    public void addSharingJob(final Job job) {
        job.addDependency(this);
        final String id = job.getUUID();
        jobs.put(id, job);
        job.addListener(SystemJobEventName.ON_FAILED_TO_START, event -> {
            dibsed.compareAndSet(job, null); //free the dibs
            scheduled.compareAndSet(job, null);
        });
        job.addListener(SystemJobEventName.ON_SCHEDULED, event -> {
            if (dibsed.compareAndSet(job, job)) {
                scheduled.compareAndSet(null, job);
            }
        });
        JobEventListener jobEventListener = (JobEvent event) -> {
            dibsed.compareAndSet(job, null); //free the dibs
            scheduled.compareAndSet(job, null); //free the dibs
            jobs.remove(id); // remove the job, if such still exists
        };

        job.addListener(SystemJobEventName.ON_DONE, jobEventListener);
        job.addListener(SystemJobEventName.ON_DISCARDED, jobEventListener);
    }

    @Override
    public boolean isCompleted(Job job) {
        String id = job.getUUID();
        if (!jobs.containsKey(id)) { // check if contesting for dibs
            return true;
        }
        if (job.isScheduled()) {
            return scheduled.compareAndSet(job, job) && dibsed.compareAndSet(job, job);
        }

        //try to aquire or this job still hold the dibs
        if (dibsed.compareAndSet(null, job) || dibsed.compareAndSet(job, job)) {
            return true;
        }

        Job dibsedBy = dibsed.get();
        if (dibsedBy != null) {
            if(dibsedBy.isScheduled()){
                //other is allready scheduled, just let it be
                return false;
            }
            // other has dibs, but not scheduled, maybe i can has dibs?
            if (scheduled.compareAndSet(null, null)) {// not executing yet, set dibs to be mine
                return dibsed.compareAndSet(dibsedBy, job);
            }
        }

        return false;

    }
}
