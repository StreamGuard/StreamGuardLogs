package com.example.school.sptech;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.example.school.sptech.config.Jira;
import com.example.school.sptech.config.Slack;

public class AlertaService {

    private static final DateTimeFormatter FORMATO =
          DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final Slack slackCritico;
    private final Slack slackAtencao;
    private final Jira jira;
    private final String jiraProjectKey;

    public AlertaService() {
        // Todas as credenciais vêm de variáveis de ambiente (nunca fixas no código)
        this.slackCritico = new Slack(lerVariavel("SLACK_WEBHOOK_CRITICO"));
        this.slackAtencao = new Slack(lerVariavel("SLACK_WEBHOOK_ATENCAO"));
        this.jira = new Jira(
              lerVariavel("JIRA_BASE_URL"),
              lerVariavel("JIRA_EMAIL"),
              lerVariavel("JIRA_API_TOKEN"));
        this.jiraProjectKey = lerVariavel("JIRA_PROJECT_KEY");
    }

    public void dispararAlerta(String componente, String metrica,
                               String valorAtingido, boolean critico) {

        String nivel = critico ? "CRÍTICO" : "ATENÇÃO";
        String emoji = critico ? "🔴" : "🟠";
        String horario = LocalDateTime.now().format(FORMATO);

        // Mensagem do Slack (pode ter várias linhas; *texto* fica em negrito)
        String textoSlack = emoji + " *[" + nivel + "]* " + componente + "\n"
              + "Métrica: " + metrica + " = " + valorAtingido + "\n"
              + "Horário: " + horario;

        // Título do chamado no Jira (uma linha só)
        String tituloJira = "[" + nivel + "] " + componente
              + " - " + metrica + " em " + valorAtingido;

        // 1) Slack: critico vai para um canal, atenção para outro
        try {
            Slack canal = critico ? slackCritico : slackAtencao;
            canal.enviarMensagem(textoSlack);
            registrarLog("SLACK", "enviado", tituloJira);
        } catch (Exception e) {
            registrarLog("SLACK", "falhou: " + e.getMessage(), tituloJira);
        }

        // 2) Jira: cada integração tem o seu try/catch, então uma não derruba a outra
        try {
            String tipo = critico ? "Bug" : "Task";
            jira.createIssue(jiraProjectKey, tituloJira, tipo);
            registrarLog("JIRA", "enviado", tituloJira);
        } catch (Exception e) {
            registrarLog("JIRA", "falhou: " + e.getMessage(), tituloJira);
        }
    }

    private void registrarLog(String integracao, String status, String mensagem) {
        // Por enquanto só imprime. Depois, troque por um INSERT numa tabela de log.
        String agora = LocalDateTime.now().format(FORMATO);
        System.out.println(agora + " [" + integracao + "] " + status + " -> " + mensagem);
    }

    private static String lerVariavel(String nome) {
        String valor = System.getenv(nome);
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException("Variável de ambiente não definida: " + nome);
        }
        return valor;
    }
}
