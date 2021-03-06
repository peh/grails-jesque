grails{
    jesque {
        enabled = true
        pruneWorkersOnStartup = true
        createWorkersOnStartup = true
        schedulerThreadActive = true
        delayedJobThreadActive = true
        startPaused = false
        pruneScheduledJobsOnStartup = false
    }
}

environments {
    test {
        grails{
            jesque {
                pruneWorkersOnStartup = false
                schedulerThreadActive = false
            }
        }
    }
}