package com.github.laim0nas100.jobsystem.dependency;

import java.util.Objects;
import com.github.laim0nas100.jobsystem.Job;
import java.io.Serializable;

/**
 * @author laim0nas100
 */
public abstract class AbstractJobDependency<T extends Serializable> implements JobDependency {

    protected final Job job;
    protected final T classifier;

    public AbstractJobDependency(Job job, T classifier) {
        this.job = Objects.requireNonNull(job);
        this.classifier = Objects.requireNonNull(classifier);
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
        if (!Objects.equals(this.classifier, other.classifier)) {
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

    public T getClassifier() {
        return classifier;
    }

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 83 * hash + Objects.hashCode(this.job);
        hash = 83 * hash + Objects.hashCode(this.classifier);
        return hash;
    }
}
