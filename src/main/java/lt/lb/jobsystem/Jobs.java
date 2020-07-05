package lt.lb.jobsystem;

import lt.lb.jobsystem.dependency.Dependency;
import lt.lb.jobsystem.dependency.JobDependency;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import lt.lb.commons.iteration.ReadOnlyIterator;
import lt.lb.commons.iteration.TreeVisitor;

/**
 *
 * @author laim0nas100
 */
public abstract class Jobs {

    /**
     * Resolve child leafs. Can be used to determine jobs without anything after
     * them.
     *
     * @param root
     * @return
     */
    public static List<Job> resolveChildLeafs(Job root) {
        ArrayList<Job> leafs = new ArrayList<>();
        Consumer<Job> cons = job -> {
            if (job.doAfter.isEmpty()) {
                leafs.add(job);
            }
        };

        TreeVisitor<Job> visitor = TreeVisitor.ofAll(cons, node -> ReadOnlyIterator.of(node.doAfter));
        visitor.BFS(root);
        return leafs;
    }

    /**
     * Resolve root leafs. Can be used to determine jobs without
     * JobDependencies.
     *
     * @param root
     * @return
     */
    public static List<Job> resolveRootLeafs(Job root) {
        ArrayList<Job> leafs = new ArrayList<>();
        Consumer<Job> cons = job -> {
            if (job.doBefore.isEmpty()) {
                leafs.add(job);
            }
        };

        TreeVisitor<Job> visitor = TreeVisitor.ofAll(cons, node -> {
            Collection<Dependency> doBefore = node.doBefore;
            return ReadOnlyIterator.of(doBefore.stream().filter(p -> p instanceof JobDependency))
                    .map(m -> (JobDependency) m).map(m -> m.getJob());
        });
        visitor.BFS(root);
        return leafs;
    }

}
