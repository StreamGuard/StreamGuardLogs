package com.example;

import com.example.school.sptech.*;
import com.example.school.sptech.config.Jira;
import com.example.school.sptech.config.Slack;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.*;
import java.util.Map;
import java.util.Properties;

public class Main {
    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        if (args.length != 0) throw new IllegalArgumentException("Configure por variáveis de ambiente.");
        boolean enviarJira = booleano(env, "JIRA_ENVIAR"), enviarSlack = booleano(env, "SLACK_ENVIAR");
        boolean teste = booleano(env, "ALERTAS_TESTE");
        if (teste && !env.getOrDefault("SILVER_CHAVE", "").startsWith("homologacao/alertas/")) {
            throw new IllegalArgumentException("Homologação exige SILVER_CHAVE no prefixo isolado homologacao/alertas/.");
        }
        Clock relogio = Clock.system(ZoneId.of(env.getOrDefault("CAPTURA_FUSO", "America/Sao_Paulo")));
        long polling = positivo(env, "ALERTAS_INTERVALO_SEGUNDOS", 15);
        long refresh = positivo(env, "CONFIG_INTERVALO_SEGUNDOS", 60);
        int recuperacao = Math.toIntExact(positivo(env, "RECUPERACAO_AMOSTRAS", 2));
        long idade = positivo(env, "SILVER_MAX_IDADE_SEGUNDOS", 900);
        long maxBytes = positivo(env, "SILVER_MAX_BYTES", 67108864);
        String regiao = env.getOrDefault("AWS_REGION", env.getOrDefault("AWS_DEFAULT_REGION", "us-east-1"));
        var timeout = Duration.ofSeconds(positivo(env, "HTTP_TIMEOUT_SEGUNDOS", 30));
        String baseJira = env.get("JIRA_BASE_URL");
        if (baseJira == null || baseJira.isBlank()) {
            String dominio = env.getOrDefault("JIRA_DOMAIN", "").replaceFirst("^https?://", "").replaceAll("/+$", "");
            baseJira = dominio.isBlank() ? "" : "https://" + dominio + (dominio.contains(".") ? "" : ".atlassian.net");
        }
        Jira jira = enviarJira ? new Jira(baseJira, obrigatoria(env, "JIRA_EMAIL"), obrigatoria(env, "JIRA_API_TOKEN"),
              obrigatoria(env, "JIRA_PROJECT_KEY"), env.getOrDefault("JIRA_ISSUE_TYPE", "Incident"), timeout) : null;
        Slack slack = enviarSlack ? new Slack(obrigatoria(env, "SLACK_WEBHOOK_URL"), timeout) : null;
        String host = obrigatoria(env, "DB_HOST"), banco = obrigatoria(env, "DB_DATABASE");
        if (!host.matches("[A-Za-z0-9_.-]+") || !banco.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Host ou banco inválido.");
        }
        long porta = positivo(env, "DB_PORT", 3306);
        if (porta > 65535) throw new IllegalArgumentException("DB_PORT inválida.");
        String url = "jdbc:mysql://" + host + ":" + porta + "/" + banco;
        var propriedades = new Properties();
        propriedades.setProperty("user", obrigatoria(env, "DB_USER"));
        propriedades.setProperty("password", obrigatoria(env, "DB_PASSWORD"));
        propriedades.setProperty("connectTimeout", "10000");
        propriedades.setProperty("socketTimeout", "15000");
        String ssl = env.getOrDefault("DB_SSL_MODE", "PREFERRED");
        if (!java.util.Set.of("DISABLED", "PREFERRED", "REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY").contains(ssl)) {
            throw new IllegalArgumentException("DB_SSL_MODE inválido.");
        }
        propriedades.setProperty("sslMode", ssl);
        var configs = new Configuracoes(() -> DriverManager.getConnection(url, propriedades), relogio, refresh);
        LocalDateTime inicio = env.containsKey("ALERTAS_DESDE") ? LocalDateTime.parse(env.get("ALERTAS_DESDE")) :
              LocalDateTime.now(relogio).withNano(0);
        try (var estado = new Estado(Path.of(env.getOrDefault("ESTADO_DIRETORIO", "/estado")));
             var s3 = S3Client.builder().region(Region.of(regiao)).httpClientBuilder(UrlConnectionHttpClient.builder())
                   .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(45)).apiCallAttemptTimeout(Duration.ofSeconds(15))).build()) {
            var fonte = new Silver(s3, obrigatoria(env, "S3_BUCKET"), maxBytes, relogio, env.get("SILVER_CHAVE"));
            var alertas = new AlertaService(fonte::obter, configs::obter, estado, jira, slack, relogio, recuperacao, idade, inicio, teste);
            System.out.println("StreamGuard: MySQL + Silver; intervalo mínimo entre envios por servidor/métrica: 300 segundos.");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    alertas.ciclo();
                    Thread.sleep(Math.multiplyExact(polling, 1000));
                } catch (InterruptedException erro) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private static String obrigatoria(Map<String, String> env, String nome) {
        String valor = env.get(nome);
        if (valor == null || valor.isBlank()) throw new IllegalArgumentException("Variável obrigatória: " + nome);
        return valor;
    }
    private static long positivo(Map<String, String> env, String nome, long padrao) {
        long valor = Long.parseLong(env.getOrDefault(nome, Long.toString(padrao)));
        if (valor < 1 || valor > Integer.MAX_VALUE) throw new IllegalArgumentException(nome + " fora do intervalo permitido.");
        return valor;
    }
    private static boolean booleano(Map<String, String> env, String nome) {
        String valor = env.getOrDefault(nome, "false");
        if (!valor.equals("true") && !valor.equals("false")) throw new IllegalArgumentException(nome + " deve ser true ou false.");
        return Boolean.parseBoolean(valor);
    }
}
