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

    HttpResponse<String> post(String path, String body) {
        def request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        client.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
