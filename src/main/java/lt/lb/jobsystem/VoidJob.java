package lt.lb.jobsystem;

import java.util.function.Consumer;

/**
 *
 * @author laim0nas100
 */
public class VoidJob extends Job<Void> {

    public VoidJob(String uuid, Consumer<Job<Void>> call) {
        super(uuid, call);
    }

    public VoidJob(Consumer<Job<Void>> call) {
        super(call);
    }

}
