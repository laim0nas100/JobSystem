package com.github.laim0nas100.jobsystem.dependency;

import com.github.laim0nas100.jobsystem.Job;

/**
 *
 * Dependency with explicit job association. 
 * @author laim0nas100
 * @param <T>
 */
public interface JobDependency<T> extends Dependency {

    

    /**
     * Job that comes with dependency
     * @return 
     */
    public Job<T> getJob();
}
