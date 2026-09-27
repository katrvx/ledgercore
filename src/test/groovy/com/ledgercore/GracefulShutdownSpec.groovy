package com.ledgercore

import spock.lang.Specification

import java.util.concurrent.TimeUnit

// runs the real main method in its own process, reading its settings from environment variables like in a container
class GracefulShutdownSpec extends Specification {

    def "the app starts from environment variables without REDIS_URL and stops cleanly on SIGTERM"() {
        given:
        def port = freePort()
        def output = File.createTempFile("ledgercore-shutdown", ".log")
        def java = System.getProperty("java.home") + "/bin/java"
        def builder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.ledgercore.App")
        def env = builder.environment()
        env.remove("REDIS_URL")
        env.put("PORT", port.toString())
        env.put("DATABASE_URL", TestEnv.POSTGRES.jdbcUrl)
        env.put("DATABASE_USER", TestEnv.POSTGRES.username)
        env.put("DATABASE_PASSWORD", TestEnv.POSTGRES.password)
        builder.redirectErrorStream(true).redirectOutput(output)
        def process = builder.start()
        waitForReady(port)

        when: "destroy() sends SIGTERM, what cloud run and kubernetes send before they kill a container"
        process.destroy()
        def exited = process.waitFor(15, TimeUnit.SECONDS)
        def log = output.text

        then:
        exited
        log.contains("shutting down")
        log.contains("stopped")
        log.indexOf("shutting down") < log.indexOf("stopped")
        portIsFree(port)

        cleanup:
        process?.destroyForcibly()
        output?.delete()
    }

    private static int freePort() {
        new ServerSocket(0).withCloseable { it.localPort }
    }

    private static boolean portIsFree(int port) {
        try {
            new ServerSocket(port).close()
            return true
        } catch (IOException ignored) {
            return false
        }
    }

    private static void waitForReady(int port) {
        def client = new TestClient(port)
        for (int i = 0; i < 100; i++) {
            try {
                if (client.get("/ready").statusCode() == 200) {
                    return
                }
            } catch (IOException ignored) {
                // not listening yet
            }
            Thread.sleep(200)
        }
        throw new AssertionError("the app did not become ready in 20 seconds")
    }
}
