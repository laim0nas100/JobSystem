package com.github.laim0nas100.jobsystem.events;

import java.util.Optional;
import com.github.laim0nas100.jobsystem.Job;

/**
 *
 * @author laim0nas100
 * @param <T> Job result
 * @param <C> classifier type
 */
public interface JobEventListener<T,C> {

    public void onEvent(Job<T> job, C classifier, Optional<T> data);
    
}
