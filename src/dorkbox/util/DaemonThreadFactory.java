package dorkbox.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A ThreadFactory instance, where all threads are created with setDaemon(true) and can be assigned a threadgroup and name prefix
 */
public
class DaemonThreadFactory implements ThreadFactory {
    private final ThreadGroup threadGroup;
    private final String threadName;
    private AtomicInteger threadCount = new AtomicInteger(0);

    /**
     * Creates a new ThreadFactory instance, where all threads are created with setDaemon(true) and are assigned to a current threads
     * threadgroup with a name prefix "Thread" and thread count ID
     */
    public
    DaemonThreadFactory() {
        this(null, null);
    }

    /**
     * Creates a new ThreadFactory instance, where all threads are created with setDaemon(true) and are assigned to a specific threadgroup
     * with a name prefix and thread count ID
     *
     * @param threadGroup the threadgroup to assign, otherwise null (which will use the current thread's threadGroup)
     */
    public
    DaemonThreadFactory(final ThreadGroup threadGroup) {
        this(threadGroup, null);
    }

    /**
     * Creates a new ThreadFactory instance, where all threads are created with setDaemon(true) and are assigned to a current threads
     * threadgroup and thread count ID
     *
     * @param threadName the name prefix for the thread. null will use the prefix "Thread"
     */
    public
    DaemonThreadFactory(String threadName) {
        this(null, threadName);
    }

    /**
     * Creates a new ThreadFactory instance, where all threads are created with setDaemon(true) and are assigned to a specific threadgroup
     * with a name prefix and thread count ID
     *
     * @param threadGroup the threadgroup to assign, otherwise null (which will use the current thread's threadGroup)
     * @param threadName the name prefix for the thread. null will use the prefix "Thread"
     */
    public
    DaemonThreadFactory(final ThreadGroup threadGroup, String threadName) {
        this.threadGroup = threadGroup;
        this.threadName = threadName;
    }

    @Override
    public
    Thread newThread(Runnable r) {
        Thread thread;
        if (threadGroup != null) {
            thread = new Thread(threadGroup, r);
        }
        else {
            thread = new Thread(r);
        }

        thread.setDaemon(true);

        if (threadName == null) {
            thread.setName("Thread-" + threadCount.getAndIncrement());
        }
        else {
            thread.setName(threadName + "-" + threadCount.getAndIncrement());
        }
        return thread;
    }
}
