package com.example.school.sptech;

import com.example.school.sptech.Modelo.Configuracao;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/** Uma consulta por ciclo, usando os vínculos do esquema atual. */
public final class Configuracoes {
    @FunctionalInterface
    public interface Conexao { Connection abrir() throws SQLException; }
    @FunctionalInterface
    public interface Fonte { Map<String, Configuracao> obter() throws IOException; }

    public static final String SQL = """
        SELECT e.id_equipamentos AS servidor, e.fk_empresa AS empresa, e.hostname,
               c.tipo AS componente, ec.status AS status_componente,
               m.tipo, m.unidade, cm.habilitado, cm.intervalo_coleta,
               cm.limite_minimo, cm.limite_maximo
        FROM config_metrica cm
        JOIN equipamento_componente ec ON ec.id_equipamento_componente = cm.fk_equipamento_componente
        JOIN equipamentos e ON e.id_equipamentos = ec.fk_equipamento
        JOIN componente c ON c.id_componente = ec.fk_componente
        JOIN metrica m ON m.id_metrica = cm.fk_metrica AND m.fk_componente = c.id_componente
        ORDER BY e.id_equipamentos, m.tipo
        """;
    private final Conexao conexao;
    private static final String SQL_LEGADO = """
        SELECT e.id_equipamentos AS servidor, e.fk_equipamento AS empresa, e.hostname,
               c.tipo AS componente, 'Ativo' AS status_componente,
               m.tipo, m.unidade, cm.habilitado, cm.intervalo_coleta,
               cm.limite_minimo, cm.limite_maximo
        FROM config_metrica cm
        JOIN metrica m ON m.id_metrica = cm.fk_metrica
        JOIN componente c ON c.id_componente = m.fk_componente
        JOIN equipamentos e ON e.id_equipamentos = c.fk_servidor
        ORDER BY e.id_equipamentos, m.tipo
        """;

    private static String consulta(Connection conexao) throws SQLException, IOException {
        var colunas = new java.util.HashSet<String>();
        var meta = conexao.getMetaData();
        try (var campos = meta.getColumns(conexao.getCatalog(), null, "%", "%")) {
            while (campos.next()) {
                colunas.add((campos.getString("TABLE_NAME") + "." + campos.getString("COLUMN_NAME"))
                      .toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (colunas.contains("config_metrica.fk_equipamento_componente") && colunas.contains("equipamentos.fk_empresa")) return SQL;
        if (!colunas.contains("config_metrica.fk_equipamento_componente") &&
              colunas.contains("componente.fk_servidor") && colunas.contains("equipamentos.fk_equipamento")) {
            for (String tabela : java.util.List.of("equipamentos", "EQUIPAMENTOS")) {
                // Confirmar que o nome legado fk_equipamento referencia a empresa.
                try (var vinculos = meta.getImportedKeys(conexao.getCatalog(), null, tabela)) {
                    while (vinculos.next()) {
                        if ("fk_equipamento".equalsIgnoreCase(vinculos.getString("FKCOLUMN_NAME")) &&
                              "empresa".equalsIgnoreCase(vinculos.getString("PKTABLE_NAME"))) return SQL_LEGADO;
                    }
                }
            }
        }
        throw new IOException("Esquema MySQL não reconhecido; envios suspensos sem alterar o banco.");
    }
    private final Clock relogio;
    private final long ttl;
    private long carregadaEm;
    private Map<String, Configuracao> cache;

    public Configuracoes(Conexao conexao, Clock relogio, long ttlSegundos) {
        if (ttlSegundos < 1) throw new IllegalArgumentException("Intervalo de configuração inválido.");
        this.conexao = conexao;
        this.relogio = relogio;
        this.ttl = Math.multiplyExact(ttlSegundos, 1000);
    }

    public Map<String, Configuracao> obter() throws IOException {
        long agora = relogio.millis();
        if (cache != null && agora >= carregadaEm && agora - carregadaEm < ttl) return cache;
        var valores = new LinkedHashMap<String, Configuracao>();
        try (var c = conexao.abrir(); var consulta = c.prepareStatement(consulta(c))) {
            consulta.setQueryTimeout(15);
            try (var linhas = consulta.executeQuery()) {
                while (linhas.next()) {
                    String tipo = linhas.getString("tipo"), unidade = linhas.getString("unidade");
                    // O painel legado grava "Uso"; porcentagem + componente identifica a leitura sem ambiguidade.
                    if ("Uso".equalsIgnoreCase(tipo) && "%".equals(unidade)) {
                        tipo = switch (linhas.getString("componente").toUpperCase(java.util.Locale.ROOT)) {
                            case "CPU" -> "USO_CPU";
                            case "RAM" -> "USO_RAM";
                            case "DISCO", "ARMAZENAMENTO" -> "USO_DISCO";
                            default -> tipo;
                        };
                    }
                    if (!Modelo.UNIDADES.containsKey(tipo)) {
                        System.err.println("Configuração sem correspondência na Silver: servidor=" + linhas.getLong("servidor") + ".");
                        continue;
                    }
                    long servidor = linhas.getLong("servidor"), empresa = linhas.getLong("empresa");
                    int habilitado = linhas.getInt("habilitado");
                    long intervalo = linhas.getLong("intervalo_coleta");
                    var minimo = linhas.getBigDecimal("limite_minimo");
                    var maximo = linhas.getBigDecimal("limite_maximo");
                    if (servidor < 1 || empresa < 1 || !Modelo.UNIDADES.get(tipo).equals(unidade) ||
                          habilitado < 0 || habilitado > 1 || intervalo < 1 ||
                          (minimo != null && minimo.signum() < 0) || (maximo != null && maximo.signum() < 0) ||
                          (minimo != null && maximo != null && minimo.compareTo(maximo) > 0) ||
                          (unidade.equals("%") && ((minimo != null && minimo.doubleValue() > 100) ||
                                (maximo != null && maximo.doubleValue() > 100)))) {
                        throw new IOException("Configuração inválida para servidor " + servidor + ".");
                    }
                    String status = linhas.getString("status_componente");
                    boolean ativo = status == null || status.equalsIgnoreCase("Ativo");
                    var config = new Configuracao(tipo, unidade, habilitado == 1 && ativo, intervalo,
                          minimo, maximo, empresa, linhas.getString("hostname"), linhas.getString("componente"));
                    if (valores.putIfAbsent(servidor + "|" + tipo, config) != null) {
                        throw new IOException("Configuração duplicada para servidor " + servidor + " e métrica " + tipo + ".");
                    }
                }
            }
        } catch (SQLException erro) {
            throw new IOException("Consulta de config_metrica indisponível; envios suspensos.");
        }
        cache = Map.copyOf(valores);
        carregadaEm = agora;
        return cache;
    }
}
