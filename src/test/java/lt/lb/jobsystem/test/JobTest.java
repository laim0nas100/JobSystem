package lt.lb.jobsystem.test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import lt.lb.jobsystem.Dependencies;
import lt.lb.jobsystem.Job;
import lt.lb.jobsystem.JobExecutor;
import lt.lb.jobsystem.ScheduledJobExecutor;
import lt.lb.jobsystem.dependency.Dependency;
import lt.lb.jobsystem.dependency.MutuallyExclusivePoint;
import lt.lb.jobsystem.events.SystemJobEventName;
import org.junit.Test;

/**
 *
 * @author laim0nas100
 */
public class JobTest {

    public static class LongHolder {

        long numb;

        public LongHolder(long numb) {
            this.numb = numb;
        }

        public LongHolder() {
        }

    }

    public static Job incrementJob(LongHolder val, Integer increments) {

        return new Job(j -> {
            for (int i = 0; i < increments; i++) {
                val.numb++;
            }
        });

    }

    public static void doIncrement(int jobs, Consumer<ArrayList<Job>> jobDepModifier) throws InterruptedException {
        ExecutorService exeServ = Executors.newFixedThreadPool(8);
        JobExecutor executor = new JobExecutor(exeServ);
        Random rng = new Random();

        ArrayList<Job> jobList = new ArrayList<>();

        ArrayList<Integer> increments = new ArrayList<>(jobs);
        for (int i = 0; i < jobs; i++) {
            increments.add(rng.nextInt(400) + 100);
        }

        LongHolder val = new LongHolder(0L);
        LongHolder expected = new LongHolder(0L);
        for (Integer inc : increments) {
            jobList.add(incrementJob(val, inc));
            expected.numb += inc;
        }

        jobDepModifier.accept(jobList);

        jobList.forEach(executor::submit);

        executor.awaitJobEmptiness(1, TimeUnit.DAYS);
        executor.shutdown();
        exeServ.shutdown();

        assert Objects.equals(expected.numb, val.numb);
    }

    @Test
    public void incrementTest() throws InterruptedException {
        doIncrement(100, jobs -> Dependencies.mutuallyExclusive(jobs));
        doIncrement(100, jobs -> Dependencies.backwardChain(jobs, SystemJobEventName.ON_SUCCESSFUL));
        doIncrement(100, jobs -> Dependencies.forwardChain(jobs, SystemJobEventName.ON_SUCCESSFUL));
    }

    public static void addEventLogListeners(Job job) {
        EnumSet<SystemJobEventName> enums = EnumSet.allOf(SystemJobEventName.class);
        enums.forEach(val -> {
            job.addListener(val, e -> {
//                Log.print(job.getUUID() + " " + val.name());
            });
        });
    }

    static AtomicLong idGen = new AtomicLong(0L);

    public static String getID() {
        return idGen.getAndIncrement() + "";
    }

    @Test
    public void exclusiveInterestPointTest() throws InterruptedException {

        for (int t = 0; t < 10; t++) {
            ExecutorService exeServ = Executors.newFixedThreadPool(8);
            JobExecutor executor = new ScheduledJobExecutor(exeServ);
            AtomicLong atomLong = new AtomicLong(0L);
            LongHolder longVal1 = new LongHolder(0L);
            LongHolder longVal2 = new LongHolder(0L);
            Random rng = new Random();

            Integer jobs = rng.nextInt(5) + 20;
            int middle = rng.nextInt(jobs - 10) + 5;

            Integer range = rng.nextInt(50000)+50000;

            MutuallyExclusivePoint point1 = new MutuallyExclusivePoint();

            MutuallyExclusivePoint point2 = new MutuallyExclusivePoint();

            Dependency randomDep = j -> {
//            Log.print(j.getUUID() + "-DEP CHECK");
                return true;
            };

            ArrayDeque<Job> allJobs = new ArrayDeque<>();
            for (int i = 0; i < jobs; i++) {
                if (i < middle) {
                    Job jobBoth = new Job(getID() + "B", job -> {
                        for (int j = 0; j < range; j++) {
                            longVal1.numb++;
                            longVal2.numb++;
                        }
                    });
                    allJobs.add(jobBoth);
                    jobBoth.addDependency(randomDep);
                    point1.addSharingJob(jobBoth);
                    point2.addSharingJob(jobBoth);

                } else {
                    Job jobEx1 = new Job(getID() + "E1", job -> {
                        for (int j = 0; j < range; j++) {
                            longVal1.numb++;
                        }
                    });
                    allJobs.add(jobEx1);
                    jobEx1.addDependency(randomDep);
                    point1.addSharingJob(jobEx1);

                    Job jobEx2 = new Job(getID() + "E2", job -> {
                        for (int j = 0; j < range; j++) {
                            longVal2.numb++;
                        }
                    });
                    allJobs.add(jobEx2);
                    jobEx2.addDependency(randomDep);
                    point2.addSharingJob(jobEx2);

                }

                Job jobSimple = new Job(getID(), job -> {
                    for (int j = 0; j < range; j++) {
                        atomLong.incrementAndGet();
                    }
                });
                jobSimple.addDependency(randomDep);

                allJobs.add(jobSimple);

            }
//            allJobs.forEach(j -> {
//                addEventLogListeners(j);
//            });
            executor.submitAll(allJobs);

//        executor.submitAll(allJobs);
            executor.awaitJobEmptiness(1, TimeUnit.DAYS);
            assert atomLong.get() == longVal1.numb;
            assert longVal1.numb == longVal2.numb;
        }

    }

}
