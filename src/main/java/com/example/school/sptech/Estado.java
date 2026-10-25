package com.example.school.sptech;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Estado implements AutoCloseable {
    public enum Envio { PENDENTE, ENVIANDO, AMBIGUA, ENVIADA, CANCELADA }

    public static class Slot {
        public String ultimoVisto;
        public String ultimaAvaliacao;
        public String configuracao;
        public String ativa;
        public int recuperacoes;
    }

    public static class Ocorrencia {
        public String id;
        public long servidor;
        public String tipo;
        public String unidade;
        public String instante;
        public String valor;
        public String limite;
        public String direcao;
        public String encerradaEm;
        public String encerramento;
        public String configuracao;
        public String cancelamento;
        public Envio envio = Envio.PENDENTE;
        public String issueKey;
        public long proximaTentativa;
    }

    public static class Dados {
        public int formato = 1;
        public String versaoSilver;
        public String inicioProcessamento;
        public Map<String, Slot> metricas = new LinkedHashMap<>();
        public Map<String, Ocorrencia> ocorrencias = new LinkedHashMap<>();
    }

    private final Path arquivo;
    private final ObjectMapper mapper = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(SerializationFeature.INDENT_OUTPUT);
    private final FileChannel canal;
    private final FileLock trava;
    private byte[] ultimoConteudo;
    public final Dados dados;

    public Estado(Path diretorio) throws IOException {
        Files.createDirectories(diretorio);
        arquivo = diretorio.resolve("estado.json");
        canal = FileChannel.open(diretorio.resolve("execucao.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock obtida;
        try {
            obtida = canal.tryLock();
            if (obtida == null) throw new IOException("Outro processo usa este diretório de estado.");
        } catch (Exception erro) {
            canal.close();
            throw new IOException("Não foi possível obter a trava de execução.", erro);
        }
        trava = obtida;
        try {
            if (Files.exists(arquivo)) {
                ultimoConteudo = Files.readAllBytes(arquivo);
                var arvore = mapper.readTree(ultimoConteudo);
                if (arvore == null || arvore.path("formato").asInt() != 1 ||
                      !arvore.path("metricas").isObject() || !arvore.path("ocorrencias").isObject()) {
                    throw new IOException("Formato de estado inválido; restaure um backup, sem apagar o checkpoint.");
                }
                dados = mapper.treeToValue(arvore, Dados.class);
                for (var item : dados.ocorrencias.entrySet()) {
                    var o = item.getValue();
                    if (o == null || !item.getKey().equals(o.id) || o.envio == null ||
                          (o.envio == Envio.ENVIADA && (o.issueKey == null || o.issueKey.isBlank()))) {
                        throw new IOException("Ocorrência persistida inválida.");
                    }
                    if (o.envio == Envio.ENVIANDO) o.envio = Envio.AMBIGUA;
                }
            } else {
                dados = new Dados();
            }
        } catch (Exception erro) {
            trava.release();
            canal.close();
            throw new IOException("Estado indisponível ou corrompido; execução interrompida.", erro);
        }
    }

    public void salvar() {
        Path temporario = arquivo.resolveSibling("estado.json.tmp");
        try {
            byte[] bytes = mapper.writeValueAsBytes(dados);
            if (java.util.Arrays.equals(ultimoConteudo, bytes) && Files.isRegularFile(arquivo)) return;
            try (FileChannel saida = FileChannel.open(temporario, StandardOpenOption.CREATE,
                  StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) saida.write(buffer);
                saida.force(true);
            }
            Files.move(temporario, arquivo, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            ultimoConteudo = bytes;
        } catch (IOException erro) {
            throw new IllegalStateException("Falha ao persistir estado; não prossiga com envios.", erro);
        }
    }

    @Override
    public void close() throws IOException {
        trava.release();
        canal.close();
    }
}
