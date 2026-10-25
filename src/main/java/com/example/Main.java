
package com.example;

import com.example.school.sptech.AlertaService;
import com.example.school.sptech.Configuracoes;
import com.example.school.sptech.Estado;
import com.example.school.sptech.Silver;
import com.example.school.sptech.config.Jira;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

public class Main {
    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        if (args.length != 0) throw new IllegalArgumentException("Configure a execução por variáveis de ambiente.");
        boolean enviar = booleano(env.getOrDefault("JIRA_ENVIAR", "false"));
        Clock relogio = Clock.system(ZoneId.of(env.getOrDefault("CAPTURA_FUSO", "America/Sao_Paulo")));
        long polling = positivo(env, "ALERTAS_INTERVALO_SEGUNDOS", 15);
        long refresh = positivo(env, "CONFIG_INTERVALO_SEGUNDOS", 60);
        long retry = positivo(env, "JIRA_RETRY_SEGUNDOS", 60);
        int recuperacao = Math.toIntExact(positivo(env, "RECUPERACAO_AMOSTRAS", 2));
        long maxBytes = positivo(env, "SILVER_MAX_BYTES", 67108864);
        String regiao = env.getOrDefault("AWS_REGION", env.getOrDefault("AWS_DEFAULT_REGION", "us-east-1"));
        Jira jira = enviar ? new Jira(obrigatoria(env, "JIRA_BASE_URL"), obrigatoria(env, "JIRA_EMAIL"),
              obrigatoria(env, "JIRA_API_TOKEN"), obrigatoria(env, "JIRA_PROJECT_KEY"),
              env.getOrDefault("JIRA_ISSUE_TYPE", "Task"), Duration.ofSeconds(positivo(env, "JIRA_TIMEOUT_SEGUNDOS", 30))) : null;
        var configs = new Configuracoes(obrigatoria(env, "WEB_DATA_VIZ_URL"), relogio, refresh);
        LocalDateTime inicio = env.containsKey("ALERTAS_DESDE") ? LocalDateTime.parse(env.get("ALERTAS_DESDE")) :
              LocalDateTime.now(relogio).withNano(0);

        try (var estado = new Estado(Path.of(env.getOrDefault("ESTADO_DIRETORIO", "estado")));
             var s3 = S3Client.builder().region(Region.of(regiao)).httpClientBuilder(UrlConnectionHttpClient.builder())
                   .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(45)).apiCallAttemptTimeout(Duration.ofSeconds(15))).build()) {
            var fonte = new Silver(s3, obrigatoria(env, "S3_BUCKET"), maxBytes);
            var alertas = new AlertaService(fonte, configs, estado, jira, relogio, recuperacao, retry, inicio);
            System.out.println("StreamGuard: Silver -> avaliação. Jira " + (enviar ? "HABILITADO" : "DESABILITADO") + ".");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    alertas.ciclo();
                    Thread.sleep(Math.multiplyExact(polling, 1000));
                } catch (InterruptedException erro) {
                    Thread.currentThread().interrupt();
                }
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

    private static boolean booleano(String valor) {
        if (!valor.equals("true") && !valor.equals("false")) throw new IllegalArgumentException("JIRA_ENVIAR deve ser true ou false.");
        return Boolean.parseBoolean(valor);
    }
}
