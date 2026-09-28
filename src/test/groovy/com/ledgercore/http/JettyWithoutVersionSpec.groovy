package com.ledgercore.http

import org.eclipse.jetty.util.thread.QueuedThreadPool
import spock.lang.Specification

class JettyWithoutVersionSpec extends Specification {

    def factory = new JettyWithoutVersion()

    def "thread settings from spark are kept"() {
        when:
        def pool = factory.create(50, 4, 30_000).threadPool as QueuedThreadPool

        then:
        pool.maxThreads == 50
        pool.minThreads == 4
        pool.idleTimeout == 30_000
    }

    def "missing thread settings get the same defaults as in spark"() {
        when:
        def pool = factory.create(50, 0, 0).threadPool as QueuedThreadPool

        then:
        pool.maxThreads == 50
        pool.minThreads == 8
        pool.idleTimeout == 60_000
    }

    def "without a thread limit jetty uses its own pool of 200 threads"() {
        expect:
        (factory.create(-1, -1, -1).threadPool as QueuedThreadPool).maxThreads == 200
    }
}
