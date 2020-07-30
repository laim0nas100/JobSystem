package lt.lb.jobsystem.events;

import java.util.Objects;
import java.util.Optional;
import lt.lb.jobsystem.Job;

/**
 *
 * @author laim0nas100
 * @param <T> data type associated with this JobEvent
 */
public class JobEvent<T> {

    private final String eventName;
    private final Job createdBy;
    private Optional<T> data;

    /**
     *
     * @return event name
     */
    public String getEventName() {
        return eventName;
    }

    /**
     *
     * @return Job that created this event.
     */
    public Job getCreator() {
        return this.createdBy;
    }

    /**
     *
     * @return optional data associated with this event
     */
    public Optional<T> getData() {
        return data;
    }

    /**
     * Create a new JobEvent
     * @param eventName event name
     * @param source Job that created this event
     */
    public JobEvent(String eventName, Job source) {
        this(eventName, source, null);
    }

    /**
     * Create a new JobEvent
     * @param eventName event name
     * @param source Job that created this event
     * @param data nullable data associated with this event
     */
    public JobEvent(String eventName, Job source, T data) {
        this.eventName = Objects.requireNonNull(eventName);
        this.createdBy = Objects.requireNonNull(source);
        this.data = Optional.ofNullable(data);
    }
}
