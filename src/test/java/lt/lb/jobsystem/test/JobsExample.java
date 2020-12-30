package lt.lb.jobsystem.test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lt.lb.jobsystem.Job;
import lt.lb.jobsystem.JobExecutor;
import lt.lb.jobsystem.ScheduledJobExecutor;
import lt.lb.jobsystem.dependency.Dependency;
import lt.lb.jobsystem.events.SystemJobEventName;

/**
 *
 * @author laim0nas100
 */
public class JobsExample {

    static ExecutorService executor = Executors.newFixedThreadPool(8);

    public static class Log {

        public static void print(Object... obs) {
            StringBuilder sb = new StringBuilder();
            for (Object o : obs) {
                sb.append(o).append(" ");
            }
            if (sb.length() > 0) {
                sb.deleteCharAt(sb.length() - 1);
            }
            executor.execute(() -> {
                System.out.println(sb);
            });

        }
    }

    static Random rng = new Random();

    public static void addEventLogListeners(Job job) {
        EnumSet<SystemJobEventName> enums = EnumSet.allOf(SystemJobEventName.class);
        enums.forEach(val -> {
            job.addListener(val, e -> {
                Log.print(val.name() + " " + e.getCreator().getUUID());
            });
        });
    }

    public static Job makeJob(String txt, List<Job> jobs) {
        Job<Number> job = new Job<>(txt, () -> {
            Log.print("In execute", txt);
            Long nextLong = (rng.nextLong() % 1000) + 1000;
            Thread.sleep(nextLong);
//            if (rng.nextInt(10) >= 8) {
//                throw new RuntimeException("OOPSIE");
//            }
            return nextLong;

        });

        addEventLogListeners(job);

        job.addListener(SystemJobEventName.ON_EXCEPTIONAL, e -> {
            Log.print("Failed, cancelling", e.getCreator().getUUID());
            e.getCreator().cancel();
        });

        ArrayList<Dependency> deps = new ArrayList<>();

        deps.add(j -> new Random().nextBoolean());
        deps.add(j -> {
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

        exe.awaitJobEmptiness(1, TimeUnit.HOURS);
        exe.shutdown();
        executor.shutdown();

    }
}
