package lt.lb.jobsystem.dependency;

import java.util.Objects;
import lt.lb.jobsystem.Job;

/**
 * @author laim0nas100
 */
public abstract class AbstractJobDependency implements JobDependency {

    protected Job job;
    protected String onEvent;

    public AbstractJobDependency(Job job, String onEvent) {
        this.job = job;
        this.onEvent = onEvent;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final AbstractJobDependency other = (AbstractJobDependency) obj;
        if (!Objects.equals(this.onEvent, other.onEvent)) {
            return false;
        }
        if (!Objects.equals(this.job, other.job)) {
            return false;
        }
        return true;
    }

    @Override
    public Job getJob() {
        return job;
    }

    

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 83 * hash + Objects.hashCode(this.job);
        hash = 83 * hash + Objects.hashCode(this.onEvent);
        return hash;
    }
}
