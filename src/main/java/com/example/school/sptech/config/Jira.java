package com.example.school.sptech.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import com.example.school.sptech.Estado.Ocorrencia;
import com.example.school.sptech.dto.IssueFieldsDto;
import com.example.school.sptech.dto.IssueRequestDto;
import com.example.school.sptech.dto.IssueTypeDto;
import com.example.school.sptech.dto.ProjectDto;

public class Jira {
    public static final class Recusado extends IOException {
        public final int status;
        public final long aguardarSegundos;
        public Recusado(int status, long aguardarSegundos) {
            super("Jira recusou a operação, HTTP " + status + ".");
            this.status = status;
            this.aguardarSegundos = aguardarSegundos;
        }
    }

    public static final class MultiplosChamados extends IOException {
        public MultiplosChamados() { super("Busca não confirmou um único chamado."); }
    }

    private final String baseUrl;
    private final String authHeader;
    private final String projeto;
    private final String tipo;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public Jira(String baseUrl, String email, String apiToken, String projeto, String tipo, Duration timeout) {
        URI uri = URI.create(baseUrl);
        boolean local = "http".equals(uri.getScheme()) &&
              java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
        if ((!local && !"https".equals(uri.getScheme())) || uri.getHost() == null ||
              uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null ||
              !projeto.matches("[A-Za-z][A-Za-z0-9_]*") || tipo.isBlank() || email.isBlank() ||
              apiToken.isBlank() || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Configuração Jira inválida.");
        }
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString((email + ":" + apiToken).getBytes(StandardCharsets.UTF_8));
        this.projeto = projeto;
        this.tipo = tipo;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public String buscar(String identificador) throws IOException, InterruptedException {
        validarId(identificador);
        String jql = "project = \"" + projeto + "\" AND labels = \"sg-" + identificador + "\"";
        String caminho = "/rest/api/3/search/jql?maxResults=2&fields=key&jql=" + URLEncoder.encode(jql, StandardCharsets.UTF_8);
        var resposta = httpClient.send(pedido(caminho).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (resposta.statusCode() != 200) throw new IOException("Busca Jira indisponível, HTTP " + resposta.statusCode() + ".");
        var dados = objectMapper.readTree(resposta.body());
        if (dados == null || !dados.path("issues").isArray()) throw new IOException("Resposta de busca Jira inválida.");
        var issues = dados.path("issues");
        if (!dados.path("isLast").asBoolean(false) || issues.size() > 1) throw new MultiplosChamados();
        if (issues.isEmpty()) return null;
        String chave = issues.get(0).path("key").asText();
        if (!chave.matches("[A-Za-z][A-Za-z0-9_]*-[0-9]+")) throw new IOException("Busca Jira sem chave válida.");
        return chave;
    }

    public String criar(Ocorrencia o) throws IOException, InterruptedException {
        validarId(o.id);
        String resumo = "StreamGuard servidor " + o.servidor + " " + o.tipo + ": " + o.valor + " " + o.unidade
              + ", limite " + o.direcao + " " + o.limite + " " + o.unidade + ", " + o.instante;
        if (resumo.length() > 255) resumo = resumo.substring(0, 255);
        var campos = new IssueFieldsDto(new ProjectDto(projeto), resumo, new IssueTypeDto(tipo));
        campos.setLabels(List.of("streamguard", "sg-" + o.id));
        String contexto = "Servidor: " + o.servidor + "\nMétrica: " + o.tipo + "\nValor: " + o.valor
              + "\nLimite " + o.direcao + ": " + o.limite + "\nUnidade: " + o.unidade
              + "\nHorário da amostra: " + o.instante + "\nOcorrência: " + o.id
              + (o.encerradaEm == null ? "" : "\nEncerramento local: " + o.encerramento + " em " + o.encerradaEm);
        campos.setDescription(Map.of("type", "doc", "version", 1, "content", List.of(
              Map.of("type", "paragraph", "content", List.of(Map.of("type", "text", "text", contexto))))));
        String json = objectMapper.writeValueAsString(new IssueRequestDto(campos));
        var resposta = httpClient.send(pedido("/rest/api/3/issue")
              .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        int status = resposta.statusCode();
        if (java.util.Set.of(400, 401, 403, 404, 405, 413, 415, 422, 429).contains(status)) throw new Recusado(status, retryAfter(resposta));
        if (status != 201) throw new IOException("Criação Jira não confirmada.");
        var dados = objectMapper.readTree(resposta.body());
        String chave = dados == null ? "" : dados.path("key").asText();
        if (!chave.matches("[A-Za-z][A-Za-z0-9_]*-[0-9]+")) throw new IOException("Jira respondeu sem chave válida.");
        return chave;
    }

    private HttpRequest.Builder pedido(String caminho) {
        return HttpRequest.newBuilder(URI.create(baseUrl + caminho)).timeout(timeout)
              .header("Authorization", authHeader).header("Accept", "application/json")
              .header("Content-Type", "application/json");
    }

    private static long retryAfter(HttpResponse<?> resposta) {
        try {
            long segundos = Long.parseLong(resposta.headers().firstValue("Retry-After").orElse("0"));
            return Math.min(Math.max(segundos, 0), 86400);
        } catch (NumberFormatException erro) {
            return 0;
        }
    }

    private static void validarId(String identificador) {
        if (identificador == null || !identificador.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("ID de ocorrência inválido.");
    }
}
