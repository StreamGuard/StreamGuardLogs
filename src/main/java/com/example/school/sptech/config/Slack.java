package com.example.school.sptech.config;

import com.example.school.sptech.Estado.Ocorrencia;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

public final class Slack {
    private final URI webhook;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public Slack(String url, Duration timeout) {
        webhook = URI.create(url);
        boolean local = "http".equals(webhook.getScheme()) && Set.of("localhost", "127.0.0.1").contains(webhook.getHost());
        if ((!local && !("https".equals(webhook.getScheme()) &&
              Set.of("hooks.slack.com", "hooks.slack-gov.com").contains(webhook.getHost()))) ||
              webhook.getUserInfo() != null || webhook.getFragment() != null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Webhook Slack inválido.");
        }
        this.timeout = timeout;
        http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public void enviar(Ocorrencia o, String jiraUrl) throws IOException, InterruptedException {
        String texto = Jira.resumo(o) + "\n" + Jira.contexto(o) + (jiraUrl == null ? "" : "\nJira: " + jiraUrl);
        var pedido = HttpRequest.newBuilder(webhook).timeout(timeout).header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("text", texto, "mrkdwn", false)))).build();
        var resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
        int status = resposta.statusCode();
        if (status >= 400 && status < 500) {
            long espera;
            try { espera = Math.min(86400, Math.max(0, Long.parseLong(resposta.headers().firstValue("Retry-After").orElse("0")))); }
            catch (NumberFormatException erro) { espera = 0; }
            throw new Jira.Recusado(status, espera);
        }
        if (status != 200 || !"ok".equals(resposta.body().trim())) throw new IOException("Entrega Slack não confirmada.");
    }
}
