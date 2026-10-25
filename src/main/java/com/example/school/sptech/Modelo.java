package com.example.school.sptech;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public final class Modelo {
    public static final Map<String, String> UNIDADES = Map.ofEntries(
          Map.entry("USO_CPU", "%"), Map.entry("USO_RAM", "%"),
          Map.entry("USO_DISCO", "%"), Map.entry("RAM_DISPONIVEL", "bytes"),
          Map.entry("CARGA_CPU_1M", "load"), Map.entry("CARGA_CPU_5M", "load"),
          Map.entry("CARGA_CPU_15M", "load"),
          Map.entry("TAXA_LEITURA_DISCO", "bytes/s"), Map.entry("TAXA_ESCRITA_DISCO", "bytes/s"),
          Map.entry("TAXA_ENVIO_REDE", "bytes/s"), Map.entry("TAXA_RECEBIMENTO_REDE", "bytes/s"));

    public record Medicao(long servidor, LocalDateTime instante, String tipo, BigDecimal valor, String unidade) {
        public String chave() { return servidor + "|" + tipo; }

        public boolean valida() {
            return valor != null && Double.isFinite(valor.doubleValue()) && Math.abs((long) valor.scale()) <= 340 && valor.signum() >= 0 && unidade.equals(UNIDADES.get(tipo))
                  && (!unidade.equals("%") || valor.compareTo(BigDecimal.valueOf(100)) <= 0);
        }
    }

    public record Configuracao(String tipo, String unidade, boolean habilitada, long intervalo,
                                BigDecimal minimo, BigDecimal maximo) {
        public boolean aplicavel() { return habilitada && (minimo != null || maximo != null); }

        public String assinatura() {
            return unidade + "|" + habilitada + "|" + intervalo + "|" + texto(minimo) + "|" + texto(maximo);
        }

        public String violacao(BigDecimal valor) {
            if (minimo != null && valor.compareTo(minimo) < 0) return "MINIMO";
            if (maximo != null && valor.compareTo(maximo) > 0) return "MAXIMO";
            return null;
        }

        public BigDecimal limite(String direcao) { return direcao.equals("MINIMO") ? minimo : maximo; }
    }

    public static String texto(BigDecimal valor) {
        return valor == null ? "NULL" : valor.stripTrailingZeros().toPlainString();
    }

    private Modelo() {}
}
