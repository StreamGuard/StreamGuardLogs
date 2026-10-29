
package com.example;

import com.example.school.sptech.AlertaService;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Main {

    public static void main(String[] args) throws InterruptedException {

        Double media_uso_cpu = 65.00;
        Double media_uso_disco = 55.00;
        Double media_uso_ram = 75.00;

        SecureRandom random = new SecureRandom();

        DateTimeFormatter formatter =
                DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

        // Cria o serviço de integração uma única vez.
        AlertaService alertas = new AlertaService();

        while (true) {

            Double uso_cpu = random.nextDouble(0.0, 100.0);
            Double uso_disco = random.nextDouble(0.0, 100.0);
            Double uso_ram = random.nextDouble(0.0, 100.0);

            LocalDateTime dataHora = LocalDateTime.now();
            String dataHoraFormatada = dataHora.format(formatter);

            System.out.println("\nColeta: " + dataHoraFormatada);

            verificarMetrica(
                    alertas, "CPU", uso_cpu, media_uso_cpu
            );

            verificarMetrica(
                    alertas, "Disco", uso_disco, media_uso_disco
            );

            verificarMetrica(
                    alertas, "RAM", uso_ram, media_uso_ram
            );

            Thread.sleep(10000);
        }
    }

    private static void verificarMetrica(
            AlertaService alertas,
            String metrica,
            Double uso,
            Double media) {

        if (uso <= media) {
            System.out.printf(
                    "%s: %.2f%% - Normal%n",
                    metrica, uso
            );
            return;
        }

        Double diferenca = uso - media;

        if (diferenca >= 20.0) {

            System.out.printf(
                    "[ALERTA] Servidor 1 - %s: %.2f%% "
                            + "(%.2f pontos acima da média)%n",
                    metrica, uso, diferenca
            );

            alertas.dispararAlerta(
                    "Servidor 1", metrica,
                    String.format("%.2f%%", uso), true
            );

        } else if (diferenca >= 10.0) {

            System.out.printf(
                    "[ATENÇÃO] Servidor 1 - %s: %.2f%% "
                            + "(%.2f pontos acima da média)%n",
                    metrica, uso, diferenca
            );

            alertas.dispararAlerta(
                    "Servidor 1", metrica,
                    String.format("%.2f%%", uso), false
            );

        } else {

            System.out.printf(
                    "%s: %.2f%% - Acima da média, "
                            + "mas abaixo do limite de alerta%n",
                    metrica, uso
            );
        }
    }
}
