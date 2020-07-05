package lt.lb.jobsystem;

import lt.lb.commons.func.unchecked.UnsafeConsumer;

/**
 *
 * @author laim0nas100
 */
public class VoidJob extends Job<Void> {

    public VoidJob(String uuid, UnsafeConsumer<Job<Void>> call) {
        super(uuid, call);
    }

    public VoidJob(UnsafeConsumer<Job<Void>> call) {
        super(call);
    }
    
}
