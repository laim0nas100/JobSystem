package lt.lb.jobsystem.events;

/**
 *
 * @author laim0nas100
 * @param <T>
 */
public interface JobEventListener<T> {

    public void onEvent(JobEvent<T> event);
}
