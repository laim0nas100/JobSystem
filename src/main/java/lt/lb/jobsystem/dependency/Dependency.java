package lt.lb.jobsystem.dependency;

import lt.lb.jobsystem.Job;

/**
 * Dependency with no explicit job association. 
 * @author laim0nas100
 */
public interface Dependency {


    /**
     * Whether dependency is satisfied
     *
     * @param caller job
     * @return
     */
    public boolean isCompleted(Job job);
    
    
    /**
     * Whether dependency is possible to be satisfied
     * @return 
     */
    public default boolean isPossible(){
        return true;
    }
}
