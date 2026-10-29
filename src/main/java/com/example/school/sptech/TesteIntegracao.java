package com.example.school.sptech;

public class TesteIntegracao {

    public static void main(String[] args) {
        AlertaService alertas = new AlertaService();

        // Alerta crítico: vai para o canal de críticos e abre uma issue do tipo Bug
        alertas.dispararAlerta("Transcoding Server 01", "CPU", "95%", true);

        // Alerta de atenção: vai para o canal de atenção e abre uma issue do tipo Task
        alertas.dispararAlerta("CDN Edge 02", "Memória", "82%", false);
    }
}
