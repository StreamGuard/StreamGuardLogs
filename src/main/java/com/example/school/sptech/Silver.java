package com.example.school.sptech;

import com.example.school.sptech.Modelo.Medicao;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.DuplicateHeaderMode;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

public final class Silver {
    public static final String CHAVE = "silver/atual/metricas.csv";
    private static final DateTimeFormatter DATA =
          DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    public record Snapshot(String versao, List<Medicao> medicoes, long rejeitados) {}

    private final S3Client s3;
    private final String bucket;
    private final long maxBytes;
    private Snapshot cache;

    public Silver(S3Client s3, String bucket, long maxBytes) {
        this.s3 = s3;
        this.bucket = bucket;
        this.maxBytes = maxBytes;
    }

    public Optional<Snapshot> obter(String processada) throws IOException {
        try {
            var cabecalho = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(CHAVE).build());
            String versao = cabecalho.eTag();
            if (versao == null || cabecalho.contentLength() == null || cabecalho.contentLength() > maxBytes) {
                throw new IOException("Silver sem versão/tamanho válido ou acima de SILVER_MAX_BYTES.");
            }
            if (versao.equals(processada)) return Optional.empty();
            if (cache != null && cache.versao().equals(versao)) return Optional.of(cache);
            var pedido = GetObjectRequest.builder().bucket(bucket).key(CHAVE).ifMatch(versao).build();
            try (var entrada = s3.getObject(pedido)) {
                if (!versao.equals(entrada.response().eTag())) throw new IOException("Silver mudou durante a leitura.");
                cache = ler(new InputStreamReader(entrada, StandardCharsets.UTF_8), versao);
                return Optional.of(cache);
            }
        } catch (S3Exception erro) {
            throw new IOException("Falha de leitura Silver, HTTP " + erro.statusCode() + ".", erro);
        } catch (software.amazon.awssdk.core.exception.SdkException erro) {
            throw new IOException("Falha temporária de comunicação com a Silver.", erro);
        }
    }

    public static Snapshot ler(Reader entrada, String versao) throws IOException {
        var leitor = new PushbackReader(entrada, 1);
        int primeiro = leitor.read();
        if (primeiro != -1 && primeiro != '\ufeff') leitor.unread(primeiro);
        var formato = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true)
              .setIgnoreEmptyLines(true).setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).get();
        var unicas = new LinkedHashMap<String, Medicao>();
        long rejeitados = 0;
        try (var parser = formato.parse(leitor)) {
            var nomes = parser.getHeaderNames();
            var esperadas = List.of("servidor_id", "timestamp", "tipo", "valor", "unidade");
            if (nomes.size() != 5 || !nomes.containsAll(esperadas)) throw new IOException("Schema Silver incompatível.");
            for (var linha : parser) {
                try {
                    if (!linha.isConsistent()) throw new IllegalArgumentException();
                    long servidor = Long.parseLong(linha.get("servidor_id"));
                    var instante = LocalDateTime.parse(linha.get("timestamp"), DATA);
                    String tipo = linha.get("tipo"), unidade = linha.get("unidade");
                    if (servidor <= 0 || servidor > Integer.MAX_VALUE || !Modelo.UNIDADES.containsKey(tipo)) {
                        throw new IllegalArgumentException();
                    }
                    BigDecimal valor;
                    try { valor = new BigDecimal(linha.get("valor")); }
                    catch (NumberFormatException erro) { valor = null; }
                    var medicao = new Medicao(servidor, instante, tipo, valor, unidade);
                    if (!medicao.valida()) rejeitados++;
                    String identidade = medicao.chave() + "|" + instante;
                    var anterior = unicas.putIfAbsent(identidade, medicao);
                    if (anterior != null && (anterior.valor() == null || medicao.valor() == null ||
                          anterior.valor().compareTo(medicao.valor()) != 0 || !anterior.unidade().equals(unidade))) {
                        unicas.put(identidade, new Medicao(servidor, instante, tipo, null, unidade));
                        rejeitados++;
                    }
                } catch (IllegalArgumentException | java.time.DateTimeException erro) {
                    rejeitados++;
                }
            }
        } catch (RuntimeException erro) {
            throw new IOException("CSV Silver malformado; checkpoint não avançado.", erro);
        }
        var medicoes = new ArrayList<>(unicas.values());
        medicoes.sort(Comparator.comparing(Medicao::instante).thenComparingLong(Medicao::servidor).thenComparing(Medicao::tipo));
        return new Snapshot(versao, medicoes, rejeitados);
    }
}
