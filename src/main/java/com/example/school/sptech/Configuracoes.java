package com.example.school.sptech;

import com.example.school.sptech.Modelo.Configuracao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Configuracoes {
    private record Cache(long tentativa, Map<String, Configuracao> valores) {}
    private final Map<Long, Cache> cache = new HashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final String base;
    private final Clock relogio;
    private final long ttl;

    public Configuracoes(String base, Clock relogio, long ttlSegundos) {
        URI uri = URI.create(base);
        if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null ||
              uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || ttlSegundos <= 0) {
            throw new IllegalArgumentException("URL da API ou intervalo de configuração inválido.");
        }
        this.base = base.replaceAll("/+$", "");
        this.relogio = relogio;
        this.ttl = ttlSegundos * 1000;
    }

    public Map<String, Configuracao> obter(long servidor) throws IOException, InterruptedException {
        long agora = relogio.millis();
        Cache anterior = cache.get(servidor);
        if (anterior != null && agora - anterior.tentativa() < ttl) {
            if (anterior.valores() == null) throw new IOException("API indisponível para servidor " + servidor + ".");
            return anterior.valores();
        }
        cache.put(servidor, new Cache(agora, null));
        var pedido = HttpRequest.newBuilder(URI.create(base + "/metricas/configuracoes/" + servidor))
              .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET().build();
        var resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
        if (resposta.statusCode() != 200) throw new IOException("API de configurações, HTTP " + resposta.statusCode() + ".");
        try {
            JsonNode dados = mapper.readTree(resposta.body());
            if (!dados.isArray()) throw new IllegalArgumentException();
            var valores = new LinkedHashMap<String, Configuracao>();
            for (var item : dados) {
                String tipo = item.path("tipo").asText(), unidade = item.path("unidade").asText();
                var habilitado = item.path("habilitado");
                var intervalo = item.path("intervalo_coleta");
                if (!Modelo.UNIDADES.containsKey(tipo) || !unidade.equals(Modelo.UNIDADES.get(tipo)) ||
                      !item.path("id_config_metrica").isIntegralNumber() || !item.path("id_config_metrica").canConvertToLong() ||
                      item.path("id_config_metrica").asLong() <= 0 ||
                      !habilitado.isIntegralNumber() || !habilitado.canConvertToInt() || (habilitado.asInt() != 0 && habilitado.asInt() != 1) ||
                      !intervalo.isIntegralNumber() || !intervalo.canConvertToInt() || intervalo.asInt() <= 0) {
                    throw new IllegalArgumentException();
                }
                BigDecimal minimo = limite(item, "limite_minimo", unidade);
                BigDecimal maximo = limite(item, "limite_maximo", unidade);
                if (minimo != null && maximo != null && minimo.compareTo(maximo) > 0) throw new IllegalArgumentException();
                if (valores.putIfAbsent(tipo, new Configuracao(tipo, unidade, habilitado.asInt() == 1,
                      intervalo.asLong(), minimo, maximo)) != null) throw new IllegalArgumentException();
            }
            var imutaveis = Map.copyOf(valores);
            cache.put(servidor, new Cache(agora, imutaveis));
            return imutaveis;
        } catch (IOException | RuntimeException erro) {
            throw new IOException("Configuração inválida ou duplicada para servidor " + servidor + ".", erro);
        }
    }

    private BigDecimal limite(JsonNode item, String campo, String unidade) {
        JsonNode valor = item.get(campo);
        if (valor == null) throw new IllegalArgumentException();
        if (valor.isNull()) return null;
        if (!valor.isNumber() && !valor.isTextual()) throw new IllegalArgumentException();
        BigDecimal numero = new BigDecimal(valor.asText());
        if (numero.signum() < 0 || !Double.isFinite(numero.doubleValue()) ||
              Math.abs((long) numero.scale()) > 340 ||
              (unidade.equals("%") && numero.compareTo(BigDecimal.valueOf(100)) > 0)) throw new IllegalArgumentException();
        return numero;
    }
}
