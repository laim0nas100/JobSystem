package com.github.laim0nas100.jobsystem.events;

import java.util.Optional;
import com.github.laim0nas100.jobsystem.Job;

/**
 *
 * @author laim0nas100
 * @param <T>
 */
public interface JobEventListener<T> {

//    public void onEvent(JobEvent<T> event);

    public void onEvent(Job<T> job, Object classifier, Optional<T> data);
    
    
}
