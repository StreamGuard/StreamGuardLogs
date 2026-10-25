# StreamGuardLogs

Aplicação Java 21 que avalia métricas da Silver pelos limites cadastrados no StreamGuard e prepara chamados no Jira. A notificação no Slack pertence à automação do Jira.

```text
Captura Python real/simulada -> Bronze -> ETL Pandas -> Silver -> Java -> Jira -> Slack
```

## Funcionamento

O Java consulta `silver/atual/metricas.csv` no bucket configurado. O CSV deve conter `servidor_id,timestamp,tipo,valor,unidade`, conforme a ETL. A Gold e o CSV de processos não são fontes deste avaliador. Não há geração de métricas no Java.

O ETag evita downloads repetidos. O GET S3 usa If-Match para recusar um arquivo alterado durante a leitura. O conteúdo é lido como UTF-8, validado e ordenado; registros inválidos são rejeitados, duplicatas iguais são ignoradas e identidades conflitantes não escolhem arbitrariamente um valor.

As configurações vêm de `GET /metricas/configuracoes/:servidor_id`, sem novo endpoint ou acesso direto ao MySQL. A consulta é agrupada por servidor e possui cache curto. Configurações ausentes, desabilitadas ou sem limites aplicáveis não produzem alertas. Se o cache vencer e a API falhar, o servidor aguarda uma configuração válida; não são inventados limites.

Uma violação ocorre abaixo do mínimo ou acima do máximo. Igualdade com o limite é permitida. Percentuais, bytes, bytes/s e carga mantêm suas próprias unidades. Os intervalos de coleta configurados controlam a distância mínima entre avaliações; o polling não substitui esses intervalos.

## Ocorrências e persistência

Cada servidor/tipo possui checkpoint e no máximo uma ocorrência ativa. Ela começa na primeira violação e continua sem novos chamados enquanto não houver recuperação confirmada. Por padrão, duas amostras válidas na faixa permitida confirmam a recuperação; intervalos excessivos ou registros inválidos interrompem essa contagem.

A recuperação é registrada localmente e permite uma nova ocorrência. O Java não fecha ou transiciona chamados automaticamente: isso depende do workflow do Jira. Mudanças de configuração encerram o contexto anterior e cancelam pendências ainda não enviadas com limites antigos. Amostras já processadas não são reaplicadas com um limite novo.

`ESTADO_DIRETORIO/estado.json` contém checkpoints, ocorrências e confirmações Jira. A escrita usa arquivo temporário, force e troca atômica no mesmo diretório. A trava impede dois processos no mesmo diretório. Falhas de persistência interrompem a execução; estado corrompido não é substituído por um estado vazio.

Use uma instância por fluxo e preserve esse diretório em disco durável nas reinicializações. Não execute réplicas com diretórios independentes para a mesma Silver. Não apague nem edite o estado para repetir um envio.

Na primeira execução, apenas amostras a partir do início do monitoramento geram ocorrências. O instante inicial é persistido. `ALERTAS_DESDE` permite definir explicitamente outro início em um diretório novo, inclusive para replay controlado. Alterá-lo não redefine um estado existente. Timestamps anteriores ao checkpoint de cada métrica são ignorados, incluindo chegadas tardias; não há reavaliação retroativa de chamados.

## Jira

O envio fica desligado por padrão. `JIRA_ENVIAR=false` impede tanto busca quanto criação no Jira, mas a leitura real do S3 e da API continua ativa.

Quando habilitado, o cliente reutiliza os DTOs existentes e envia projeto, tipo, resumo, descrição ADF e labels. O contexto informa servidor, métrica, valor, limite, unidade, horário e identificação estável da ocorrência. A autenticação vem de e-mail/token privados, via HTTPS; HTTP só é aceito para loopback nos testes.

Antes do POST, busca o identificador em `/rest/api/3/search/jql`. O estado ENVIANDO é gravado antes de transmitir. Só HTTP 201 com chave válida, ou uma busca que confirme um único chamado, marca a ocorrência ENVIADA.

Rejeições conhecidas (400, 401, 403, 404, 405, 413, 415, 422 e 429) permitem nova tentativa após espera e nova busca; Retry-After em segundos é respeitado até 24 horas. Timeout, 5xx, respostas desconhecidas ou sucesso sem chave ficam AMBIGUA. Nessas situações, o Java repete somente a busca, sem repetir o POST cegamente. Se não houver confirmação ou existirem múltiplos resultados, a pendência exige revisão manual no Jira; não apague o checkpoint.

Não existe garantia distribuída de exatamente uma vez entre Jira e arquivo local. A estratégia prioriza evitar duplicações, podendo manter uma pendência sem reenviar quando o resultado for incerto. A indexação de busca do Jira pode ter atraso.

## Configuração e execução

Requer JDK 21, Maven, acesso de leitura ao objeto Silver no S3, API disponível e diretório de estado gravável. As dependências operacionais são Jackson, Apache Commons CSV e os módulos S3/HTTP do AWS SDK Java v2.

`.env.example` lista as variáveis; Java não carrega esse arquivo automaticamente. Configure-as no ambiente do processo. Credenciais AWS devem vir da cadeia padrão do SDK, preferencialmente função IAM na EC2 ou perfil local. Não coloque chaves no código ou em arquivos versionados.

| Variável | Uso/padrão |
|---|---|
| S3_BUCKET | Bucket da Silver, sem s3://; obrigatório. |
| AWS_REGION / AWS_DEFAULT_REGION | Região; padrão us-east-1. |
| WEB_DATA_VIZ_URL | Base da API; obrigatório. |
| CAPTURA_FUSO | Fuso dos timestamps sem offset da captura; padrão America/Sao_Paulo. Deve coincidir com a captura. |
| ESTADO_DIRETORIO | Diretório durável; padrão estado. |
| ALERTAS_INTERVALO_SEGUNDOS | Polling da Silver; padrão 15. |
| CONFIG_INTERVALO_SEGUNDOS | Validade das configurações; padrão 60. |
| RECUPERACAO_AMOSTRAS | Confirmação da recuperação; padrão 2. |
| SILVER_MAX_BYTES | Tamanho máximo aceito; padrão 67108864 (64 MiB). |
| ALERTAS_DESDE | Início ISO local opcional, sem offset; padrão início da primeira execução. |
| JIRA_ENVIAR | true ou false; padrão false. |
| JIRA_BASE_URL | Site Jira Cloud; obrigatório ao habilitar envio. |
| JIRA_EMAIL / JIRA_API_TOKEN | Conta técnica e token privados. |
| JIRA_PROJECT_KEY | Chave do projeto autorizado. |
| JIRA_ISSUE_TYPE | Nome do tipo permitido no projeto; padrão Task. |
| JIRA_TIMEOUT_SEGUNDOS | Timeout HTTP; padrão 30. |
| JIRA_RETRY_SEGUNDOS | Espera mínima entre tentativas; padrão 60. |

```shell
mvn package
java -jar target/StreamGuardLogs-1.0-SNAPSHOT-jar-with-dependencies.jar
```

Encerre com Ctrl+C. A trava do sistema operacional é liberada ao terminar; o arquivo lock pode permanecer e não precisa ser apagado. Em Windows, se necessário, passe as opções de console UTF-8 entre aspas: `"-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8"` antes de `-jar`.

## Automação Jira -> Slack

Solicite ao responsável a URL do Jira Cloud, projeto, tipo de chamado, e-mail/token da conta técnica e acesso para criar e pesquisar chamados. Confirme permissões Browse Projects/Create Issues, visibilidade das issues e disponibilidade dos campos Summary, Description e Labels na criação. Verifique campos obrigatórios adicionais antes de habilitar o envio.

No Jira Automation, configure um gatilho de issue criada no projeto, condição label `streamguard` e ação Send Slack message para o canal aprovado. Autorize a conexão do workspace ou o incoming webhook exigido pela ação, armazenando esse segredo no Jira, não no Java. Uma mensagem pode usar `{{issue.key}}`, `{{issue.summary}}` e `{{issue.url}}`. Verifique também o ator, escopo, permissões, limites da automação e audit log.

O Java não usa webhooks Slack. A existência e a entrega da regra externa precisam ser confirmadas com os administradores. Referência: [automação oficial com Slack](https://support.atlassian.com/cloud-automation/docs/use-automation-with-slack/).

## Homologação controlada

1. Obtenha autorização explícita antes de habilitar qualquer envio real. Confirme projeto, canal, tipo e campos com o responsável.
2. Mantenha JIRA_ENVIAR=false enquanto verifica S3, API, fuso e estado. Esse modo ainda acessa S3/API reais quando executado com suas configurações.
3. Combine uma janela com uma única violação controlada, usando os limites editáveis do sistema. Evite outras métricas/servidores em violação na mesma janela.
4. Pare a instância anterior e use um diretório dedicado de homologação, com início atual. Inspecione as pendências antes de ativar envio; habilitar um estado antigo pode enviar ocorrências acumuladas.
5. Configure as credenciais privadas, habilite JIRA_ENVIAR e acompanhe o estado até confirmar a chave de um chamado. Interrompa a execução após a validação combinada.
6. Confira o conteúdo no Jira, o audit log da regra e uma única mensagem no canal Slack. Reinicie com o mesmo estado para conferir a ausência de duplicação e confirme recuperação local.
7. Em erro ou ambiguidade, desabilite envio e revise o Jira pelo label da ocorrência. Não remova o estado para tentar novamente.

A leitura S3 com IAM, os campos/permissões do projeto Jira, a automação Slack e o comportamento na infraestrutura real permanecem sujeitos à homologação. Testes locais usam mocks e CSVs fora deste repositório; não comprovam entrega real. Não há configuração Docker neste projeto.
