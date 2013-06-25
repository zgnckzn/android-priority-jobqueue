package com.path.android.jobqueue;

import android.content.Context;
import com.path.android.jobqueue.config.Configuration;
import com.path.android.jobqueue.di.DependencyInjector;
import com.path.android.jobqueue.executor.JobConsumerExecutor;
import com.path.android.jobqueue.log.JqLog;
import com.path.android.jobqueue.network.NetworkEventProvider;
import com.path.android.jobqueue.network.NetworkUtil;
import com.path.android.jobqueue.nonPersistentQueue.NonPersistentPriorityQueue;
import com.path.android.jobqueue.persistentQueue.sqlite.SqliteJobQueue;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * a JobManager that supports;
 * -> Persistent / Non Persistent Jobs
 * -> Job Priority
 * -> Running Jobs in Parallel
 * -> Stats like waiting Job Count
 */
public class JobManager implements NetworkEventProvider.Listener {
    public static final long NS_PER_MS = 1000000;
    public static final long NOT_RUNNING_SESSION_ID = Long.MIN_VALUE;
    public static final long NOT_DELAYED_JOB_DELAY = Long.MIN_VALUE;
    private final long sessionId;
    private int maxConsumerCount;
    private JobQueue persistentJobQueue;
    private JobQueue nonPersistentJobQueue;
    private boolean running;
    private NetworkUtil networkUtil;
    private final Context appContext;
    private DependencyInjector dependencyInjector;
    private final List<String> runningJobGroups;
    private final JobConsumerExecutor jobConsumerExecutor;
    private final Object newJobListeners = new Object();

    /**
     * Default constructor that will create a JobManager with 1 {@link SqliteJobQueue} and 1 {@link NonPersistentPriorityQueue}
     * @param context
     */
    public JobManager(Context context) {
        this(context, "default");
    }


    /**
     * Default constructor that will create a JobManager with a default {@link Configuration}
     * @param context application context
     * @param id an id that is unique to this JobManager
     */
    public JobManager(Context context, String id) {
        this(context, createDefaultConfiguration().withId(id));
    }

    /**
     *
     * @param context used to acquire ApplicationContext
     * @param config
     */
    public JobManager(Context context, Configuration config) {
        appContext = context.getApplicationContext();
        maxConsumerCount = config.getMaxConsumerCount();
        running = true;
        runningJobGroups = new ArrayList<String>();//no reason to use a hashMap, this list is very small
        sessionId = System.nanoTime();
        //by providing an array blocking queue w/ maxConsumerCount size, we let it queue new items while some are about
        //to die.
        this.persistentJobQueue = config.getQueueFactory().createPersistentQueue(context, sessionId, config.getId());
        this.nonPersistentJobQueue = config.getQueueFactory().createNonPersistent(context, sessionId, config.getId());
        networkUtil = config.getNetworkUtil();
        dependencyInjector = config.getDependencyInjector();
        if(networkUtil instanceof NetworkEventProvider) {
            ((NetworkEventProvider) networkUtil).setListener(this);
        }
        //is important to initialize consumers last so that they can start running
        jobConsumerExecutor = new JobConsumerExecutor(maxConsumerCount, consumerContract);
        start();
    }

    public static Configuration createDefaultConfiguration() {
        return new Configuration()
                .withDefaultQueueFactory()
                .withDefaultNetworkUtil();
    }

    /**
     * Sets the max # of consumers. Existing consumers will NOT be killed until queue is empty.
     * @param maxConsumerCount
     */
    public void setMaxConsumerCount(int maxConsumerCount) {
        this.maxConsumerCount = maxConsumerCount;
    }


    /**
     * Stops consuming jobs. Currently running jobs will be finished but no new jobs will be run.
     */
    public void stop() {
        running = false;
    }

    /**
     * restarts the JobManager. Will create a new consumer if necessary.
     */
    public void start() {
        if(running) {
            return;
        }
        running = true;
        notifyJobConsumer();
    }

    /**
     * returns the # of jobs that are waiting to be executed.
     * This might be a good place to decide whether you should wake your app up on boot etc. to complete pending jobs.
     * @return
     */
    public int count() {
        return nonPersistentJobQueue.count() + persistentJobQueue.count();
    }

    /**
     * Adds a job with given priority and returns the JobId.
     * @param priority Higher runs first
     * @param baseJob The actual job to run
     * @return
     */
    public long addJob(int priority, BaseJob baseJob) {
        return addJob(priority, 0, baseJob);
    }

    /**
     * Adds a job with given priority and returns the JobId.
     * @param priority Higher runs first
     * @param delay number of milliseconds that this job should be delayed
     * @param baseJob The actual job to run
     * @return
     */
    public long addJob(int priority, long delay, BaseJob baseJob) {
        JobHolder jobHolder = new JobHolder(priority, baseJob, delay > 0 ? System.nanoTime() + delay * NS_PER_MS : NOT_DELAYED_JOB_DELAY, NOT_RUNNING_SESSION_ID);
        long id;
        if (baseJob.shouldPersist()) {
            synchronized (persistentJobQueue) {
                id = persistentJobQueue.insert(jobHolder);
            }
        } else {
            synchronized (nonPersistentJobQueue) {
                id = nonPersistentJobQueue.insert(jobHolder);
            }
        }
        if(JqLog.isDebugEnabled()) {
            JqLog.d("added job id: %d class: %s priority: %d delay: %d group : %s", id, baseJob.getClass().getSimpleName(), priority, delay, baseJob.getRunGroupId());
        }
        if(dependencyInjector != null) {
            //inject members b4 calling onAdded
            dependencyInjector.inject(baseJob);
        }
        jobHolder.getBaseJob().onAdded();
        notifyJobConsumer();
        return id;
    }

    private void ensureConsumerWhenNeeded(Boolean hasNetwork) {
        if(hasNetwork == null) {
            //if network util can inform us when network is recovered, we we'll check only next job that does not
            //require network. if it does not know how to inform us, we have to keep a busy loop.
            hasNetwork = networkUtil instanceof NetworkEventProvider ? hasNetwork() : true;
        }
        //this method is called when there are jobs but job consumer was not given any
        //this may happen in a race condition or when the latest job is a delayed job
        Long nextRunNs;
        synchronized (nonPersistentJobQueue) {
            nextRunNs = nonPersistentJobQueue.getNextJobDelayUntilNs(hasNetwork);
        }
        if(nextRunNs != null && nextRunNs <= System.nanoTime()) {
            notifyJobConsumer();
            return;
        }
        Long persistedJobRunNs;
        synchronized (persistentJobQueue) {
            persistedJobRunNs = persistentJobQueue.getNextJobDelayUntilNs(hasNetwork);
        }
        if(persistedJobRunNs != null) {
            if(nextRunNs == null) {
                nextRunNs = persistedJobRunNs;
            } else if(persistedJobRunNs < nextRunNs) {
                nextRunNs = persistedJobRunNs;
            }
        }
        if(nextRunNs != null) {
            if(nextRunNs <= System.nanoTime()) {
                notifyJobConsumer();
            } else {
                ensureConsumerOnTime(nextRunNs - System.nanoTime());
            }
        }
    }

    private void notifyJobConsumer() {
        synchronized (newJobListeners) {
            newJobListeners.notifyAll();
        }
        jobConsumerExecutor.considerAddingConsumer();
    }

    private void ensureConsumerOnTime(long waitNs) {
        long delay = (long)Math.ceil((double)(waitNs) / NS_PER_MS);
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                notifyJobConsumer();
            }
        }, delay);
    }

    private boolean hasNetwork() {
        return networkUtil == null ? true : networkUtil.isConnected(appContext);
    }

    private JobHolder getNextJob() {
        return getNextJob(false);
    }

    private synchronized JobHolder getNextJob(boolean nonPersistentOnly) {
        boolean haveNetwork = hasNetwork();
        JobHolder jobHolder;
        boolean persistent = false;
        synchronized (nonPersistentJobQueue) {
            jobHolder = nonPersistentJobQueue.nextJobAndIncRunCount(haveNetwork, runningJobGroups);
        }
        if (jobHolder == null && nonPersistentOnly == false) {
            //go to disk, there aren't any non-persistent jobs
            synchronized (persistentJobQueue) {
                jobHolder = persistentJobQueue.nextJobAndIncRunCount(haveNetwork, runningJobGroups);
                persistent = true;
            }
        }
        if(persistent && jobHolder != null && dependencyInjector != null) {
            dependencyInjector.inject(jobHolder.getBaseJob());
        }
        if(jobHolder != null && jobHolder.getGroupId() != null) {
            runningJobGroups.add(jobHolder.getGroupId());
        }
        return jobHolder;
    }

    private synchronized void reAddJob(JobHolder jobHolder) {
        if (jobHolder.getBaseJob().shouldPersist()) {
            synchronized (persistentJobQueue) {
                persistentJobQueue.insertOrReplace(jobHolder);
            }
        } else {
            synchronized (nonPersistentJobQueue) {
                nonPersistentJobQueue.insertOrReplace(jobHolder);
            }
        }
        if(jobHolder.getGroupId() != null) {
            runningJobGroups.remove(jobHolder.getGroupId());
        }
        notifyJobConsumer();
    }

    private synchronized void removeJob(JobHolder jobHolder) {
        if (jobHolder.getBaseJob().shouldPersist()) {
            synchronized (persistentJobQueue) {
                persistentJobQueue.remove(jobHolder);
            }
        } else {
            synchronized (nonPersistentJobQueue) {
                nonPersistentJobQueue.remove(jobHolder);
            }
        }
        if(jobHolder.getGroupId() != null) {
            runningJobGroups.remove(jobHolder.getGroupId());
        }
    }

    public void clear() {
        synchronized (nonPersistentJobQueue) {
            nonPersistentJobQueue.clear();
        }
        synchronized (persistentJobQueue) {
            persistentJobQueue.clear();
        }
        runningJobGroups.clear();
    }

    /**
     * if {@link NetworkUtil} implements {@link NetworkEventProvider}, this method is called when network is recovered
     * @param isConnected
     */
    @Override
    public void onNetworkChange(boolean isConnected) {
        ensureConsumerWhenNeeded(isConnected);
    }

    private final JobConsumerExecutor.Contract consumerContract = new JobConsumerExecutor.Contract() {
        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public boolean canDetectNetworkChanges() {
            return networkUtil instanceof NetworkEventProvider;
        }

        @Override
        public void insertOrReplace(JobHolder jobHolder) {
            reAddJob(jobHolder);
        }

        @Override
        public void removeJob(JobHolder jobHolder) {
            JobManager.this.removeJob(jobHolder);
        }

        @Override
        public JobHolder getNextJob(int wait, TimeUnit waitDuration) {
            long start = System.nanoTime();
            long remainingWait = waitDuration.toNanos(wait);
            long waitUntil = remainingWait + start;
            JobHolder nextJob = null;
            while (nextJob == null && waitUntil > System.nanoTime()) {
                 nextJob = JobManager.this.getNextJob();
                if(nextJob == null) {
                    long remaining = waitUntil - System.nanoTime();
                    if(remaining > 0) {
                        synchronized (newJobListeners) {
                            try {
                                //if we can't detect network changes, we won't be notified.
                                //to avoid waiting up to give time, wait in chunks of 500 ms max
                                long maxWait = TimeUnit.NANOSECONDS.toMillis(remaining);
                                if(canDetectNetworkChanges()) {
                                    newJobListeners.wait(maxWait);
                                } else {
                                    newJobListeners.wait(Math.min(500, maxWait));
                                }

                            } catch (InterruptedException e) {
                                //
                            }
                        }
                    }
                }
            }
            return nextJob;
        }

        @Override
        public int countRemainingJobs() {
            return count();
        }
    };

    /**
     * Default implementation of QueueFactory that creates one {@link SqliteJobQueue} and one {@link NonPersistentPriorityQueue}
     */
    public static class DefaultQueueFactory implements QueueFactory {
        @Override
        public JobQueue createPersistentQueue(Context context, Long sessionId, String id) {
            return new SqliteJobQueue(context, sessionId, id);
        }
        @Override
        public JobQueue createNonPersistent(Context context, Long sessionId, String id) {
            return new NonPersistentPriorityQueue(sessionId, id);
        }
    }

    /**
     * Interface to supply custom {@link JobQueue}s for JobManager
     */
    public static interface QueueFactory {
        public JobQueue createPersistentQueue(Context context, Long sessionId, String id);
        public JobQueue createNonPersistent(Context context, Long sessionId, String id);
    }
}
