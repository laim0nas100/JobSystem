package com.github.laim0nas100.jobsystem;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 *
 * @author laim0nas100
 */
public class JobState {
    public final AtomicInteger flags = new AtomicInteger(0);

    // Bit constants
    public static final int EXCEPTIONAL = 1;
    public static final int SUCCESSFUL = 1 << 1;
    public static final int INTERRUPTED = 1 << 2;
    public static final int EXCEPTIONAL_EVENT = 1 << 3;
    public static final int EXECUTED = 1 << 4;
    public static final int SCHEDULED = 1 << 5;
    public static final int DISCARDED = 1 << 6;
    public static final int REPEATED_DISCARD = 1 << 7;
    public static final int RUNNING = 1 << 8;
    public static final int CANCELLED = 1 << 9;
    public static final int DONE = 1 << 10;

    // upper 16 bits = failedToStart counter
    public static final int FAILED_SHIFT = 16;
    public static final int FAILED_INC = 1 << FAILED_SHIFT;
    public static final int FAILED_MASK = 0xFFFF0000;

    public static final int REMOVABLE_MASK
            = DISCARDED
            | SUCCESSFUL
            | CANCELLED
            | EXCEPTIONAL
            | INTERRUPTED
            | DONE;
    
    
    public boolean trySetFlag(int flag) {
        int current;
        int updated;

        do {
            current = flags.get();

            if ((current & flag) != 0) {
                return false; // already set
            }

            updated = current | flag;
            if (flags.compareAndSet(current, updated)) {
                return true;
            }
            LockSupport.parkNanos(1);// backoff

        } while (true);
    }

    public boolean tryClearFlag(int flag) {
        int current;
        int updated;

        do {
            current = flags.get();

            if ((current & flag) == 0) {
                return false; // already cleared
            }

            updated = current & ~flag;
            boolean ok = flags.compareAndSet(current, updated);
            if (ok) {
                return ok;
            }
            LockSupport.parkNanos(1);// backoff

        } while (true);
    }

    public void setFlag(int flag) {
        setFlag(flag, true);
    }

    public void setFlag(int flag, boolean repeated) {
        int current;
        int updated;

        do {
            current = flags.get();
            updated = current | flag;
            if (current == updated) {
                return;
            }
            if (flags.compareAndSet(current, updated)) {
                return;
            }
            LockSupport.parkNanos(1);// backoff

        } while (repeated);
    }

    public void clearFlag(int flag) {
        clearFlag(flag, true);
    }

    public void clearFlag(int flag, boolean repeated) {
        int current;
        int updated;

        do {
            current = flags.get();
            updated = current & ~flag;

            // already cleared
            if (current == updated) {
                return;
            }

            if (flags.compareAndSet(current, updated)) {
                return;
            }
            LockSupport.parkNanos(1);// backoff
        } while (repeated);
    }

    public boolean hasFlag(int flag) {
        return (flags.get() & flag) != 0;
    }

    public int incrementFailedToStart() {
        int current;
        int updated;

        int count;
        do {
            current = flags.get();

            count = (current >>> FAILED_SHIFT);

            // saturate at 65535
            if (count == 0xFFFF) {
                return count;
            }

            updated = current + FAILED_INC;
            if (flags.compareAndSet(current, updated)) {
                return count + 1;
            }
            LockSupport.parkNanos(1);// backoff

        } while (true);

    }
    
    /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_FAILED_TO_START}
     *
     * @return
     */
    public int getFailedToStart() {
        return (flags.get() >>> FAILED_SHIFT);
    }
    
    /**
     * Returns if discarded or done.
     *
     * @return
     */
    public boolean isRemovable() {
        return (flags.get() & REMOVABLE_MASK) != 0;
    }
    
    /**
     * Returns if isExecuted and done.
     *
     * @return
     */
    public boolean isAttempted() {
        int bits = flags.get();
        return (bits & JobState.EXECUTED) != 0
                && (bits & JobState.DONE) != 0;
    }
    
     /**
     * {@link lt.lb.jobsystem.events.SystemJobEventName#ON_ABORTED}
     *
     * @return
     */
    public boolean isAborted() {
        int bits = flags.get();
        return (bits & JobState.CANCELLED) != 0
                && (bits & JobState.EXECUTED) == 0;
    }
}
