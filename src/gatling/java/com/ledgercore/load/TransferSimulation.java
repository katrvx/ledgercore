package com.ledgercore.load;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

// transfers between random pairs of funded accounts at a fixed arrival rate
public class TransferSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl");
    private static final int ACCOUNTS = Integer.parseInt(System.getProperty("accounts"));
    private static final int RATE = Integer.parseInt(System.getProperty("rate"));
    private static final int SECONDS = Integer.parseInt(System.getProperty("seconds"));
    // enough money that no transfer in a run can fail with insufficient funds
    private static final long DEPOSIT = 100_000_000;

    private final HttpClient setupClient = HttpClient.newHttpClient();

    public TransferSimulation() {
        // accounts are created with a plain http client, so the setup never shows up in the gatling numbers
        List<Long> accounts = createFundedAccounts(ACCOUNTS);

        ScenarioBuilder transfers = scenario("transfer")
                .feed(randomTransfers(accounts))
                .exec(http("transfer")
                        .post("/transfers")
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", "#{key}")
                        .body(StringBody("{\"fromAccountId\":#{from},\"toAccountId\":#{to},\"amount\":#{amount},\"currency\":\"EUR\"}"))
                        .check(status().is(201)));

        // shared connections like a load balancer in front of the service, not one new tcp connection per request
        HttpProtocolBuilder protocol = http.baseUrl(BASE_URL).shareConnections();

        // open model: requests keep arriving at RATE per second even if the service slows down
        setUp(transfers.injectOpen(constantUsersPerSec(RATE).during(SECONDS))).protocols(protocol);
    }

    private static Iterator<Map<String, Object>> randomTransfers(List<Long> accounts) {
        return Stream.generate(() -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int from = random.nextInt(accounts.size());
            int to = random.nextInt(accounts.size() - 1);
            if (to >= from) {
                to++;
            }
            return Map.<String, Object>of(
                    "from", accounts.get(from),
                    "to", accounts.get(to),
                    "amount", 1 + random.nextInt(100),
                    "key", UUID.randomUUID().toString());
        }).iterator();
    }

    private List<Long> createFundedAccounts(int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String account = post("/accounts", "{\"ownerName\":\"load " + i + "\",\"currency\":\"EUR\"}");
            long id = Long.parseLong(account.replaceAll(".*\"id\":(\\d+).*", "$1"));
            post("/accounts/" + id + "/deposits", "{\"amount\":" + DEPOSIT + "}");
            ids.add(id);
        }
        return ids;
    }

    private String post(String path, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = setupClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201) {
                throw new IllegalStateException("setup " + path + " failed: " + response.statusCode() + " " + response.body());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("setup " + path + " failed", e);
        }
    }
}
