package com.example.school.sptech;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.core.ResponseInputStream;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SilverTest {
    @Test void consultaParticoesDaEmpresaUsaIfMatchEReaproveitaETag() throws Exception {
        var clock = new AlertasTest.Relogio();
        var chaves = new ArrayList<String>();
        int[] downloads = {0};
        String csv = "servidor_id,timestamp,tipo,valor,unidade\n1,2026-11-19 12:00:00,USO_CPU,99,%\n";
        var s3 = (S3Client) Proxy.newProxyInstance(S3Client.class.getClassLoader(), new Class[]{S3Client.class}, (proxy, metodo, args) -> {
            if (metodo.getName().equals("headObject")) {
                var pedido = (HeadObjectRequest) args[0];
                chaves.add(pedido.key());
                if (pedido.key().contains("2026-11-18")) throw S3Exception.builder().statusCode(404).build();
                return HeadObjectResponse.builder().eTag("etag").contentLength((long) csv.length()).build();
            }
            if (metodo.getName().equals("getObject")) {
                var pedido = (GetObjectRequest) args[0];
                assertEquals("etag", pedido.ifMatch());
                downloads[0]++;
                return new ResponseInputStream<>(GetObjectResponse.builder().eTag("etag").build(),
                      new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
            }
            if (metodo.getName().equals("close")) return null;
            throw new UnsupportedOperationException(metodo.getName());
        });
        var fonte = new Silver(s3, "bucket-teste", 1024, clock, null);
        var partes = fonte.obter(Set.of(1L, 2L));
        assertEquals(2, partes.size());
        assertEquals(Set.of(1L, 2L), partes.stream().map(Silver.Particao::empresa).collect(java.util.stream.Collectors.toSet()));
        assertTrue(chaves.contains("silver/1/2026-11-19/metricas.csv"));
        assertTrue(chaves.contains("silver/2/2026-11-18/metricas.csv"));
        assertEquals(2, downloads[0]);
        fonte.obter(Set.of(1L, 2L));
        assertEquals(2, downloads[0]);
        assertEquals(8, chaves.size());
    }
}
