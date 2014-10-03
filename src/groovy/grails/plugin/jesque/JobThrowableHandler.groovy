package grails.plugin.jesque

import net.greghaines.jesque.Job

/**
 * Handles Errors thrown by a jobs perform() method.
 */
interface JobThrowableHandler {

    def onThrowable(Throwable throwable, Job job, String curQueue)

}
