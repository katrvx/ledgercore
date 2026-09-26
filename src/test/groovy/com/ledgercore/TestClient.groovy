package com.ledgercore

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class TestClient {

    private final HttpClient client = HttpClient.newHttpClient()
    private final String baseUrl

    TestClient(int port) {
        baseUrl = "http://localhost:$port"
    }

    HttpResponse<String> get(String path) {
        get(path, [:])
    }

    HttpResponse<String> get(String path, Map<String, String> headers) {
        send("GET", path, null, headers)
    }

    // every post gets a fresh idempotency key unless the test passes its own
    HttpResponse<String> post(String path, String body) {
        post(path, body, UUID.randomUUID().toString())
    }

    HttpResponse<String> post(String path, String body, String idempotencyKey) {
        def headers = ["Content-Type": "application/json"]
        if (idempotencyKey != null) {
            headers["Idempotency-Key"] = idempotencyKey
        }
        send("POST", path, body, headers)
    }

    HttpResponse<String> send(String method, String path, String body, Map<String, String> headers) {
        def builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
        headers.each { name, value -> builder.header(name, value) }
        client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
