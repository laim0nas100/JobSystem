package lt.lb.jobsystem;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * * Job executor with provided base executor. Cleanup (shutdown) is necessary.
 * Job scheduling uses same provided executor (usually the same work thread
 * after job was finished). Periodically (1 second by default) rescans jobs.
 *
 * @author laim0nas100
 */
public class ScheduledJobExecutor extends JobExecutor {

    ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor();

    /**
     * @param exe Main executor
     */
    public ScheduledJobExecutor(Executor exe) {
        this(1, TimeUnit.SECONDS, 3, exe);
    }

    /**
     * @param time rescan period time
     * @param unit rescan period unit
     * @param rescanThrottle how many concurrent rescan jobs can be happening
     * @param exe Main executor
     */
    public ScheduledJobExecutor(long time, TimeUnit unit, int rescanThrottle, Executor exe) {
        super(rescanThrottle, exe);
        service.scheduleAtFixedRate(() -> rescanJobs(), time, time, unit);
    }

    @Override
    public void shutdown() {
        super.shutdown();
        service.shutdown();

    }

}
