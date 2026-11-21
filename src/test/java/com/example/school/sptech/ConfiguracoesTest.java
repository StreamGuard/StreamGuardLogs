package com.example.school.sptech;

import org.junit.jupiter.api.Test;
import org.h2.tools.RunScript;
import java.io.*;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ConfiguracoesTest {
    @Test void consultaEsquemaAtualRespeitaVinculoServidorComponenteEMetrica() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        var relogio = new AlertasTest.Relogio();
        try (var banco = DriverManager.getConnection(url)) {
            try (var ddl = new InputStreamReader(getClass().getResourceAsStream("/esquema.sql"), java.nio.charset.StandardCharsets.UTF_8)) {
                RunScript.execute(banco, ddl);
            }
            RunScript.execute(banco, new StringReader("""
                INSERT INTO empresa (id_empresa,razao_social,cnpj,codigo_empresa) VALUES
                  (1,'Empresa A','11111111111111','AAAA0001'),(2,'Empresa B','22222222222222','BBBB0002');
                INSERT INTO equipamentos (id_equipamentos,hostname,codigo_agente,fk_empresa) VALUES
                  (1,'srv-a','agente-a',1),(2,'srv-b','agente-b',2);
                INSERT INTO componente (id_componente,nome,tipo) VALUES (1,'Processador','CPU');
                INSERT INTO equipamento_componente (id_equipamento_componente,fk_equipamento,fk_componente,status) VALUES
                  (1,1,1,'Ativo'),(2,2,1,'Ativo');
                INSERT INTO metrica (id_metrica,tipo,nome,unidade,fk_componente) VALUES (1,'USO_CPU','Uso CPU','%',1);
                INSERT INTO config_metrica (habilitado,intervalo_coleta,limite_maximo,fk_equipamento_componente,fk_metrica) VALUES
                  (1,5,80,1,1),(1,10,95,2,1);
                """));
            var fonte = new Configuracoes(() -> DriverManager.getConnection(url), relogio, 60);
            var valores = fonte.obter();
            assertEquals(2, valores.size());
            assertEquals(new BigDecimal("80.00"), valores.get("1|USO_CPU").maximo());
            assertEquals(new BigDecimal("95.00"), valores.get("2|USO_CPU").maximo());
            assertEquals(2, valores.get("2|USO_CPU").empresa());
            banco.createStatement().executeUpdate("UPDATE config_metrica SET habilitado=0 WHERE fk_equipamento_componente=1");
            relogio.avancar(59); assertTrue(fonte.obter().get("1|USO_CPU").habilitada());
            relogio.avancar(1); assertFalse(fonte.obter().get("1|USO_CPU").habilitada());
            banco.createStatement().executeUpdate("UPDATE equipamento_componente SET status='Inativo' WHERE id_equipamento_componente=2");
            relogio.avancar(60); assertFalse(fonte.obter().get("2|USO_CPU").habilitada());
            banco.createStatement().executeUpdate("DELETE FROM config_metrica WHERE fk_equipamento_componente=1");
            relogio.avancar(60); assertFalse(fonte.obter().containsKey("1|USO_CPU"));
            banco.createStatement().executeUpdate("UPDATE metrica SET tipo='Uso' WHERE id_metrica=1");
            relogio.avancar(60); assertTrue(fonte.obter().containsKey("2|USO_CPU"));

            boolean[] indisponivel = {false};
            var comFalha = new Configuracoes(() -> {
                if (indisponivel[0]) throw new SQLException("detalhe privado");
                return DriverManager.getConnection(url);
            }, relogio, 60);
            assertEquals(1, comFalha.obter().size());
            indisponivel[0] = true;
            relogio.avancar(60);
            assertThrows(IOException.class, comFalha::obter);
        }
    }

    @Test void consultaEsquemaLegadoDaEc2SemMigracao() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var banco = DriverManager.getConnection(url)) {
            RunScript.execute(banco, new StringReader("""
                CREATE TABLE empresa (id_empresa INT PRIMARY KEY);
                CREATE TABLE equipamentos (id_equipamentos INT PRIMARY KEY, hostname VARCHAR(100),
                  fk_equipamento INT REFERENCES empresa(id_empresa));
                CREATE TABLE componente (id_componente INT PRIMARY KEY, tipo VARCHAR(50),
                  fk_servidor INT REFERENCES equipamentos(id_equipamentos));
                CREATE TABLE metrica (id_metrica INT PRIMARY KEY, tipo VARCHAR(50), unidade VARCHAR(20),
                  fk_componente INT REFERENCES componente(id_componente));
                CREATE TABLE config_metrica (fk_metrica INT REFERENCES metrica(id_metrica), habilitado INT,
                  intervalo_coleta INT, limite_minimo DECIMAL(10,2), limite_maximo DECIMAL(10,2));
                INSERT INTO empresa VALUES (1),(2);
                INSERT INTO equipamentos VALUES (1,'srv-1',1),(2,'srv-2',2);
                INSERT INTO componente VALUES (1,'CPU',1),(2,'CPU',2);
                INSERT INTO metrica VALUES (1,'USO_CPU','%',1),(2,'USO_CPU','%',2);
                INSERT INTO config_metrica VALUES (1,1,5,NULL,85),(2,1,5,NULL,95);
                """));
            var fonte = new Configuracoes(() -> DriverManager.getConnection(url), new AlertasTest.Relogio(), 60);
            var valores = fonte.obter();
            assertEquals(2, valores.size());
            assertEquals(1, valores.get("1|USO_CPU").empresa());
            assertEquals(2, valores.get("2|USO_CPU").empresa());
            assertEquals(new BigDecimal("85.00"), valores.get("1|USO_CPU").maximo());
            assertEquals(new BigDecimal("95.00"), valores.get("2|USO_CPU").maximo());
        }
    }

    @Test void falhaAoRenovarCacheNaoRetornaConfiguracaoAntiga() throws Exception {
        var clock = new AlertasTest.Relogio();
        var fonte = new Configuracoes(() -> { throw new SQLException("senha privada"); }, clock, 60);
        var erro = assertThrows(IOException.class, fonte::obter);
        assertFalse(erro.getMessage().contains("senha privada"));
    }
}
