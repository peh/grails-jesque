package grails.plugin.jesque

import net.greghaines.jesque.Job
import net.greghaines.jesque.worker.Worker
import net.greghaines.jesque.worker.WorkerEvent
import net.greghaines.jesque.worker.WorkerListener
import org.apache.commons.logging.LogFactory
import org.codehaus.groovy.grails.support.PersistenceContextInterceptor

class WorkerPersistenceListener implements WorkerListener {

    PersistenceContextInterceptor persistenceInterceptor
    boolean initiatied = false
    boolean autoFlush
    static log = LogFactory.getLog(WorkerPersistenceListener)

    WorkerPersistenceListener(PersistenceContextInterceptor persistenceInterceptor, boolean autoFlush) {
        this.persistenceInterceptor = persistenceInterceptor
        this.autoFlush = autoFlush
    }

    void onEvent(WorkerEvent workerEvent, Worker worker, String s, Job job, Object o, Object o1, Exception e) {
        log.debug("Processing worker event ${workerEvent.name()}")
        if (workerEvent == WorkerEvent.JOB_EXECUTE) {
            initiatied = bindSession()
        } else if (workerEvent in [WorkerEvent.JOB_SUCCESS, WorkerEvent.JOB_FAILURE]) {
            unbindSession()
        }
    }

    private boolean bindSession() {
        if (persistenceInterceptor == null)
            throw new IllegalStateException("No persistenceInterceptor found");

        log.debug("Binding session")

        if (!initiatied) {
            persistenceInterceptor.init()
        }
        true
    }

    private void unbindSession() {
        if (initiatied) {
            if (autoFlush) {
                persistenceInterceptor.flush()
            }
            persistenceInterceptor.destroy()
            initiatied = false
        } else {
            log.debug("persistenceInterceptor has never been initialised")
        }
    }
}
