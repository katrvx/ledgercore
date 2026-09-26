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
        def request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .build()
        client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    // every post gets a fresh idempotency key unless the test passes its own
    HttpResponse<String> post(String path, String body) {
        post(path, body, UUID.randomUUID().toString())
    }

    HttpResponse<String> post(String path, String body, String idempotencyKey) {
        def builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey)
        }
        client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
