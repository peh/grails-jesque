package grails.plugin.jesque

import net.greghaines.jesque.Job
import net.greghaines.jesque.admin.Admin
import net.greghaines.jesque.admin.AdminClient
import net.greghaines.jesque.admin.AdminImpl
import net.greghaines.jesque.client.Client
import net.greghaines.jesque.meta.WorkerInfo
import net.greghaines.jesque.meta.dao.WorkerInfoDAO
import net.greghaines.jesque.worker.*
import org.codehaus.groovy.grails.support.PersistenceContextInterceptor
import org.joda.time.DateTime
import org.springframework.beans.factory.DisposableBean
import redis.clients.jedis.Jedis

class JesqueService implements DisposableBean {

    static transactional = false

    static final int DEFAULT_WORKER_POOL_SIZE = 3
    public static final String CLASSES_KEY = 'jobs:done:classes'
    public static final String DONE_COUNT_KEY = 'jobs:done:count'

    def grailsApplication
    def jesqueConfig
    def jesqueDelayedJobService
    def scheduledJobDaoService
    def triggerDaoService
    def redisService
    PersistenceContextInterceptor persistenceInterceptor
    Client jesqueClient
    WorkerInfoDAO workerInfoDao
    List<Worker> workers = Collections.synchronizedList([])
    AdminClient jesqueAdminClient

    void enqueue(String queueName, Job job) {
        jesqueClient.enqueue(queueName, job)
    }

    void enqueue(String queueName, String jobName, List args) {
        enqueue(queueName, new Job(jobName, args))
    }

    void enqueue(String queueName, Class jobClazz, List args) {
        enqueue(queueName, jobClazz.simpleName, args)
    }

    void enqueue(String queueName, String jobName, Object... args) {
        enqueue(queueName, new Job(jobName, args))
    }

    void enqueue(String queueName, Class jobClazz, Object... args) {
        enqueue(queueName, jobClazz.simpleName, args)
    }

    void priorityEnqueue(String queueName, Job job) {
        jesqueClient.priorityEnqueue(queueName, job)
    }

    void priorityEnqueue(String queueName, String jobName, def args) {
        priorityEnqueue(queueName, new Job(jobName, args))
    }

    void priorityEnqueue(String queueName, Class jobClazz, def args) {
        priorityEnqueue(queueName, jobClazz.simpleName, args)
    }

    void enqueueAt(DateTime dateTime, String queueName, Job job) {
        jesqueDelayedJobService.enqueueAt(dateTime, queueName, job)
    }

    void enqueueAt(DateTime dateTime, String queueName, String jobName, Object... args) {
        enqueueAt(dateTime, queueName, new Job(jobName, args))
    }

    void enqueueAt(DateTime dateTime, String queueName, Class jobClazz, Object... args) {
        enqueueAt(dateTime, queueName, jobClazz.simpleName, args)
    }

    void enqueueAt(DateTime dateTime, String queueName, String jobName, List args) {
        enqueueAt(dateTime, queueName, new Job(jobName, args))
    }

    void enqueueAt(DateTime dateTime, String queueName, Class jobClazz, List args) {
        enqueueAt(dateTime, queueName, jobClazz.simpleName, args)
    }


    void enqueueIn(Integer millisecondDelay, String queueName, Job job) {
        enqueueAt(new DateTime().plusMillis(millisecondDelay), queueName, job)
    }

    void enqueueIn(Integer millisecondDelay, String queueName, String jobName, Object... args) {
        enqueueIn(millisecondDelay, queueName, new Job(jobName, args))
    }

    void enqueueIn(Integer millisecondDelay, String queueName, Class jobClazz, Object... args) {
        enqueueIn(millisecondDelay, queueName, jobClazz.simpleName, args)
    }

    void enqueueIn(Integer millisecondDelay, String queueName, String jobName, List args) {
        enqueueIn(millisecondDelay, queueName, new Job(jobName, args))
    }

    void enqueueIn(Integer millisecondDelay, String queueName, Class jobClazz, List args) {
        enqueueIn(millisecondDelay, queueName, jobClazz.simpleName, args)
    }


    Worker startWorker(String queueName, JobFactory jobFactory = new GrailsJobFactory(grailsApplication), ExceptionHandler exceptionHandler = null,
                       boolean paused = false) {
        startWorker([queueName], jobFactory, exceptionHandler, paused)
    }

    Worker startWorker(List<String> queues, JobFactory jobFactory = new GrailsJobFactory(grailsApplication), ExceptionHandler exceptionHandler = null,
                       boolean paused = false) {
        log.info "Starting worker processing queueus: ${queues}"

        def customWorkerClass = grailsApplication.config.grails.jesque.custom.worker.clazz
        Worker worker
        if (customWorkerClass && customWorkerClass in GrailsWorkerImpl) {
            worker = customWorkerClass.newInstance(jesqueConfig, queues, jobFactory)
        } else {
            if (customWorkerClass)
                log.warn('The specified custom worker class does not extend GrailsWorkerImpl. Ignoring it')
            worker = new GrailsWorkerImpl(jesqueConfig, queues, jobFactory)
        }

        def customListenerClass = grailsApplication.config.grails.jesque.custom.listener.clazz
        if (customListenerClass && customListenerClass in WorkerListener) {
            worker.workerEventEmitter.addListener(customListenerClass.newInstance() as WorkerListener)
        } else if (customListenerClass) {
            log.warn('The specified custom listener class does not implement WorkerListener. Ignoring it')
        }

        def customJobThrowableHandler = grailsApplication.config.grails.jesque.custom.jobThrowableHandler.clazz
        if (customJobThrowableHandler && customJobThrowableHandler in JobThrowableHandler) {
            worker.jobThrowableHandler = customJobThrowableHandler.newInstance() as JobThrowableHandler
        } else if (customJobThrowableHandler) {
            log.warn('The specified custom job throwable handler class does not implement JobThrowableHandler. Ignoring it')
        }

        if (exceptionHandler)
            worker.exceptionHandler = exceptionHandler

        if (paused) {
            worker.togglePause(paused)
        }

        workers.add(worker)

        // create an Admin for this worker (makes it possible to administer across a cluster)
        Admin admin = new AdminImpl(jesqueConfig)
        admin.setWorker(worker)

        if (!grailsApplication.config.grails.jesque.skipPersistence) {
            log.debug("Enabling Persistence for all Jobs")
            def autoFlush = grailsApplication.config.grails.jesque.autoFlush ?: true
            def workerPersistenceListener = new WorkerPersistenceListener(persistenceInterceptor, autoFlush)
            worker.workerEventEmitter.addListener(workerPersistenceListener, WorkerEvent.JOB_EXECUTE, WorkerEvent.JOB_SUCCESS, WorkerEvent.JOB_FAILURE)
        }

        boolean monitoring = grailsApplication.config.grails.jesque.monitoring as boolean
        if (monitoring) {
            log.debug("Enabling Monitoring for all Jobs")
            def workerMonitorListener = new WorkerMonitorListener(this)
            worker.workerEventEmitter.addListener(workerMonitorListener, WorkerEvent.JOB_EXECUTE, WorkerEvent.JOB_SUCCESS, WorkerEvent.JOB_FAILURE)
        }

        def workerLifeCycleListener = new WorkerLifecycleListener(this)
        worker.workerEventEmitter.addListener(workerLifeCycleListener, WorkerEvent.WORKER_STOP)

        def workerThread = new Thread(worker)
        workerThread.start()

        def adminThread = new Thread(admin)
        adminThread.start()

        worker
    }

    void stopAllWorkers() {
        log.info "Stopping ${workers.size()} jesque workers"

        List<Worker> workersToRemove = workers.collect { it }
        workersToRemove.each { Worker worker ->
            try {
                log.debug "Stopping worker processing queues: ${worker.queues}"
                worker.end(true)
                worker.join(5000)
            } catch (Exception exception) {
                log.error "Exception ending jesque worker", exception
            }
        }
    }

    void withWorker(String queueName, JobFactory jobFactory, Closure closure) {
        def worker = startWorker(queueName, jobFactory)
        try {
            closure()
        } finally {
            worker.end(true)
        }
    }

    void startWorkersFromConfig(ConfigObject jesqueConfigMap) {
        def startPaused = jesqueConfigMap.startPaused as boolean ?: false

        jesqueConfigMap.workers.each { String workerPoolName, value ->
            int workers = value.workers ? value.workers.toInteger() : DEFAULT_WORKER_POOL_SIZE

            if (workers < 1) return

            log.info "Starting $workers workers for pool $workerPoolName"

            def queueNames = value.queueNames
            if (queueNames instanceof Closure)
                queueNames = queueNames.call()
            JobFactory jobFactory = new GrailsJobFactory(grailsApplication)

            workers.times {
                startWorker(queueNames, jobFactory, null, startPaused)
            }
        }
    }

    void pruneWorkers() {
        def hostName = InetAddress.localHost.hostName
        workerInfoDao.allWorkers?.each { WorkerInfo workerInfo ->
            if (workerInfo.host == hostName) {
                log.debug "Removing stale worker $workerInfo.name"
                workerInfoDao.removeWorker(workerInfo.name)
            }
        }
    }

    public void removeWorkerFromLifecycleTracking(Worker worker) {
        log.debug "Removing worker ${worker.name} from lifecycle tracking"
        workers.remove(worker)
    }

    void destroy() throws Exception {
        this.stopAllWorkers()
    }

    void pauseAllWorkersOnThisNode() {
        log.info "Pausing all ${workers.size()} jesque workers on this node"

        List<Worker> workersToPause = workers.collect { it }
        workersToPause.each { Worker worker ->
            log.debug "Pausing worker processing queues: ${worker.queues}"
            worker.togglePause(true)
        }
    }

    void resumeAllWorkersOnThisNode() {
        log.info "Resuming all ${workers.size()} jesque workers on this node"

        List<Worker> workersToPause = workers.collect { it }
        workersToPause.each { Worker worker ->
            log.debug "Resuming worker processing queues: ${worker.queues}"
            worker.togglePause(false)
        }
    }

    void pauseAllWorkersInCluster() {
        log.debug "Pausing all workers in the cluster"
        jesqueAdminClient.togglePausedWorkers(true)
    }

    void resumeAllWorkersInCluster() {
        log.debug "Resuming all workers in the cluster"
        jesqueAdminClient.togglePausedWorkers(false)
    }

    void shutdownAllWorkersInCluster() {
        log.debug "Shutting down all workers in the cluster"
        jesqueAdminClient.shutdownWorkers(true)
    }

    boolean areAllWorkersInClusterPaused() {
        return workerInfoDao.getActiveWorkerCount() == 0
    }

    void pruneScheduledJobs() {
        log.debug "Pruning schedules jobs"
        // first, delete all triggers
        triggerDaoService.deleteAll()
        // then delete the scheduled jobs
        scheduledJobDaoService.deleteAll()
    }

    /**
     * Adds a monitor result to redis.
     *
     * @param name the job name
     * @param start the time when the execution of the job started
     * @param end the time when the execution of the job ended
     * @param args the job arguments
     * @param success true if the job execution was successful, false otherwise
     * @return
     */
    void addMonitorResult(def name, long start, long end, def args = null, boolean success = true) {
        int ttl = grailsApplication.config.grails.jesque.monitoringTTL ?: -1
        def obj = [:]
        obj.start = start
        obj.end = end
        obj.runtime = end - start
        obj.args = args
        obj.name = name
        obj.success = success
        redisService.withRedis { Jedis jedis ->
            long next = jedis.incr(DONE_COUNT_KEY)
            String jobKey = getDoneKey(next)
            obj.id = next
            obj.each { key, value ->
                jedis.hset(jobKey, key.toString(), value.toString())
            }
            if (ttl > 0) {
                jedis.expire(jobKey, ttl)
            }
            jedis.sadd(CLASSES_KEY, "$name")
            jedis.lpush(getClassesDoneKey(name), "$next")
        }
    }

    static String getClassesDoneKey(String name) {
        "jobs:done:classes:$name"
    }

    static String getDoneKey(long id) {
        "jobs:done:${id}"
    }

}
