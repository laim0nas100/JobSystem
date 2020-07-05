package lt.lb.jobsystem.dependency;

/**
 * Dependency with no explicit job association. 
 * @author laim0nas100
 */
public interface Dependency {


    /**
     * Wether dependency is satisfied
     *
     * @return
     */
    public boolean isCompleted();
}
