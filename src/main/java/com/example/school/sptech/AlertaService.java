package com.example.school.sptech;

import com.example.school.sptech.Modelo.Configuracao;
import com.example.school.sptech.Modelo.Medicao;
import com.example.school.sptech.Estado.Envio;
import com.example.school.sptech.Estado.Ocorrencia;
import com.example.school.sptech.Estado.Slot;
import com.example.school.sptech.config.Jira;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;

public final class AlertaService {
    private final Silver silver;
    private final Configuracoes configuracoes;
    private final Estado estado;
    private final Jira jira;
    private final Clock relogio;
    private final int confirmarRecuperacao;
    private final long intervaloTentativa;

    public AlertaService(Silver silver, Configuracoes configuracoes, Estado estado, Jira jira,
                         Clock relogio, int confirmarRecuperacao, long intervaloTentativa, LocalDateTime inicio) {
        if (confirmarRecuperacao < 1 || intervaloTentativa < 1) throw new IllegalArgumentException();
        this.silver = silver;
        this.configuracoes = configuracoes;
        this.estado = estado;
        this.jira = jira;
        this.relogio = relogio;
        this.confirmarRecuperacao = confirmarRecuperacao;
        this.intervaloTentativa = intervaloTentativa;
        if (estado.dados.inicioProcessamento == null) estado.dados.inicioProcessamento = inicio.toString();
        estado.salvar();
    }

    public void ciclo() throws InterruptedException {
        var porServidor = new HashMap<Long, Map<String, Configuracao>>();
        var indisponiveis = new HashSet<Long>();
        for (String chave : estado.dados.metricas.keySet()) {
            long servidor = Long.parseLong(chave.substring(0, chave.indexOf('|')));
            carregarConfiguracao(servidor, porServidor, indisponiveis);
        }
        try {
            var nova = silver.obter(estado.dados.versaoSilver);
            if (nova.isPresent()) {
                var snapshot = nova.get();
                boolean completo = true;
                for (Medicao medicao : snapshot.medicoes()) {
                    carregarConfiguracao(medicao.servidor(), porServidor, indisponiveis);
                    if (indisponiveis.contains(medicao.servidor())) {
                        completo = false;
                        continue;
                    }
                    avaliar(medicao, porServidor.get(medicao.servidor()).get(medicao.tipo()));
                }
                if (completo) estado.dados.versaoSilver = snapshot.versao();
                if (snapshot.rejeitados() > 0) System.out.println("Silver: registros rejeitados=" + snapshot.rejeitados());
            }
        } catch (IOException erro) {
            System.err.println(erro.getMessage());
        }
        estado.salvar();
        if (jira != null) enviarPendentes(porServidor);
    }

    private void carregarConfiguracao(long servidor, Map<Long, Map<String, Configuracao>> valores,
                                     HashSet<Long> indisponiveis) throws InterruptedException {
        if (valores.containsKey(servidor) || indisponiveis.contains(servidor)) return;
        try {
            var atuais = configuracoes.obter(servidor);
            valores.put(servidor, atuais);
            for (var item : estado.dados.metricas.entrySet()) {
                if (item.getKey().startsWith(servidor + "|")) {
                    String tipo = item.getKey().substring(item.getKey().indexOf('|') + 1);
                    atualizarConfiguracao(item.getKey(), item.getValue(), atuais.get(tipo));
                }
            }
        } catch (IOException erro) {
            indisponiveis.add(servidor);
            System.err.println(erro.getMessage());
        }
    }

    private void atualizarConfiguracao(String chave, Slot slot, Configuracao config) {
        String assinatura = config == null ? "AUSENTE" : config.assinatura();
        if (!assinatura.equals(slot.configuracao)) {
            encerrar(slot, "CONFIGURACAO_ALTERADA", LocalDateTime.now(relogio).toString());
            for (var o : estado.dados.ocorrencias.values()) {
                if ((o.servidor + "|" + o.tipo).equals(chave) && o.envio == Envio.PENDENTE && !assinatura.equals(o.configuracao)) {
                    o.envio = Envio.CANCELADA;
                    o.cancelamento = "CONFIGURACAO_ALTERADA";
                }
            }
            slot.configuracao = assinatura;
            slot.recuperacoes = 0;
            slot.ultimaAvaliacao = null;
        }
    }

    private void avaliar(Medicao medicao, Configuracao config) {
        Slot slot = estado.dados.metricas.computeIfAbsent(medicao.chave(), chave -> new Slot());
        atualizarConfiguracao(medicao.chave(), slot, config);
        if (slot.ultimoVisto != null && !medicao.instante().isAfter(LocalDateTime.parse(slot.ultimoVisto))) return;
        slot.ultimoVisto = medicao.instante().toString();
        if (medicao.instante().isBefore(LocalDateTime.parse(estado.dados.inicioProcessamento))) return;
        if (!medicao.valida() || config == null || !config.aplicavel() || !config.unidade().equals(medicao.unidade())) {
            slot.recuperacoes = 0;
            return;
        }
        LocalDateTime ultima = slot.ultimaAvaliacao == null ? null : LocalDateTime.parse(slot.ultimaAvaliacao);
        if (ultima != null && medicao.instante().isBefore(ultima.plusSeconds(config.intervalo()))) return;
        if (ultima != null && medicao.instante().isAfter(ultima.plusSeconds(config.intervalo() * 3))) slot.recuperacoes = 0;
        slot.ultimaAvaliacao = medicao.instante().toString();
        String direcao = config.violacao(medicao.valor());
        if (direcao == null) {
            if (slot.ativa != null && ++slot.recuperacoes >= confirmarRecuperacao) {
                encerrar(slot, "RECUPERACAO_CONFIRMADA", medicao.instante().toString());
            }
            return;
        }
        slot.recuperacoes = 0;
        if (slot.ativa != null) return;
        Ocorrencia o = new Ocorrencia();
        o.id = identidade(medicao.chave() + "|" + medicao.instante() + "|" + config.assinatura());
        o.servidor = medicao.servidor();
        o.tipo = medicao.tipo();
        o.unidade = medicao.unidade();
        o.instante = medicao.instante().toString();
        o.valor = Modelo.texto(medicao.valor());
        o.limite = Modelo.texto(config.limite(direcao));
        o.direcao = direcao;
        o.configuracao = config.assinatura();
        estado.dados.ocorrencias.put(o.id, o);
        slot.ativa = o.id;
        System.out.println("Ocorrência iniciada: " + o.servidor + " " + o.tipo + " " + o.id);
    }

    private void encerrar(Slot slot, String motivo, String instante) {
        if (slot.ativa == null) return;
        var o = estado.dados.ocorrencias.get(slot.ativa);
        if (o == null) throw new IllegalStateException("Estado referencia ocorrência ausente.");
        o.encerradaEm = instante;
        o.encerramento = motivo;
        if (o.envio == Envio.PENDENTE && motivo.equals("CONFIGURACAO_ALTERADA")) {
            o.envio = Envio.CANCELADA;
            o.cancelamento = motivo;
        }
        slot.ativa = null;
        slot.recuperacoes = 0;
        System.out.println("Ocorrência encerrada: " + o.id + " " + motivo);
    }

    private void enviarPendentes(Map<Long, Map<String, Configuracao>> atuais) throws InterruptedException {
        for (var o : estado.dados.ocorrencias.values()) {
            if (o.envio == Envio.ENVIADA || o.envio == Envio.CANCELADA || o.proximaTentativa > relogio.millis()) continue;
            if (o.envio == Envio.PENDENTE) {
                var porTipo = atuais.get(o.servidor);
                var config = porTipo == null ? null : porTipo.get(o.tipo);
                if (config == null || !config.aplicavel() || !config.assinatura().equals(o.configuracao)) continue;
            }
            o.proximaTentativa = relogio.millis() + intervaloTentativa * 1000;
            String existente;
            try {
                existente = jira.buscar(o.id);
            } catch (Jira.MultiplosChamados erro) {
                o.envio = Envio.AMBIGUA;
                estado.salvar();
                System.err.println("Mais de um chamado para ocorrência " + o.id + "; revisão manual necessária.");
                continue;
            } catch (IOException erro) {
                estado.salvar();
                System.err.println("Reconciliação Jira indisponível para " + o.id + ".");
                continue;
            }
            if (existente != null) {
                o.issueKey = existente;
                o.envio = Envio.ENVIADA;
                estado.salvar();
                continue;
            }
            if (o.envio == Envio.AMBIGUA || o.envio == Envio.ENVIANDO) {
                estado.salvar();
                continue;
            }
            o.envio = Envio.ENVIANDO;
            estado.salvar();
            try {
                o.issueKey = jira.criar(o);
                o.envio = Envio.ENVIADA;
            } catch (Jira.Recusado erro) {
                o.envio = Envio.PENDENTE;
                o.proximaTentativa = Math.max(o.proximaTentativa, relogio.millis() + erro.aguardarSegundos * 1000);
                System.err.println("Jira recusou ocorrência " + o.id + ", HTTP " + erro.status + ".");
            } catch (IOException erro) {
                o.envio = Envio.AMBIGUA;
                System.err.println("Resposta Jira ambígua para " + o.id + "; somente reconciliação será repetida.");
            } catch (InterruptedException erro) {
                o.envio = Envio.AMBIGUA;
                estado.salvar();
                throw erro;
            }
            estado.salvar();
        }
    }

    private static String identidade(String texto) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException erro) {
            throw new IllegalStateException(erro);
        }
    }
}
