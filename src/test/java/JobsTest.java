
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import lt.lb.commons.LineStringBuilder;
import lt.lb.commons.func.unchecked.UnsafeFunction;
import lt.lb.commons.misc.rng.RandomDistribution;
import lt.lb.commons.threads.executors.FastExecutor;
import lt.lb.commons.threads.executors.FastWaitingExecutor;
import lt.lb.commons.threads.sync.WaitTime;
import lt.lb.jobsystem.Dependencies;
import lt.lb.jobsystem.Job;
import lt.lb.jobsystem.JobExecutor;
import lt.lb.jobsystem.ScheduledJobExecutor;
import lt.lb.jobsystem.dependency.Dependency;
import lt.lb.jobsystem.events.SystemJobEventName;

/**
 *
 * @author laim0nas100
 */
public class JobsTest {

    static FastWaitingExecutor fastExe = new FastWaitingExecutor(1);
    public static class Log {

        public static void print(Object... obs) {
            LineStringBuilder sb = new LineStringBuilder();
            for (Object o : obs) {
                sb.append(o).append(" ");
            }
            if (sb.length() > 0) {
                sb.removeFromEnd(1);
            }
            fastExe.execute(()->{
                System.out.println(sb);
            });
            

        }
    }

    static RandomDistribution rng = RandomDistribution.uniform(new Random());

    public static Job makeJob(String txt, List<Job> jobs) {
        Job<Number> job = new Job<>(txt, (UnsafeFunction) j -> {
            Log.print("In execute", txt);
            Long nextLong = rng.nextLong(1000L, 2000L);
            Thread.sleep(nextLong);
//            if (rng.nextInt(10) >= 8) {
//                throw new RuntimeException("OOPSIE");
//            }
            return nextLong;

        });
        job.addListener(SystemJobEventName.ON_FAILED_TO_START, e -> {
            Log.print("Failed to start ", e.getCreator().getUUID());
        });

        job.addListener(SystemJobEventName.ON_EXECUTE, e -> {
            Log.print("Execute ", e.getCreator().getUUID());
        });

        job.addListener(SystemJobEventName.ON_CANCEL, e -> {
            Log.print("Cancel ", e.getCreator().getUUID());
        });

        job.addListener(SystemJobEventName.ON_DONE, e -> {
            Log.print("Done", e.getCreator().getUUID());
        });
        job.addListener(SystemJobEventName.ON_SUCCESSFUL, e -> {
            Log.print("Success", e.getCreator().getUUID());
        });
        job.addListener(SystemJobEventName.ON_EXCEPTIONAL, e -> {
            Log.print("Failed, cancelling", e.getCreator().getUUID());
            e.getCreator().cancel();
        });

        job.addListener(SystemJobEventName.ON_SCHEDULED, e -> {
            Log.print("Scheduled", e.getCreator().getUUID());
        });

        job.addListener(SystemJobEventName.ON_DISCARDED, e -> {
            Log.print("Discarded", e.getCreator().getUUID());
        });
        ArrayList<Dependency> deps = new ArrayList<>();

        deps.add(() -> new Random().nextBoolean());
        deps.add(() -> {
            Log.print("Dep check " + txt);
            return true;
        });

//        deps.stream().map(Dependencies::cacheOnComplete).forEach(d -> {
//            job.addDependency(d);
//        });
        jobs.add(job);
        return job;
    }

    public static void main(String... args) throws Exception {
        FastExecutor executor = new FastExecutor(8);
        JobExecutor exe = new ScheduledJobExecutor(executor);
        List<Job> jobs = new ArrayList<>();

        Job j0 = makeJob("0", jobs);
        Job j1 = makeJob("1", jobs);
        Job j2 = makeJob("2", jobs);
        Job j3 = makeJob("3", jobs);
        Job j4 = makeJob("4", jobs);
        Job j5 = makeJob("5", jobs);
        Job j6 = makeJob("6", jobs);
        Job j7 = makeJob("7", jobs);

        j0.chainForward(j1).chainForward(j2).chainForward(j3);
        j1.chainForward(j4).chainForward(j5);
        j2.chainForward(j6);
        j3.chainForward(j7);

//        Job f = makeJob("Final", new ArrayList<>());
//        Jobs.chainBackward(f, JobEvent.ON_DONE, Jobs.resolveChildLeafs(j0));
//        Dependencies.mutuallyExclusive(jobs);
        jobs.forEach(exe::submit);

        exe.awaitJobEmptiness(1,TimeUnit.HOURS);
        exe.shutdown();

    }
}
