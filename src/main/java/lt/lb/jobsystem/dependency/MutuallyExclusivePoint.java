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
 * Simulates shared data (that can only be accessed by one thread at a time)
 * @author laim0nas100
 */
public class MutuallyExclusivePoint implements Dependency {

    protected Map<String, Job> jobs = new ConcurrentHashMap<>();
    protected AtomicReference<String> dibsed = new AtomicReference<>(null);

    public void addSharingJob(Job job) {
        job.addDependency(this);
        final String id = job.getUUID();
        jobs.put(id, job);
        job.addListener(SystemJobEventName.ON_FAILED_TO_START, event -> {
            if (dibsed.compareAndSet(id, null)) {
                //succefully descheduled
            } else {
                //something else allready got scheduled, should not happen
            }
        });
        JobEventListener jobEventListener = (JobEvent event) -> {
            dibsed.compareAndSet(id, null); //free the dibs
            jobs.remove(id); // remove the job, if such still exists
        };
        
        job.addListener(SystemJobEventName.ON_DONE, jobEventListener);
        job.addListener(SystemJobEventName.ON_DISCARDED, jobEventListener);
    }

    @Override
    public boolean isCompleted(Job caller) {
        String id = caller.getUUID();
        if (!jobs.containsKey(id)) { // check if contesting for dibs
            return true;
        }

        //try to aquire || or this job still hold the dibs
        return dibsed.compareAndSet(null, id) || dibsed.compareAndSet(id, id);
    }

}
