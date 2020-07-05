package lt.lb.jobsystem;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import lt.lb.commons.threads.sync.WaitTime;

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
        this(WaitTime.ofSeconds(1), 2, exe);
    }

    /**
     * @param rescanThrottle how many concurrent rescan jobs can be happening
     * @param exe Main executor
     * @param rescan WaitTime to set the scheduler rescan period.
     */
    public ScheduledJobExecutor(WaitTime rescan, int rescanThrottle, Executor exe) {
        super(rescanThrottle, exe);

        service.scheduleAtFixedRate(() -> rescanJobs(), rescan.time, rescan.time, rescan.unit);
    }

    @Override
    public void shutdown() {
        super.shutdown();
        service.shutdown();

    }

}
