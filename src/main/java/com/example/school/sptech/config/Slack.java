package com.example.school.sptech.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public class Slack {

    private final String webhookUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public Slack(String webhookUrl) {
        this.webhookUrl = webhookUrl;

        this.httpClient = HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(30))
              .build();

        this.objectMapper = new ObjectMapper();
    }

    public void enviarMensagem(String texto) throws IOException, InterruptedException {
        // O Incoming Webhook só precisa de: { "text": "..." }
        String json = objectMapper.writeValueAsString(Map.of("text", texto));

        HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(webhookUrl))
              .timeout(Duration.ofSeconds(30))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .build();

        HttpResponse<String> response =
              httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Quando dá certo, o Slack responde 200 com o corpo "ok"
        if (response.statusCode() != 200) {
            throw new RuntimeException(
                  "Slack request failed: " + response.statusCode()
                        + " - " + response.body());
        }
    }
}
