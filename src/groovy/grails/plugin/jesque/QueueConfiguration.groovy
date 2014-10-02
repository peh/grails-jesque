package grails.plugin.jesque

import groovy.util.logging.Log4j
import org.apache.commons.lang.WordUtils

/**
 * Queue configuration holder.
 * Has all the information you need to build a valid jesque configuration.
 */
@Log4j
class QueueConfiguration {

    /**
     * the map of queue names as values and the class the queue should handle as the key
     */
    private static final Map<Class, String> queues = [:]

    /**
     * the map of Job classes jesque is allowed to work on. key is class.simpleName and value is class.
     */
    private static final Map<String, Class> jobTypes = [:]

    /**
     * Adds a job class to the queue configuration.
     *
     * @param jobClass the job class
     */
    static void addJob(Class jobClass) {
        String queueName = jobClass.declaredFields.find { it.name == 'queue' } ?
                jobClass.queue :
                "${WordUtils.uncapitalize(jobClass.simpleName)}Queue"
        queues << [(jobClass): queueName]
        jobTypes << [(jobClass.simpleName): jobClass]
        log.info("added job $jobClass.simpleName to queue $queueName")
    }

    /**
     * Returns the queue name for the given job class.
     *
     * @param clazz the job class
     * @return the queue name
     */
    static String getQueueName(Class clazz) {
        queues.get(clazz)
    }

    /**
     * Returns all queue names.
     *
     * @return all queue names as a List
     */
    static List<String> getQueueNames() {
        queues.values() as List
    }

    /**
     * Map of job names and there job class as values.
     *
     * @return the map with the job types
     */
    static Map<String, Class> getJobTypes() {
        jobTypes
    }

    /**
     * Returns all job classes.
     *
     * @return all job classes
     */
    static List<Class> getJobClasses() {
        jobTypes.values() as List
    }

    /**
     * Returns all queue names with the given Jobs queues as the first elements in list (in the given order)
     * This should be used to prioritize specific jobs over the rest.
     *
     * @param prioClasses collection of JobClasses that should be the first elements in the resulting list
     * @return all queue names with priorization
     */
    static List getQueueNamesWithPrioritization(Collection<Class> prioClasses) {
        List<String> result = []
        prioClasses.each {
            result.add(getQueueName(it))
        }
        result.addAll(queues.findAll { key, val -> !(key in prioClasses) }.values())
        result
    }

}
