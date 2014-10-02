package grails.plugin.jesque

import net.greghaines.jesque.Job
import net.greghaines.jesque.worker.JobFactory
import org.codehaus.groovy.grails.commons.GrailsApplication

/**
 * Job Factory that knows how to materialize grails jobs.
 */
class GrailsJobFactory implements JobFactory {

    GrailsApplication grailsApplication

    public GrailsJobFactory(final GrailsApplication grailsApplication) {
        this.grailsApplication = grailsApplication
    }

    @Override
    public Object materializeJob(final Job job) throws Exception {
        Class jobClass = QueueConfiguration.jobTypes.get(job.className)
        return grailsApplication.mainContext.getBean(jobClass.canonicalName)
    }

}
