package lt.lb.jobsystem.dependency;

import lt.lb.jobsystem.Job;

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
    public boolean isCompleted(Job job);
    
    
    /**
     * Wether dependency is possible to be satisfied
     * @return 
     */
    public default boolean isPossible(){
        return true;
    }
}
