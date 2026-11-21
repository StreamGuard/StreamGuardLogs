# StreamGuardLogs

Serviço Java responsável por avaliar métricas da camada Silver do S3, consultar os limites configurados no MySQL e enviar alertas ao Jira e ao Slack. Executa em container Docker na EC2 e mantém o histórico das ocorrências em volume persistente.

## Fluxo de processamento

```text
Captura → S3 Bronze → ETL → S3 Silver → Java → Jira e Slack
                                        ↑
                               MySQL / config_metrica
```

O serviço consulta `config_metrica` a cada 60 segundos e lê os arquivos `silver/<empresa>/<dia>/metricas.csv`. A avaliação considera a amostra mais recente por servidor e tipo de métrica, os limites mínimo e máximo, a unidade e o estado da configuração.

São aceitos os dois esquemas de banco utilizados pelo StreamGuard: vínculos por `equipamento_componente` ou componentes associados diretamente ao servidor. A estrutura é identificada pelos metadados JDBC, sem executar migrações. No esquema legado, o vínculo da empresa é confirmado pela chave estrangeira.

## Regras dos alertas

- Um valor abaixo do mínimo ou acima do máximo gera uma ocorrência. Igualdade com o limite é permitida.
- Configurações desabilitadas ou sem limites aplicáveis não geram alertas.
- Cada servidor/tipo de métrica possui uma ocorrência ativa. CPU, RAM, disco, rede e suas métricas têm controles independentes.
- A primeira violação cria um chamado Jira. Enquanto persistir, o serviço atualiza esse chamado.
- Cada integração respeita um intervalo mínimo de **cinco minutos entre tentativas**, inclusive após reiniciar o container.
- Uma atualização exige nova avaliação. Consultar novamente o mesmo dado não repete a notificação.
- Duas amostras válidas dentro dos limites confirmam a recuperação local. O encerramento do chamado continua sob controle do workflow do Jira.

Por padrão, amostras com mais de 15 minutos, futuras, inválidas ou incompatíveis com a unidade configurada são descartadas. O início do monitoramento é persistido na primeira execução; amostras anteriores a esse início não geram ocorrências. A recuperação aceita a periodicidade dos lotes da ETL; lacunas maiores que a validade da amostra ou três intervalos de coleta, o que for maior, reiniciam a contagem.

O formato esperado da Silver é:

```csv
servidor_id,timestamp,tipo,valor,unidade
```

Os tipos incluem uso de CPU/RAM/disco, carga da CPU, memória disponível, taxas de disco/rede e inventário. Percentuais, bytes, bytes/s, segundos e carga são comparados em suas próprias unidades. O legado `Uso` em `%` é reconhecido para CPU, RAM e disco; não são inferidas conversões de unidade ou direção de tráfego. Duplicatas conflitantes são rejeitadas e configurações duplicadas para um servidor/tipo suspendem a avaliação.

## Requisitos

- Docker e Docker Compose na EC2.
- MySQL acessível pela rede Docker, com um usuário dedicado que possua SELECT nas tabelas do StreamGuard.
- IAM Role da EC2 com acesso de leitura às partições Silver do bucket.
- Conta Jira com permissões para pesquisar, criar e editar chamados no projeto.
- Incoming Webhook do canal Slack destinado às notificações.
- Java 21 e Maven para desenvolvimento local; o Dockerfile fornece as ferramentas necessárias ao build.

## Configuração

`.env.example` contém somente o modelo. Na implantação, os valores ficam em **`/home/ubuntu/streamguard/java.env`**, fora do repositório e da imagem. O Java recebe variáveis de ambiente e não carrega `.env` automaticamente.

| Variável | Finalidade | Padrão |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_DATABASE` | Endereço do MySQL | Porta `3306` |
| `DB_USER`, `DB_PASSWORD` | Usuário dedicado de leitura | Obrigatórios |
| `DB_SSL_MODE` | Modo TLS do JDBC | `PREFERRED` |
| `S3_BUCKET` | Bucket que contém a Silver | Obrigatório |
| `AWS_REGION` / `AWS_DEFAULT_REGION` | Região AWS | `us-east-1` |
| `CAPTURA_FUSO` | Fuso dos timestamps sem offset | `America/Sao_Paulo` |
| `ESTADO_DIRETORIO` | Checkpoints e histórico persistente | `/estado` |
| `ALERTAS_INTERVALO_SEGUNDOS` | Polling do S3 | `15` |
| `CONFIG_INTERVALO_SEGUNDOS` | Renovação dos limites | `60` |
| `SILVER_MAX_IDADE_SEGUNDOS` | Validade máxima das amostras | `900` |
| `SILVER_MAX_BYTES` | Tamanho máximo de cada arquivo | `67108864` |
| `RECUPERACAO_AMOSTRAS` | Amostras normais para recuperação | `2` |
| `HTTP_TIMEOUT_SEGUNDOS` | Timeout Jira/Slack | `30` |
| `JIRA_ENVIAR`, `SLACK_ENVIAR` | Habilitação independente das entregas | `false` |
| `JIRA_DOMAIN` ou `JIRA_BASE_URL` | Endereço do Jira | Obrigatório ao habilitar Jira |
| `JIRA_EMAIL`, `JIRA_API_TOKEN` | Autenticação Jira | Obrigatórios ao habilitar Jira |
| `JIRA_PROJECT_KEY`, `JIRA_ISSUE_TYPE` | Projeto e nome exato do tipo de chamado | Tipo padrão `Incident` |
| `SLACK_WEBHOOK_URL` | Webhook do canal Slack | Obrigatório ao habilitar Slack |

`ALERTAS_DESDE` aceita um horário local ISO sem offset, como `2026-11-19T12:00:00`, somente para definir o início de um estado novo. `SILVER_CHAVE` permite selecionar explicitamente outro objeto, inclusive o caminho legado `silver/atual/metricas.csv`.

No ambiente StreamGuard atual, o banco chama-se `StreamGuard` (S maiúsculo); mantenha exatamente essa capitalização em `DB_DATABASE`, especialmente em hosts Linux. O usuário dedicado de alertas precisa de leitura no mesmo schema. Para conceder esse acesso após criar o usuário, use no MySQL:

```sql
GRANT SELECT ON `StreamGuard`.* TO 'streamguard_alertas'@'%';
```

Use o nome de tipo disponibilizado pelo projeto Jira, por exemplo `[System] Incident` quando aplicável. Falhas de renovação das configurações suspendem os envios até que o banco volte a responder. Os limites não são substituídos por valores fixos do código.

## Execução na EC2

O Compose deste repositório executa somente o worker e utiliza a rede existente `streamguard_default` para acessar o banco. O arquivo privado `java.env` deve estar na pasta imediatamente acima do checkout.

```text
/home/ubuntu/streamguard/
├── java.env
└── StreamGuardLogs/
    ├── Dockerfile
    ├── compose.ec2.yaml
    ├── pom.xml
    └── src/
```

```bash
cd /home/ubuntu/streamguard/StreamGuardLogs
sudo docker compose --project-name streamguard -f compose.ec2.yaml up -d --build java
sudo docker compose --project-name streamguard -f compose.ec2.yaml ps
sudo docker compose --project-name streamguard -f compose.ec2.yaml logs --tail 100 java
```

A imagem executa os testes durante o build e utiliza um usuário sem root no runtime. `restart: unless-stopped` mantém o processo ativo; o volume `streamguard_estado_java` preserva o controle de ocorrências. Execute uma única instância por fluxo e mantenha o volume ao atualizar a imagem.

A autenticação S3 utiliza a cadeia padrão do AWS SDK, preferencialmente a IAM Role da EC2. Em rede bridge com IMDSv2, a instância precisa permitir o acesso ao metadata service a partir dos containers.

## Entregas e histórico

As mensagens contêm servidor, componente, métrica, valor, limite, unidade, horário e identificador da ocorrência. O Slack inclui o link do chamado quando a chave Jira já foi confirmada e pode notificar mesmo se o Jira estiver indisponível. Sucesso ou falha de cada integração é registrado separadamente. Para evitar duplicidade, utilize apenas um caminho de notificação ao canal: webhook direto do Java ou automação Jira → Slack equivalente.

O Jira utiliza labels estáveis para reconciliar criações cuja resposta foi incerta. Atualizações usam PUT dos campos do chamado. Timeout de criação não provoca um novo POST sem confirmação. No Slack, uma resposta ambígua permanece pendente de revisão, pois o webhook não oferece uma consulta para confirmar a mensagem. Não há transação distribuída entre serviços externos e os arquivos locais.

O volume contém:

| Arquivo | Conteúdo |
|---|---|
| `estado.json` | Checkpoints, ocorrências, chave Jira e controle de intervalos |
| `alertas.jsonl` | Histórico JSON por linha: avaliações, tentativas, confirmações, falhas e recuperação |
| `execucao.lock` | Trava para impedir processos concorrentes no mesmo diretório |

```bash
sudo docker compose --project-name streamguard -f compose.ec2.yaml exec java tail -n 50 /estado/alertas.jsonl
```

Cada evento inclui ambiente, instante, ID da ocorrência, empresa, servidor, componente, métrica, valor, limite, revisão e situação das entregas. Senhas, tokens e webhook não são registrados. Falhas de persistência interrompem a execução para evitar continuar enviando sem conseguir gravar o histórico.

## Homologação isolada

O modo `ALERTAS_TESTE=true` exige `SILVER_CHAVE` sob o prefixo **`homologacao/alertas/`**. Utilize um diretório de estado exclusivo para o teste e uma amostra com horário atual. O serviço continua consultando os limites reais no banco, mas identifica as notificações como **`[HOMOLOGAÇÃO]`** e informa que a medição é sintética.

As ocorrências de homologação possuem identidade separada das ocorrências reais. Esse modo permite validar envio, atualização após cinco minutos e persistência após reinício sem alterar limites ou arquivos da produção.

## Desenvolvimento e testes

```bash
mvn verify
docker build -t streamguard-alertas .
```

Os testes automatizados usam relógio controlado, H2 em modo MySQL, cliente S3 simulado e servidores HTTP locais. Cobrem os dois esquemas de banco, limites, isolamento por empresa, ETag, duplicatas, recuperação, intervalo de cinco minutos, falhas parciais, histórico persistente e reinício. A homologação na EC2 complementa esses testes com MySQL, S3, Jira e Slack reais.
