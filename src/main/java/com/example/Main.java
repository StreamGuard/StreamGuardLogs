import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        Double media_uso_cpu = 65.00;
        Double media_uso_disco = 55.00;
        Double media_uso_ram = 75.00;
        SecureRandom random = new SecureRandom();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

        while(true) {
            Double uso_cpu = random.nextDouble(0.0, 100.0);
            Double uso_disco = random.nextDouble(0.0,100.0);
            Double uso_ram = random.nextDouble(0.0,100.0);
            LocalDateTime dataHora = LocalDateTime.now();
            String dataHoraFormatada = dataHora.format(formatter);
            if (uso_cpu > media_uso_cpu) {
                Double diferenca = uso_cpu - media_uso_cpu;
                if(diferenca >= 20.0  && media_uso_cpu + diferenca <= 100.00) {
                    System.out.printf("[ALERTA] %s - Alerta gerado no servidor 1 (CPU com uso %.2f%% acima da média!)%n", dataHoraFormatada, diferenca);
                } else if (diferenca >= 10.00 && media_uso_cpu + diferenca <= 100.00) {
                    System.out.printf("[ATENCAO] %s - Ponto de atencao gerado no servidor 1 (CPU com uso %.2f%% acima da média!) %n", dataHoraFormatada, diferenca);
                }
            }

            if(uso_disco > media_uso_disco){
                Double diferenca = uso_disco - media_uso_disco;
                if(diferenca >= 20.0 && media_uso_disco + diferenca <= 100.00){
                    System.out.printf("[ALERTA] %s - Alerta gerado no servidor 1 (disco com uso %.2f%% acima da média!)  %n", dataHoraFormatada, diferenca);
                }else if (diferenca >= 10.00  && media_uso_disco + diferenca <= 100.00) {
                    System.out.printf("[ATENCAO] %s - Ponto de atencao gerado no servidor 1 (disco com uso %.2f%% acima da média!)%n", dataHoraFormatada, diferenca);
                }
            }

            if(uso_ram> media_uso_ram){
                Double diferenca = uso_ram - media_uso_ram;
                if(diferenca >= 20.0 && media_uso_ram + diferenca <= 100.0){
                    System.out.printf("[ALERTA] %s - Alerta gerado no servidor 1 (RAM com uso %.2f%% acima da média!) %n", dataHoraFormatada, diferenca);
                }else if (diferenca >= 10.00 && media_uso_ram + diferenca <= 100.0) {
                    System.out.printf("[ATENCAO] %s - Ponto de atencao gerado no servidor 1 (RAM com uso %.2f%% acima da média!)%n", dataHoraFormatada, diferenca);
                }
            }

            Thread.sleep(10000);
        }

    }

}
