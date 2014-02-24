package grails.plugin.jesque

import redis.clients.jedis.Jedis

class TriggerDaoService {

    def redisService

    void save( Trigger trigger) {
        redisService.withRedis { Jedis redis ->
            save( redis, trigger )
        }
    }

    void save( Jedis redis, Trigger trigger) {
        redis.hmset(trigger.redisKey, trigger.toRedisHash())
    }

    void deleteAll() {
        redisService.withRedis { Jedis redis ->
            def triggers = redis.keys("${Trigger.REDIS_PREFIX}:*")
            triggers.each { trigger ->
                redis.del(trigger)
            }
        }
    }

    Trigger findByJobName(String jobName) {
        redisService.withRedis { Jedis redis ->
            findByJobName(redis, jobName)
        } as Trigger
    }

    Trigger findByJobName(Jedis redis, String jobName) {
        Trigger.fromRedisHash( redis.hgetAll( Trigger.getRedisKeyForJobName(jobName) ) )
    }

}
