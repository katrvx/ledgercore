package com.ledgercore

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class TestClient {

    private final HttpClient client = HttpClient.newHttpClient()
    private final String baseUrl

    TestClient(int port) {
        baseUrl = "http://localhost:$port"
    }

    HttpResponse<String> get(String path) {
        def request = HttpRequest.newBuilder(URI.create(baseUrl + path)).build()
        client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    HttpResponse<String> post(String path, String body) {
        def request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        client.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
