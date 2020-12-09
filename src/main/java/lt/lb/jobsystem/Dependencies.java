package lt.lb.jobsystem;

import lt.lb.jobsystem.dependency.Dependency;
import lt.lb.jobsystem.dependency.JobDependency;
import lt.lb.jobsystem.events.SystemJobDependency;
import lt.lb.jobsystem.events.SystemJobEventName;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 *
 * @author laim0nas100
 */
public abstract class Dependencies {

    /**
     * Create cached dependency. Once method {@link lt.lb.jobsystem.dependency.Dependency#isComplete()
     * } returns true, the result becomes cached, and it always returns true.
     *
     * @param dep
     * @return
     */
    public static Dependency cacheOnComplete(Dependency dep) {
        AtomicBoolean value = new AtomicBoolean(false);
        return job -> {
            if (!value.get()) {
                value.set(dep.isCompleted(job));
            }
            return value.get();
        };
    }

    /**
     * Creates job dependency based on job system events;
     *
     * @param job
     * @param event
     * @return
     */
    public static SystemJobDependency standard(Job job, SystemJobEventName event) {
        return new SystemJobDependency(job, event);
    }

    /**
     * Creates JobDependency, which is satisfied while provided job is not
     * executing. (isDone || (!isScheduled && !isRunning)
     *
     * @param job
     * @return
     */
    public static <T> JobDependency<T> whileNotExecuting(Job<T> job) {

        return ofJob(job, j -> j.isDiscardedOrDone()|| (!j.isScheduled() && !j.isRunning()));
    }

    /**
     * Creates a job dependency of specified predicate logic and job.
     *
     * @param <T>
     * @param job
     * @param predicate
     * @return
     */
    public static <T> JobDependency<T> ofJob(Job<T> job, Predicate<Job<T>> predicate) {
        return new JobDependency<T>() {
            @Override
            public Job getJob() {
                return job;
            }

            @Override
            public boolean isCompleted(Job callerJob) {
                return predicate.test(job);
            }
        };
    }

    /**
     * Adds JobDependecy to all jobs, that only allows 1 job to be executing at
     * any given time from given collection. Creates (n-1) * (n-1) dependencies,
     * where n is number of jobs submitted.
     *
     * @param jobs
     */
    public static void mutuallyExclusive(Collection<Job> jobs) {
        if (jobs.size() <= 1) { // nothing to exclude
            return;
        }

        int i = 0;
        for (Job main : jobs) {

            int j = 0;
            for (Job other : jobs) {

                if (i != j) {
                    main.addDependency(whileNotExecuting(other));
                }
                j++;
            }
            i++;
        }
    }

    /**
     * Chain all jobs forward to given root job. In other words, root job
     * becomes a dependency to all other jobs.
     *
     * @param root
     * @param evName
     * @param deps
     */
    public static void chainForward(Job root, SystemJobEventName evName, Iterable<Job> deps) {
        for (Job j : deps) {
            root.chainForward(evName, j);
        }
    }

    /**
     * Chains given jobs consecutively forward. In other words, every job
     * becomes direct dependency of the next job in the iterable. 1-st job must
     * execute before 2-nd, 2-nd before 3-rd etc.
     *
     * @param jobs
     * @param evName
     */
    public static void forwardChain(Iterable<Job> jobs, SystemJobEventName evName) {
        Job prev = null;
        for (Job job : jobs) {
            if (prev == null) {
                prev = job;
                continue;
            }

            prev.chainForward(evName, job);
            prev = job;
        }
    }

    /**
     * Chains given jobs consecutively backward In other words, every job
     * becomes direct dependency of the previous job in the iterable. 1-st job
     * must execute after 2-nd, 2-nd after 3-rd etc.
     *
     * @param jobs
     * @param evName
     */
    public static void backwardChain(Iterable<Job> jobs, SystemJobEventName evName) {
        Job prev = null;
        for (Job job : jobs) {
            if (prev == null) {
                prev = job;
                continue;
            }

            prev.chainBackward(evName, job);
            prev = job;
        }
    }

    /**
     * Chain all jobs backward to given root job.
     *
     * @param root
     * @param evName
     * @param deps
     */
    public static void chainBackward(Job root, SystemJobEventName evName, Iterable<Job> deps) {
        for (Job j : deps) {
            root.chainBackward(evName, j);
        }
    }

    public static Dependency any(JobDependency... deps) {
        if (deps == null || deps.length == 0) {
            throw new IllegalArgumentException("no JobDependecies");
        }
        return caller -> {
            for (JobDependency d : deps) {
                if (d.isCompleted(caller)) {
                    return true;
                }
            }
            return false;
        };

    }

    public static Dependency all(JobDependency... deps) {
        if (deps == null || deps.length == 0) {
            throw new IllegalArgumentException("no JobDependecies");
        }
        return caller -> {
            for (JobDependency d : deps) {
                if (!d.isCompleted(caller)) {
                    return false;
                }
            }
            return true;
        };
    }

}
