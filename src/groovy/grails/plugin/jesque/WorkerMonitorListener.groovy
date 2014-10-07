package grails.plugin.jesque

import net.greghaines.jesque.Job
import net.greghaines.jesque.worker.Worker
import net.greghaines.jesque.worker.WorkerEvent
import net.greghaines.jesque.worker.WorkerListener
import org.apache.commons.logging.LogFactory

/**
 * Takes care of adding a monitoring result to redis when a worker finished executing a job.
 */
class WorkerMonitorListener implements WorkerListener {

    private static final log = LogFactory.getLog('uberall_jesque')

    JesqueService jesqueService
    long start
    long end

    public WorkerMonitorListener(JesqueService jesqueService) {
        this.jesqueService = jesqueService
    }

    @Override
    void onEvent(WorkerEvent event, Worker worker, String queue, Job job, Object runner, Object result, Throwable t) {
        if (event == WorkerEvent.JOB_EXECUTE)
            start = System.currentTimeMillis()
        else if (event == WorkerEvent.JOB_SUCCESS)
            done(job.className, true, job.args)
        else if (event == WorkerEvent.JOB_FAILURE)
            done(job.className, false, job.args)
    }

    private void done(String name, boolean success, def args = null) {
        end = System.currentTimeMillis()
        if (!start || !end) {
            log.warn("done called without start ($start) or end ($end) set, will not monitor this event")
            return
        }

        jesqueService.addMonitorResult(name, start, end, args, success)

        // clearing listener for next job run
        start = 0
        end = 0
    }

}
