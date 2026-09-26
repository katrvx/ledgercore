package com.ledgercore.config

import ch.qos.logback.classic.Level
import com.ledgercore.LogCapture
import io.lettuce.core.RedisException
import org.testcontainers.containers.GenericContainer
import spock.lang.Specification

class RedisStateLoggingSpec extends Specification {

    def "redis going down and coming back is logged once each, not on every command"() {
        given: "a redis of its own, paused and unpaused so it keeps its port"
        def container = new GenericContainer("redis:7-alpine").withExposedPorts(6379)
        container.start()
        def redis = new Redis("redis://${container.host}:${container.getMappedPort(6379)}")
        def logs = new LogCapture()
        def docker = container.dockerClient

        when:
        redis.call { it.ping() }
        docker.pauseContainerCmd(container.containerId).exec()
        def failures = (1..3).count { tryPing(redis) == null }
        docker.unpauseContainerCmd(container.containerId).exec()
        def afterUnpause = [tryPing(redis), tryPing(redis)]
        def redisLogs = logs.events(Redis.name)

        then:
        failures == 3
        afterUnpause == ["PONG", "PONG"]
        redisLogs*.level == [Level.WARN, Level.INFO]
        redisLogs[0].formattedMessage.startsWith("redis is down, using the database instead: ")
        redisLogs[1].formattedMessage == "redis is back"

        cleanup:
        logs?.close()
        redis?.close()
        container?.stop()
    }

    def "a failed first connection is logged once"() {
        given:
        def redis = new Redis("redis://localhost:1")
        def logs = new LogCapture()

        when:
        3.times { tryPing(redis) }

        then:
        logs.events(Redis.name)*.level == [Level.WARN]

        cleanup:
        logs.close()
        redis.close()
    }

    private static String tryPing(Redis redis) {
        try {
            return redis.call { it.ping() }
        } catch (RedisException ignored) {
            return null
        }
    }
}
