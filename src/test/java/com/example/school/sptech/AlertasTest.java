package com.example.school.sptech;

import com.example.school.sptech.Modelo.*;
import com.example.school.sptech.config.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AlertasTest {
    @TempDir Path pasta;
    final ObjectMapper json = new ObjectMapper();
    final Relogio clock = new Relogio();
    final Map<String, Configuracao> configs = new LinkedHashMap<>();
    List<Medicao> amostras = new ArrayList<>();
    HttpServer http;
    String base;
    int criacoes, atualizacoes, mensagens, slackStatus = 200;
    boolean respostaPerdida, buscaOculta, bancoIndisponivel;
    String label;
    String ultimoCorpo;

    static final class Relogio extends Clock {
        Instant agora = Instant.parse("2026-11-19T15:00:00Z");
        public ZoneId getZone() { return ZoneId.of("America/Sao_Paulo"); }
        public Clock withZone(ZoneId zone) { return Clock.fixed(agora, zone); }
        public Instant instant() { return agora; }
        void avancar(long segundos) { agora = agora.plusSeconds(segundos); }
    }

    @BeforeEach void iniciar() throws Exception {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", troca -> {
            String caminho = troca.getRequestURI().getPath();
            String corpo = new String(troca.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String saida;
            int status;
            if (caminho.equals("/slack")) {
                mensagens++;
                status = slackStatus;
                saida = status == 200 ? "ok" : "erro";
                if (status == 429) troca.getResponseHeaders().set("Retry-After", "400");
            } else if (caminho.equals("/rest/api/3/search/jql")) {
                status = 200;
                saida = criacoes == 0 || buscaOculta ? "{\"isLast\":true,\"issues\":[]}" : "{\"isLast\":true,\"issues\":[{\"key\":\"SUP-1\"}]}";
            } else if (troca.getRequestMethod().equals("POST")) {
                criacoes++;
                ultimoCorpo = corpo;
                label = json.readTree(corpo).path("fields").path("labels").get(1).asText();
                status = 201;
                saida = respostaPerdida ? "{}" : "{\"key\":\"SUP-1\"}";
            } else {
                atualizacoes++;
                ultimoCorpo = corpo;
                status = 204;
                saida = "";
            }
            byte[] bytes = saida.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            troca.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) troca.getResponseBody().write(bytes);
            troca.close();
        });
        http.start();
        base = "http://127.0.0.1:" + http.getAddress().getPort();
    }
    @AfterEach void parar() { if (http != null) http.stop(0); }

    Configuracao config(String tipo, String unidade, boolean ativa, String min, String max) {
        return new Configuracao(tipo, unidade, ativa, 5, min == null ? null : new BigDecimal(min),
              max == null ? null : new BigDecimal(max), 1, "servidor-teste", tipo);
    }
    void medir(long servidor, String tipo, String valor) {
        amostras.removeIf(m -> m.servidor() == servidor && m.tipo().equals(tipo));
        amostras.add(new Medicao(servidor, LocalDateTime.now(clock), tipo, new BigDecimal(valor), Modelo.UNIDADES.get(tipo)));
    }
    AlertaService servico(Estado estado) {
        return servico(estado, false);
    }
    AlertaService servico(Estado estado, boolean teste) {
        return new AlertaService(empresas -> List.of(new Silver.Particao(1, new Silver.Snapshot("etag", List.copyOf(amostras), 0))),
              () -> { if (bancoIndisponivel) throw new IOException("Banco indisponível"); return Map.copyOf(configs); }, estado,
              new Jira(base, "tecnico@example.com", "token-teste", "SUP", "Incident", Duration.ofSeconds(2)),
              new Slack(base + "/slack", Duration.ofSeconds(2)), clock, 2, 900, LocalDateTime.now(clock).minusSeconds(900), teste);
    }

    @Test void homologacaoIdentificaAmostraSinteticaNoChamadoENoHistorico() throws Exception {
        configs.put("1|USO_CPU", config("USO_CPU", "%", true, null, "80"));
        medir(1, "USO_CPU", "91");
        try (var estado = new Estado(pasta)) { servico(estado, true).ciclo(); }
        var campos = json.readTree(ultimoCorpo).path("fields");
        assertTrue(campos.path("summary").asText().startsWith("[HOMOLOGAÇÃO]"));
        assertTrue(campos.path("description").toString().contains("não representa uma medição real"));
        var evento = json.readTree(Files.readAllLines(pasta.resolve("alertas.jsonl")).get(0));
        assertEquals("homologacao", evento.path("ambiente").asText());
        try (var estado = new Estado(pasta)) {
            assertTrue(estado.dados.ocorrencias.values().iterator().next().teste);
        }
    }

    @Test void atualizaMesmoChamadoSomenteAposCincoMinutosEPreservaNoReinicio() throws Exception {
        configs.put("1|USO_CPU", config("USO_CPU", "%", true, null, "80"));
        medir(1, "USO_CPU", "91");
        try (var estado = new Estado(pasta)) {
            var s = servico(estado);
            s.ciclo();
            s.ciclo();
            assertEquals(1, criacoes);
            assertEquals(1, mensagens);
            clock.avancar(299);
            medir(1, "USO_CPU", "92");
            s.ciclo();
            assertEquals(0, atualizacoes);
            assertEquals(1, mensagens);
        }
        try (var estado = new Estado(pasta)) {
            var s = servico(estado);
            s.ciclo();
            assertEquals(0, atualizacoes);
            clock.avancar(1);
            s.ciclo();
            assertEquals(1, atualizacoes);
            assertEquals(1, criacoes);
            assertEquals(2, mensagens);
            assertTrue(ultimoCorpo.contains("92"));
            assertTrue(label.startsWith("sg-"));
            s.ciclo();
            assertEquals(1, atualizacoes);
        }
    }

    @Test void historicoPersisteContextoEEntregasAposReinicio() throws Exception {
        configs.put("1|USO_CPU", config("USO_CPU", "%", true, null, "80"));
        medir(1, "USO_CPU", "91");
        try (var estado = new Estado(pasta)) { servico(estado).ciclo(); }
        var arquivo = pasta.resolve("alertas.jsonl");
        var eventos = Files.readAllLines(arquivo);
        assertEquals(5, eventos.size());
        var alerta = json.readTree(eventos.get(0));
        assertEquals("ALERTA_AVALIADO", alerta.path("evento").asText());
        assertEquals("91", alerta.path("valor").asText());
        assertEquals("80", alerta.path("limite").asText());
        assertEquals("USO_CPU", alerta.path("metrica").asText());
        assertEquals("JIRA_TENTATIVA", json.readTree(eventos.get(1)).path("evento").asText());
        assertEquals("JIRA_CONFIRMADO", json.readTree(eventos.get(2)).path("evento").asText());
        assertEquals("SLACK_TENTATIVA", json.readTree(eventos.get(3)).path("evento").asText());
        assertEquals("SLACK_CONFIRMADO", json.readTree(eventos.get(4)).path("evento").asText());
        assertEquals("SUP-1", json.readTree(eventos.get(4)).path("jira").asText());
        try (var estado = new Estado(pasta)) { servico(estado).ciclo(); }
        assertEquals(eventos, Files.readAllLines(arquivo));
        assertFalse(Files.readString(arquivo).contains("token-teste"));
        assertFalse(Files.readString(arquivo).contains(base));
    }

    @Test void falhaNoHistoricoInterrompeAntesDoEnvioExterno() throws Exception {
        configs.put("1|USO_CPU", config("USO_CPU", "%", true, null, "80"));
        medir(1, "USO_CPU", "91");
        Files.createDirectory(pasta.resolve("alertas.jsonl"));
        try (var estado = new Estado(pasta)) {
            assertThrows(IllegalStateException.class, () -> servico(estado).ciclo());
        }
        try (var estado = new Estado(pasta)) {
            assertThrows(IllegalStateException.class, () -> servico(estado).ciclo());
        }
        assertEquals(0, criacoes);
        assertEquals(0, mensagens);
    }

    @Test void limitesMinimoMaximoIgualdadeEComponentesIndependentes() throws Exception {
        // Testa todas as unidades sem assumir percentuais para rede, RAM ou carga.
        for (var item : Modelo.UNIDADES.entrySet()) {
            configs.put("1|" + item.getKey(), config(item.getKey(), item.getValue(), true, "10", "80"));
            medir(1, item.getKey(), "80");
        }
        try (var estado = new Estado(pasta)) {
            var s = servico(estado);
            s.ciclo();
            assertEquals(0, criacoes);
            clock.avancar(5);
            medir(1, "USO_CPU", "81");
            medir(1, "RAM_DISPONIVEL", "9");
            medir(1, "TAXA_ENVIO_REDE", "81");
            medir(1, "USO_DISCO", "81");
            configs.put("2|USO_CPU", config("USO_CPU", "%", true, null, "80"));
            medir(2, "USO_CPU", "81");
            buscaOculta = true;
            s.ciclo();
            assertEquals(5, criacoes);
            assertEquals(5, mensagens);
            assertEquals("MINIMO", estado.dados.ocorrencias.values().stream().filter(o -> o.tipo.equals("RAM_DISPONIVEL")).findFirst().orElseThrow().direcao);
        }
    }

    @Test void recuperacaoNaoPermiteBurlarIntervaloComOscilacao() throws Exception {
        configs.put("1|USO_CPU", config("USO_CPU", "%", true, null, "80"));
        medir(1, "USO_CPU", "91");
        buscaOculta = true;
        try (var estado = new Estado(pasta)) {
            var s = servico(estado);
            s.ciclo();
            clock.avancar(5); medir(1, "USO_CPU", "70"); s.ciclo();
            clock.avancar(5); medir(1, "USO_CPU", "70"); s.ciclo();
            assertNull(estado.dados.metricas.get("1|USO_CPU").ativa);
            clock.avancar(5); medir(1, "USO_CPU", "91"); s.ciclo();
            assertEquals(1, criacoes);
            assertEquals(1, mensagens);
            clock.avancar(285); s.ciclo();
            assertEquals(2, criacoes);
            assertEquals(2, mensagens);
        }
    }

    @Test void recuperacaoAceitaNovasLeiturasDosLotesDeCincoMinutos() throws Exception {
        configs.put("1|USO_CPU", config("USO_CPU", "%", true, null, "80"));
        medir(1, "USO_CPU", "91");
        try (var estado = new Estado(pasta)) {
            var s = servico(estado); s.ciclo();
            clock.avancar(300); medir(1, "USO_CPU", "70"); s.ciclo();
            assertNotNull(estado.dados.metricas.get("1|USO_CPU").ativa);
            clock.avancar(300); medir(1, "USO_CPU", "70"); s.ciclo();
            assertNull(estado.dados.metricas.get("1|USO_CPU").ativa);
            assertEquals(1, criacoes); assertEquals(1, mensagens);
        }
    }

    @Test void alteracaoDeConfigReavaliaAmostraAtualEDesabilitacaoCancelaEnvios() throws Exception {
        configs.put("1|USO_CPU", config("USO_CPU", "%", true, null, "99"));
        medir(1, "USO_CPU", "91");
        try (var estado = new Estado(pasta)) {
            var s = servico(estado);
            s.ciclo(); assertEquals(0, criacoes);
            configs.put("1|USO_CPU", config("USO_CPU", "%", true, null, "90"));
            clock.avancar(60); s.ciclo(); assertEquals(1, criacoes);
            configs.put("1|USO_CPU", config("USO_CPU", "%", false, null, "80"));
            clock.avancar(300); medir(1, "USO_CPU", "95"); s.ciclo();
            assertEquals(0, atualizacoes);
            assertNull(estado.dados.metricas.get("1|USO_CPU").ativa);
            configs.clear(); s.ciclo(); assertEquals(1, criacoes);
        }
    }

    @Test void naoEnviaHistoricoDadosAntigosFuturosOuMedicaoInvalida() throws Exception {
        configs.put("1|USO_CPU", config("USO_CPU", "%", true, null, "80"));
        medir(1, "USO_CPU", "70");
        amostras.add(new Medicao(1, LocalDateTime.now(clock).minusSeconds(10), "USO_CPU", new BigDecimal("99"), "%"));
        try (var estado = new Estado(pasta)) {
            var s = servico(estado);
            s.ciclo(); assertEquals(0, criacoes);
            amostras = List.of(new Medicao(1, LocalDateTime.now(clock).minusSeconds(901), "USO_CPU", new BigDecimal("99"), "%"));
            s.ciclo(); assertEquals(0, criacoes);
            amostras = List.of(new Medicao(1, LocalDateTime.now(clock).plusSeconds(1), "USO_CPU", new BigDecimal("99"), "%"));
            s.ciclo(); assertEquals(0, criacoes);
            clock.avancar(5);
            amostras = List.of(new Medicao(1, LocalDateTime.now(clock), "USO_CPU", null, "%"));
            s.ciclo(); assertEquals(0, criacoes);
        }
    }

    @Test void falhaNoBancoImpedeAtualizacaoComLimiteDesatualizado() throws Exception {
        configs.put("1|USO_CPU", config("USO_CPU", "%", true, null, "80"));
        medir(1, "USO_CPU", "91");
        try (var estado = new Estado(pasta)) {
            var s = servico(estado); s.ciclo();
            clock.avancar(300); medir(1, "USO_CPU", "95"); bancoIndisponivel = true;
            s.ciclo();
            assertEquals(0, atualizacoes); assertEquals(1, mensagens);
        }
    }

    @Test void criacaoAmbiguaReconciliaSemSegundoPost() throws Exception {
        configs.put("1|USO_CPU", config("USO_CPU", "%", true, null, "80"));
        medir(1, "USO_CPU", "91"); respostaPerdida = true; buscaOculta = true;
        try (var estado = new Estado(pasta)) {
            var s = servico(estado); s.ciclo();
            assertEquals(1, criacoes); assertEquals(1, mensagens);
            clock.avancar(300); medir(1, "USO_CPU", "92"); s.ciclo();
            assertEquals(1, criacoes);
        }
        buscaOculta = false;
        try (var estado = new Estado(pasta)) {
            clock.avancar(300); medir(1, "USO_CPU", "93"); servico(estado).ciclo();
            assertEquals(1, criacoes); assertEquals(1, atualizacoes); assertEquals(3, mensagens);
        }
    }

    @Test void falhaSlackRetentaSeparadamenteERespeitaRetryAfter() throws Exception {
        configs.put("1|USO_CPU", config("USO_CPU", "%", true, null, "80"));
        medir(1, "USO_CPU", "91"); slackStatus = 429;
        try (var estado = new Estado(pasta)) {
            var s = servico(estado); s.ciclo();
            slackStatus = 200;
            clock.avancar(300); s.ciclo(); assertEquals(1, mensagens);
            clock.avancar(100); s.ciclo(); assertEquals(2, mensagens);
            assertEquals(1, criacoes); assertEquals(0, atualizacoes);
        }
    }

    @Test void webhookAmbiguoNaoEhReenviadoNoReinicio() throws Exception {
        configs.put("1|USO_CPU", config("USO_CPU", "%", true, null, "80"));
        medir(1, "USO_CPU", "91"); slackStatus = 500;
        try (var estado = new Estado(pasta)) { servico(estado).ciclo(); }
        slackStatus = 200;
        clock.avancar(300); medir(1, "USO_CPU", "92");
        try (var estado = new Estado(pasta)) { servico(estado).ciclo(); }
        assertEquals(1, mensagens); assertEquals(1, criacoes);
    }

    @Test void csvConflitanteNaoEscolheValorArbitrarioEAceitaInventario() throws Exception {
        String csv = "\ufeffservidor_id,timestamp,tipo,valor,unidade\n" +
              "1,2026-11-19 12:00:00,USO_CPU,99,%\n1,2026-11-19 12:00:00,USO_CPU,70,%\n" +
              "1,2026-11-19 12:00:00,MEMORIA_TOTAL,1024,bytes\n";
        var dados = Silver.ler(new StringReader(csv), "etag");
        assertEquals(2, dados.medicoes().size());
        assertFalse(dados.medicoes().stream().filter(m -> m.tipo().equals("USO_CPU")).findFirst().orElseThrow().valida());
        assertEquals(1, dados.rejeitados());
        assertThrows(IOException.class, () -> Silver.ler(new StringReader("servidor_id,valor\n1,99"), "etag"));
    }

    @Test void travaDeProcessoEEstadoCorrompidoImpedemResetSilencioso() throws Exception {
        try (var estado = new Estado(pasta)) {
            estado.salvar();
            assertThrows(IOException.class, () -> new Estado(pasta));
        }
        Files.writeString(pasta.resolve("estado.json"), "invalido");
        assertThrows(IOException.class, () -> new Estado(pasta));
    }
}
