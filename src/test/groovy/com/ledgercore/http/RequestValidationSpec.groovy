package com.ledgercore.http

import com.ledgercore.App
import com.ledgercore.TestClient
import com.ledgercore.TestEnv
import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Specification

import java.nio.charset.StandardCharsets

class RequestValidationSpec extends Specification {

    static final int MAX_BODY = 16 * 1024
    static final String ACCOUNT = '{"ownerName":"Alice","currency":"EUR"}'

    @Shared
    App app

    @Shared
    TestClient client

    @Shared
    JsonSlurper json = new JsonSlurper()

    def setupSpec() {
        app = App.start(TestEnv.appConfig())
        client = new TestClient(app.port())
    }

    def cleanupSpec() {
        app.stop()
    }

    def "a post with Content-Type #contentType is accepted"() {
        expect:
        client.send("POST", "/accounts", ACCOUNT, ["Content-Type": contentType]).statusCode() == 201

        where:
        contentType << ["application/json", "application/json; charset=utf-8", "Application/JSON", "APPLICATION/JSON;charset=UTF-8"]
    }

    def "a post with Content-Type #contentType returns 415"() {
        when:
        def response = client.send("POST", "/accounts", ACCOUNT, ["Content-Type": contentType])
        def problem = json.parseText(response.body())

        then:
        response.statusCode() == 415
        response.headers().firstValue("Content-Type").get().startsWith("application/problem+json")
        problem.title == "Unsupported Media Type"
        problem.detail == "Content-Type must be application/json"

        where:
        contentType << ["text/plain", "application/x-www-form-urlencoded", "application/jsonx", "application/json-patch+json"]
    }

    def "a post without Content-Type returns 415"() {
        expect:
        client.send("POST", "/accounts", ACCOUNT, [:]).statusCode() == 415
    }

    def "a body of exactly 16 KB is accepted"() {
        given:
        def body = padTo(ACCOUNT, MAX_BODY)

        expect:
        body.getBytes(StandardCharsets.UTF_8).length == MAX_BODY
        client.post("/accounts", body).statusCode() == 201
    }

    def "a body one byte over 16 KB returns 413"() {
        when:
        def response = client.post("/accounts", padTo(ACCOUNT, MAX_BODY + 1))
        def problem = json.parseText(response.body())

        then:
        response.statusCode() == 413
        problem.title == "Content Too Large"
        problem.detail == "request body must be at most 16384 bytes"
    }

    def "a large Content-Length is rejected without waiting for the whole body"() {
        when: "10 MB are promised but only 1 byte is sent"
        def status = rawRequest("Content-Length: 10485760\r\n", "x")

        then:
        status == "HTTP/1.1 413 Payload Too Large"
    }

    def "a chunked body without Content-Length stops being read after 16 KB"() {
        when: "the chunk promises 32 KB, so a server that read everything would wait forever"
        def status = rawRequest("Transfer-Encoding: chunked\r\n", unfinishedChunk())

        then:
        status == "HTTP/1.1 413 Payload Too Large"
    }

    def "a chunked body with Transfer-Encoding #value can't bypass the limit either"() {
        when: "jetty still reads these as chunked, but spark only treats the exact word chunked as streaming"
        def status = rawRequest("Transfer-Encoding: " + value + "\r\n", unfinishedChunk())

        then:
        status == "HTTP/1.1 413 Payload Too Large"

        where:
        value << ["Chunked", "gzip, chunked", "identity, chunked"]
    }

    def "an Idempotency-Key with #reason returns 400"() {
        when:
        def response = client.post("/transfers", '{"fromAccountId":1,"toAccountId":2,"amount":1,"currency":"EUR"}', key)

        then:
        response.statusCode() == 400
        json.parseText(response.body()).detail == "Idempotency-Key must contain only visible ASCII characters"

        where:
        reason        | key
        "a space"     | "key with space"
        "a tab"       | "key\twith-tab"
    }

    // pads a json object with spaces before the closing brace, so it stays valid json
    private static String padTo(String json, int size) {
        json.substring(0, json.length() - 1) + " " * (size - json.length()) + "}"
    }

    // raw socket, so the test controls exactly what is sent and can leave a body unfinished.
    // it writes once and never after the server may have answered, so a fast close can't break the test
    private String rawRequest(String headers, String body) {
        def socket = new Socket("localhost", app.port())
        socket.soTimeout = 10_000
        try {
            def request = "POST /accounts HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n" + headers + "\r\n" + body
            socket.outputStream.write(request.getBytes(StandardCharsets.US_ASCII))
            socket.outputStream.flush()
            def reader = new BufferedReader(new InputStreamReader(socket.inputStream, StandardCharsets.US_ASCII))
            return reader.readLine()
        } finally {
            socket.close()
        }
    }

    // one chunk that promises 32 KB, of which only the limit plus one byte is sent:
    // the server needs every byte to reach its limit, so it has nothing unread when it closes
    private static String unfinishedChunk() {
        "8000\r\n" + "x" * (MAX_BODY + 1)
    }
}
