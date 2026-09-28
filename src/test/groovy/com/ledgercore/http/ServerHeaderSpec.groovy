package com.ledgercore.http

import com.ledgercore.App
import com.ledgercore.TestClient
import com.ledgercore.TestEnv
import spock.lang.Shared
import spock.lang.Specification

import java.nio.charset.StandardCharsets

class ServerHeaderSpec extends Specification {

    @Shared
    App app

    @Shared
    TestClient client

    def setupSpec() {
        app = App.start(TestEnv.appConfig())
        client = new TestClient(app.port())
    }

    def cleanupSpec() {
        app.stop()
    }

    def "a response to #path does not name the server"() {
        when:
        def response = client.get(path)

        then:
        response.statusCode() == status
        !response.headers().firstValue("Server").isPresent()

        where:
        path                  | status
        "/health"             | 200
        "/accounts/999999999" | 404
        "/nothing"            | 404
    }

    def "an answer that jetty writes by itself does not name the server either"() {
        when: "the first line is not http, so jetty answers before the app sees the request"
        def answer = sendRaw("this is not http\r\n\r\n")

        then:
        answer.startsWith("HTTP/1.1 400")
        !answer.toLowerCase().contains("jetty")
    }

    private String sendRaw(String request) {
        def socket = new Socket("localhost", app.port())
        try {
            socket.soTimeout = 5000
            socket.outputStream.write(request.getBytes(StandardCharsets.US_ASCII))
            socket.outputStream.flush()
            return new String(socket.inputStream.readAllBytes(), StandardCharsets.US_ASCII)
        } finally {
            socket.close()
        }
    }
}
