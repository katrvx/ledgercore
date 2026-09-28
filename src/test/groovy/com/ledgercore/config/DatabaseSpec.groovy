package com.ledgercore.config

import com.ledgercore.TestEnv
import spock.lang.Specification

class DatabaseSpec extends Specification {

    def "a request waits at most 5 seconds for a pool connection, so /ready answers before a probe gives up"() {
        given:
        def dataSource = Database.connect(TestEnv.appConfig())

        expect:
        dataSource.connectionTimeout == 5_000

        cleanup:
        dataSource.close()
    }

    def "the pool size comes from the config"() {
        given:
        def config = TestEnv.appConfig()
        def smaller = new AppConfig(config.port(), config.databaseUrl(), config.databaseUser(), config.databasePassword(),
                3, config.redisUrl(), config.redisTimeout(), config.fraud())

        when:
        def dataSource = Database.connect(smaller)

        then:
        dataSource.maximumPoolSize == 3

        cleanup:
        dataSource?.close()
    }
}
