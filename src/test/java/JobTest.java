
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lt.lb.commons.containers.values.LongValue;
import lt.lb.commons.misc.rng.FastRandom;
import lt.lb.commons.misc.rng.RandomDistribution;
import lt.lb.commons.threads.executors.FastWaitingExecutor;
import lt.lb.commons.threads.sync.WaitTime;
import lt.lb.jobsystem.Dependencies;
import lt.lb.jobsystem.Job;
import lt.lb.jobsystem.JobExecutor;
import lt.lb.jobsystem.VoidJob;
import lt.lb.jobsystem.events.SystemJobEventName;
import org.junit.Test;

/**
 *
 * @author laim0nas100
 */
public class JobTest {

    public static Job<Void> incrementJob(LongValue val, Integer increments) {

        return new VoidJob(j -> {
            for (int i = 0; i < increments; i++) {
                val.incrementAndGet();
            }
        });

//        return new Job<>((Job<Void> j) -> {
//            for (int i = 0; i < increments; i++) {
//                val.incrementAndGet();
//            }
//            
//        });
    }

    public static void doIncrement(int jobs, Consumer<ArrayList<Job>> jobDepModifier) throws InterruptedException {
        JobExecutor executor = new JobExecutor(new FastWaitingExecutor(4));
        RandomDistribution rng = RandomDistribution.uniform(new FastRandom());

        ArrayList<Job> jobList = new ArrayList<>();

        ArrayList<Integer> increments = new ArrayList<>(jobs);
        for (int i = 0; i < jobs; i++) {
            increments.add(rng.nextInt(100, 500));
        }

        LongValue val = new LongValue(0L);
        LongValue expected = new LongValue(0L);
        for (Integer inc : increments) {
            jobList.add(incrementJob(val, inc));
            expected.incrementAndGet(inc);
        }

        jobDepModifier.accept(jobList);

        jobList.forEach(executor::submit);

        executor.shutdown();

        assert executor.awaitTermination(1, TimeUnit.HOURS);
        assert expected.equals(val);
    }

    @Test
    public void incrementTest() throws InterruptedException {
        doIncrement(100, jobs -> Dependencies.mutuallyExclusive(jobs));
        doIncrement(100, jobs -> Dependencies.backwardChain(jobs, SystemJobEventName.ON_SUCCESSFUL));
        doIncrement(100, jobs -> Dependencies.forwardChain(jobs, SystemJobEventName.ON_SUCCESSFUL));
    }
}
