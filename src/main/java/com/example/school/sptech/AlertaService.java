package com.example.school.sptech;

import com.example.school.sptech.Modelo.Configuracao;
import com.example.school.sptech.Modelo.Medicao;
import com.example.school.sptech.Estado.Envio;
import com.example.school.sptech.Estado.Ocorrencia;
import com.example.school.sptech.Estado.Slot;
import com.example.school.sptech.config.Jira;
import com.example.school.sptech.config.Slack;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;

public final class AlertaService {
    public static final long INTERVALO_ENVIO_MS = 300_000;
    private final Silver.Fonte silver;
    private final Configuracoes.Fonte configuracoes;
    private final Estado estado;
    private final Jira jira;
    private final Slack slack;
    private final Clock relogio;
    private final int confirmarRecuperacao;
    private final long maxIdade;
    private final boolean teste;
    private final ObjectMapper mapper = new ObjectMapper();

    public AlertaService(Silver.Fonte silver, Configuracoes.Fonte configuracoes, Estado estado, Jira jira,
                         Slack slack, Clock relogio, int confirmarRecuperacao, long maxIdade, LocalDateTime inicio) {
        this(silver, configuracoes, estado, jira, slack, relogio, confirmarRecuperacao, maxIdade, inicio, false);
    }

    public AlertaService(Silver.Fonte silver, Configuracoes.Fonte configuracoes, Estado estado, Jira jira,
                         Slack slack, Clock relogio, int confirmarRecuperacao, long maxIdade, LocalDateTime inicio, boolean teste) {
        if (confirmarRecuperacao < 1 || maxIdade < 1 || maxIdade > 86400) throw new IllegalArgumentException();
        this.silver = silver;
        this.configuracoes = configuracoes;
        this.estado = estado;
        this.jira = jira;
        this.slack = slack;
        this.relogio = relogio;
        this.confirmarRecuperacao = confirmarRecuperacao;
        this.maxIdade = maxIdade;
        this.teste = teste;
        if (estado.dados.inicioProcessamento == null) estado.dados.inicioProcessamento = inicio.toString();
        estado.salvar();
    }

    public void ciclo() throws InterruptedException {
        try {
            var configs = configuracoes.obter();
            for (var item : estado.dados.metricas.entrySet()) {
                var config = configs.get(item.getKey());
                if (config == null || !config.aplicavel()) encerrar(item.getValue(), "CONFIGURACAO_DESABILITADA");
            }
            var empresas = new HashSet<Long>();
            configs.values().forEach(c -> empresas.add(c.empresa()));
            var recentes = new HashMap<String, Medicao>();
            for (var parte : silver.obter(empresas)) {
                if (parte.snapshot().rejeitados() > 0) {
                    System.err.println("Silver: registros rejeitados=" + parte.snapshot().rejeitados());
                }
                for (var m : parte.snapshot().medicoes()) {
                    var c = configs.get(m.chave());
                    if (c == null || (parte.empresa() != 0 && c.empresa() != parte.empresa())) continue;
                    if (m.instante().isAfter(LocalDateTime.now(relogio))) continue;
                    recentes.merge(m.chave(), m, (a, b) -> {
                        if (a.instante().isAfter(b.instante())) return a;
                        if (b.instante().isAfter(a.instante())) return b;
                        if (a.valor() == null || b.valor() == null || a.valor().compareTo(b.valor()) != 0 || !a.unidade().equals(b.unidade())) {
                            return new Medicao(a.servidor(), a.instante(), a.tipo(), null, a.unidade());
                        }
                        return a;
                    });
                }
            }
            var emAlerta = new HashSet<String>();
            for (var m : recentes.values()) {
                var c = configs.get(m.chave());
                if (m.instante().isBefore(LocalDateTime.now(relogio).minusSeconds(maxIdade)) ||
                      m.instante().isBefore(LocalDateTime.parse(estado.dados.inicioProcessamento))) continue;
                avaliar(m, c);
                var slot = estado.dados.metricas.get(m.chave());
                if (m.valida() && c.aplicavel() && c.unidade().equals(m.unidade()) && c.violacao(m.valor()) != null &&
                      c.assinatura().equals(slot.configuracao)) {
                    emAlerta.add(m.chave());
                }
            }
            estado.salvar();
            for (String chave : emAlerta) {
                var slot = estado.dados.metricas.get(chave);
                if (slot.ativa == null) continue;
                var o = estado.dados.ocorrencias.get(slot.ativa);
                if (jira != null) enviarJira(slot, o);
                if (slack != null) enviarSlack(slot, o);
            }
        } catch (IOException erro) {
            // Nunca imprimir o corpo de respostas, URLs privadas, tokens ou detalhes JDBC.
            System.err.println(erro.getMessage());
        }
    }

    private void avaliar(Medicao m, Configuracao c) {
        var slot = estado.dados.metricas.computeIfAbsent(m.chave(), k -> new Slot());
        boolean mudou = !c.assinatura().equals(slot.configuracao);
        if (slot.ultimoVisto != null) {
            var anterior = LocalDateTime.parse(slot.ultimoVisto);
            if (m.instante().isBefore(anterior) || (!mudou && m.instante().equals(anterior))) return;
        }
        slot.configuracao = c.assinatura();
        slot.ultimoVisto = m.instante().toString();
        if (mudou) { slot.ultimaAvaliacao = null; slot.recuperacoes = 0; }
        if (!m.valida() || !c.aplicavel() || !c.unidade().equals(m.unidade())) {
            slot.recuperacoes = 0;
            return;
        }
        var ultima = slot.ultimaAvaliacao == null ? null : LocalDateTime.parse(slot.ultimaAvaliacao);
        if (ultima != null && m.instante().isBefore(ultima.plusSeconds(c.intervalo()))) return;
        // A ETL entrega lotes a cada cinco minutos, mesmo que a coleta seja de cinco segundos.
        if (ultima != null && m.instante().isAfter(ultima.plusSeconds(Math.max(c.intervalo() * 3, maxIdade)))) slot.recuperacoes = 0;
        slot.ultimaAvaliacao = m.instante().toString();
        String direcao = c.violacao(m.valor());
        if (direcao == null) {
            if (slot.ativa != null && ++slot.recuperacoes >= confirmarRecuperacao) encerrar(slot, "RECUPERACAO_CONFIRMADA");
            return;
        }
        slot.recuperacoes = 0;
        Ocorrencia o;
        if (slot.ativa == null) {
            o = new Ocorrencia();
            o.teste = teste;
            o.id = identidade((teste ? "HOMOLOGACAO|" : "") + m.chave() + "|" + m.instante() + "|" + c.assinatura());
            o.servidor = m.servidor();
            o.tipo = m.tipo();
            o.instante = m.instante().toString();
            estado.dados.ocorrencias.put(o.id, o);
            slot.ativa = o.id;
        } else {
            o = estado.dados.ocorrencias.get(slot.ativa);
        }
        o.empresa = c.empresa();
        o.hostname = c.hostname();
        o.componente = c.componente();
        o.unidade = m.unidade();
        o.ultimaAmostra = m.instante().toString();
        o.valor = Modelo.texto(m.valor());
        o.limite = Modelo.texto(c.limite(direcao));
        o.direcao = direcao;
        o.configuracao = c.assinatura();
        o.revisao++;
        log("ALERTA_AVALIADO", o);
    }

    private void encerrar(Slot slot, String motivo) {
        if (slot.ativa == null) return;
        var o = estado.dados.ocorrencias.get(slot.ativa);
        o.encerradaEm = LocalDateTime.now(relogio).toString();
        o.encerramento = motivo;
        if (o.envio == Envio.PENDENTE) o.envio = Envio.CANCELADA;
        if (o.envioSlack == Envio.PENDENTE) o.envioSlack = Envio.CANCELADA;
        slot.ativa = null;
        slot.recuperacoes = 0;
        log(motivo, o);
    }

    private boolean podeEnviar(long tentativa, long proxima) {
        long agora = relogio.millis();
        return agora >= proxima && (tentativa == 0 || agora - tentativa >= INTERVALO_ENVIO_MS);
    }

    private void enviarJira(Slot slot, Ocorrencia o) throws InterruptedException {
        if (o.envio == Envio.CANCELADA || (o.issueKey != null && o.revisaoJira >= o.revisao) ||
              !podeEnviar(slot.ultimaTentativaJira, o.proximaTentativa)) return;
        slot.ultimaTentativaJira = relogio.millis();
        estado.salvar();
        log("JIRA_TENTATIVA", o);
        try {
            if (o.issueKey == null) {
                String existente = jira.buscar(o.id);
                if (existente != null) {
                    o.issueKey = existente;
                    estado.salvar();
                } else {
                    if (o.envio == Envio.AMBIGUA || o.envio == Envio.ENVIANDO) return;
                    o.envio = Envio.ENVIANDO;
                    estado.salvar();
                    o.issueKey = jira.criar(o);
                    o.revisaoJira = o.revisao;
                }
            }
            if (o.revisaoJira < o.revisao) {
                jira.atualizar(o);
                o.revisaoJira = o.revisao;
            }
            o.envio = Envio.ENVIADA;
            log("JIRA_CONFIRMADO", o);
        } catch (Jira.MultiplosChamados erro) {
            o.envio = Envio.AMBIGUA;
            log("JIRA_AMBIGUO", o);
        } catch (Jira.Recusado erro) {
            if (o.issueKey == null) o.envio = Envio.PENDENTE;
            o.proximaTentativa = relogio.millis() + Math.max(INTERVALO_ENVIO_MS, erro.aguardarSegundos * 1000);
            log("JIRA_RECUSADO_HTTP_" + erro.status, o);
        } catch (IOException erro) {
            if (o.envio == Envio.ENVIANDO) o.envio = Envio.AMBIGUA;
            log("JIRA_PENDENTE", o);
        } catch (InterruptedException erro) {
            if (o.envio == Envio.ENVIANDO) o.envio = Envio.AMBIGUA;
            throw erro;
        } finally {
            estado.salvar();
        }
    }

    private void enviarSlack(Slot slot, Ocorrencia o) throws InterruptedException {
        if (o.revisaoSlack >= o.revisao || o.envioSlack == Envio.AMBIGUA || o.envioSlack == Envio.CANCELADA ||
              !podeEnviar(slot.ultimaTentativaSlack, o.proximaTentativaSlack)) return;
        slot.ultimaTentativaSlack = relogio.millis();
        o.envioSlack = Envio.ENVIANDO;
        estado.salvar();
        log("SLACK_TENTATIVA", o);
        try {
            slack.enviar(o, o.issueKey == null || jira == null ? null : jira.url(o.issueKey));
            o.revisaoSlack = o.revisao;
            o.envioSlack = Envio.ENVIADA;
            log("SLACK_CONFIRMADO", o);
        } catch (Jira.Recusado erro) {
            o.envioSlack = Envio.PENDENTE;
            o.proximaTentativaSlack = relogio.millis() + Math.max(INTERVALO_ENVIO_MS, erro.aguardarSegundos * 1000);
            log("SLACK_RECUSADO_HTTP_" + erro.status, o);
        } catch (IOException erro) {
            o.envioSlack = Envio.AMBIGUA;
            log("SLACK_AMBIGUO", o);
        } catch (InterruptedException erro) {
            o.envioSlack = Envio.AMBIGUA;
            throw erro;
        } finally {
            estado.salvar();
        }
    }

    private void log(String evento, Ocorrencia o) {
        var campos = new java.util.LinkedHashMap<String, Object>();
        campos.put("evento", evento);
        campos.put("ambiente", o.teste ? "homologacao" : "producao");
        campos.put("timestamp", relogio.instant().toString());
        campos.put("ocorrencia", o.id);
        campos.put("empresa", o.empresa);
        campos.put("servidor", o.servidor);
        campos.put("componente", o.componente);
        campos.put("metrica", o.tipo);
        campos.put("valor", o.valor);
        campos.put("limite", o.limite);
        campos.put("direcao", o.direcao);
        campos.put("unidade", o.unidade);
        campos.put("amostra", o.ultimaAmostra);
        campos.put("jira", o.issueKey);
        campos.put("revisao", o.revisao);
        campos.put("envio_jira", o.envio.name());
        campos.put("envio_slack", o.envioSlack.name());
        estado.registrarEvento(campos);
        try { System.out.println(mapper.writeValueAsString(campos)); }
        catch (IOException erro) { throw new IllegalStateException(erro); }
    }

    private static String identidade(String texto) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException erro) { throw new IllegalStateException(erro); }
    }
}
