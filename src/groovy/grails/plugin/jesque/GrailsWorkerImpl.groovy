package grails.plugin.jesque

import net.greghaines.jesque.Config
import net.greghaines.jesque.Job
import net.greghaines.jesque.worker.JobFactory
import net.greghaines.jesque.worker.WorkerAware
import net.greghaines.jesque.worker.WorkerImpl
import redis.clients.jedis.Jedis

import static net.greghaines.jesque.utils.ResqueConstants.WORKER
import static net.greghaines.jesque.worker.WorkerEvent.JOB_EXECUTE
import static net.greghaines.jesque.worker.WorkerEvent.JOB_PROCESS

class GrailsWorkerImpl extends WorkerImpl {

    JobThrowableHandler jobThrowableHandler

    public GrailsWorkerImpl(
            final Config config,
            final Collection<String> queues,
            final JobFactory jobFactory) {
        super(config, queues, jobFactory)
    }

    @Override
    protected void process(final Job job, final String curQueue) {
        this.listenerDelegate.fireEvent(JOB_PROCESS, this, curQueue, job, null, null, null)
        renameThread("Processing " + curQueue + " since " + System.currentTimeMillis())
        try {
            def instance = jobFactory.materializeJob(job)
            // allow subclasses to perform actions just before executing the job
            preExecute()
            // we call our own execute implementation as we call perform() on the job (instead of Runnable#call())
            execute(job, curQueue, instance, job.args)
        } catch (Throwable t) {
            failure(t, job, curQueue)
        }
    }

    /**
     * Called when the job we execute throws an exception.
     *
     * @param ex the exception
     * @param job the job that was executed
     * @param curQueue the current queue
     */
    @Override
    protected void failure(final Throwable t, final Job job, final String curQueue) {
        jobThrowableHandler?.onThrowable(t, job, curQueue)
        super.failure(t, job, curQueue)
    }

    protected void execute(final Job job, final String curQueue, final Object instance, final Object[] args) {
        if (instance instanceof WorkerAware) {
            ((WorkerAware) instance).setWorker(this);
        }
        doWithJedis { Jedis jedis ->
            jedis.set(key(WORKER, this.name), statusMsg(curQueue, job))
            try {
                final Object result
                this.listenerDelegate.fireEvent(JOB_EXECUTE, this, curQueue, job, instance, null, null)
                result = instance.perform(*args)
                success(job, instance, result, curQueue)
            } finally {
                jedis.del(key(WORKER, this.name))
            }
        }
    }

    protected void doWithJedis(Closure closure) {
        try {
            closure.call jedis
        } finally {
            jedis.close()
        }
    }

    protected void preExecute() {}

}
