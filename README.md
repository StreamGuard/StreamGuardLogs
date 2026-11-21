# StreamGuardLogs

Worker Java que avalia métricas da camada Silver do S3, consulta limites no MySQL e envia alertas ao Jira e ao Slack. O estado das ocorrências é mantido em volume persistente do Docker.

## Arquitetura

~~~text
Capturador → S3 Bronze → Lambda ETL → S3 Silver → StreamGuardLogs → Jira / Slack
                                                    ↑
                                             MySQL / config_metrica
~~~

O worker consulta as configurações no MySQL a cada 60 segundos e verifica as partições diárias `silver/<empresa>/<dia>/metricas.csv`. Para cada servidor e tipo de métrica, avalia a amostra válida mais recente, respeitando unidade, limites e estado da configuração.

O serviço reconhece os esquemas de banco com vínculo por `equipamento_componente` e o esquema legado em que o componente se associa diretamente ao equipamento. A estrutura é identificada por metadados JDBC; o worker não executa migrações.

## Regras de alerta

- Uma amostra abaixo do limite mínimo ou acima do máximo abre uma ocorrência. Igualdade com o limite é permitida.
- O alerta é baseado na amostra mais recente; o worker não exige permanência contínua por cinco minutos.
- Configurações desabilitadas ou sem limites aplicáveis não geram ocorrências.
- Cada servidor e tipo de métrica tem controle independente.
- A primeira violação cria um chamado Jira. Enquanto a ocorrência persistir, o serviço atualiza o chamado.
- Cada integração respeita um intervalo mínimo de cinco minutos entre tentativas, inclusive após reiniciar o container.
- Uma atualização exige nova amostra. Consultar novamente o mesmo dado não repete a notificação.
- Duas amostras válidas dentro dos limites confirmam a recuperação local. O encerramento do chamado segue o workflow do Jira.
- Amostras futuras, inválidas, incompatíveis com a unidade ou com mais de 15 minutos são descartadas por padrão.

A recuperação também considera a periodicidade de lotes da ETL. Uma lacuna maior que a validade da amostra ou três intervalos de coleta — o que for maior — reinicia a contagem de recuperação.

## Contrato da Silver

O CSV esperado contém estas colunas:

~~~csv
servidor_id,timestamp,tipo,valor,unidade
~~~

São reconhecidos uso de CPU/RAM/disco, carga da CPU, memória disponível, taxas de disco e rede e dados de inventário. Percentuais, bytes, bytes por segundo, segundos e carga são comparados em suas próprias unidades. O legado `Uso` em porcentagem é reconhecido para CPU, RAM e disco. O worker não infere conversões de unidade nem direção de tráfego. Duplicatas conflitantes são rejeitadas; configurações duplicadas para servidor e métrica suspendem a avaliação.

## Requisitos

- Java 21 e Maven para desenvolvimento e validação local.
- Docker e Docker Compose para execução em container.
- MySQL acessível pela rede Docker e usuário dedicado com permissão de leitura nas tabelas utilizadas.
- Credenciais AWS via IAM Role no ambiente AWS ou pela cadeia padrão do SDK em desenvolvimento.
- Jira com permissão para pesquisar, criar e editar chamados.
- Slack Incoming Webhook, caso o envio direto ao Slack esteja habilitado.

## Configuração

`.env.example` é apenas um modelo. Não coloque senhas, tokens ou webhooks no repositório.

O Compose de implantação lê o arquivo privado `java.env` localizado um nível acima deste checkout. A pasta de implantação pode ser escolhida pela equipe:

~~~text
<diretorio-de-implantacao>/
├── java.env
└── StreamGuardLogs/
    ├── compose.ec2.yaml
    ├── Dockerfile
    └── src/
~~~

Preencha `java.env` a partir do modelo `.env.example`, ajustando os valores ao ambiente. O Java recebe as variáveis pelo Compose e não carrega arquivos `.env` automaticamente.

| Variável | Finalidade | Padrão |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_DATABASE` | Acesso ao MySQL | Porta `3306` |
| `DB_USER`, `DB_PASSWORD` | Usuário dedicado de leitura | Obrigatórios |
| `DB_SSL_MODE` | TLS do JDBC | `PREFERRED` |
| `S3_BUCKET` | Bucket com as partições Silver | Obrigatório |
| `AWS_REGION` / `AWS_DEFAULT_REGION` | Região AWS | `us-east-1` |
| `CAPTURA_FUSO` | Fuso dos timestamps sem offset | `America/Sao_Paulo` |
| `ESTADO_DIRETORIO` | Checkpoints e histórico | `/estado` |
| `ALERTAS_INTERVALO_SEGUNDOS` | Intervalo de consulta ao S3 | `15` |
| `CONFIG_INTERVALO_SEGUNDOS` | Atualização dos limites | `60` |
| `SILVER_MAX_IDADE_SEGUNDOS` | Validade máxima das amostras | `900` |
| `SILVER_MAX_BYTES` | Tamanho máximo do arquivo | `67108864` |
| `RECUPERACAO_AMOSTRAS` | Amostras normais para recuperação | `2` |
| `HTTP_TIMEOUT_SEGUNDOS` | Timeout Jira/Slack | `30` |
| `JIRA_ENVIAR`, `SLACK_ENVIAR` | Habilitação independente das integrações | `false` |
| `JIRA_DOMAIN` ou `JIRA_BASE_URL` | Endereço do Jira | Obrigatório se Jira estiver habilitado |
| `JIRA_EMAIL`, `JIRA_API_TOKEN` | Autenticação Jira | Obrigatórios se Jira estiver habilitado |
| `JIRA_PROJECT_KEY`, `JIRA_ISSUE_TYPE` | Projeto e tipo do chamado | Tipo padrão `Incident` |
| `SLACK_WEBHOOK_URL` | Webhook do canal | Obrigatório se Slack estiver habilitado |

No schema atual do StreamGuard, o nome é `StreamGuard` com S maiúsculo. Mantenha essa capitalização em `DB_DATABASE`, especialmente em Linux. O usuário do worker precisa de permissão de leitura no mesmo schema:

~~~sql
GRANT SELECT ON `StreamGuard`.* TO 'streamguard_alertas'@'%';
~~~

`ALERTAS_DESDE` aceita um horário local ISO sem offset e define o início de um estado novo. `SILVER_CHAVE` seleciona explicitamente outro objeto, inclusive o caminho legado `silver/atual/metricas.csv`.

## Implantação com Docker Compose

O arquivo `compose.ec2.yaml` executa somente o worker e conecta à rede Docker externa `streamguard_default`, que deve permitir acesso ao MySQL. Configure o caminho da implantação no ambiente antes de executar os comandos:

~~~bash
export STREAMGUARD_ROOT="/caminho/escolhido/para/streamguard"
cd "$STREAMGUARD_ROOT/StreamGuardLogs"

docker compose --project-name streamguard -f compose.ec2.yaml up -d --build java
docker compose --project-name streamguard -f compose.ec2.yaml ps
docker compose --project-name streamguard -f compose.ec2.yaml logs --tail 100 java
~~~

A imagem executa `mvn verify` durante o build e roda com usuário sem root. O volume `streamguard_estado_java` preserva o estado das ocorrências. Execute uma instância do worker por fluxo e mantenha o volume ao atualizar a imagem.

A cadeia padrão do AWS SDK é usada para obter credenciais; em EC2, prefira uma IAM Role com acesso de leitura às partições Silver. Em rede bridge com IMDSv2, o acesso ao serviço de metadados deve estar disponível para os containers.

Para consultar o histórico persistido:

~~~bash
docker compose --project-name streamguard -f compose.ec2.yaml exec java tail -n 50 /estado/alertas.jsonl
~~~

## Operação e histórico

As mensagens registram servidor, componente, métrica, valor, limite, unidade, horário e identificador da ocorrência. O Slack inclui o link do chamado quando a chave Jira já foi confirmada; o envio ao Slack pode continuar mesmo se o Jira estiver indisponível. Habilite apenas um caminho de notificação para o canal, evitando duplicidade entre o webhook do worker e uma automação Jira → Slack.

O Jira usa labels estáveis para reconciliar uma criação cuja resposta ficou incerta. Falhas ou timeouts não geram outro chamado sem confirmação. Como o Incoming Webhook do Slack não permite consultar uma mensagem enviada, uma resposta ambígua permanece pendente de revisão. Não há transação distribuída entre Jira, Slack e o estado local.

O volume persistente contém:

| Arquivo | Conteúdo |
|---|---|
| `estado.json` | Checkpoints, ocorrências, chave Jira e intervalos de envio |
| `alertas.jsonl` | Histórico JSON por linha de avaliações e entregas |
| `execucao.lock` | Trava contra processos concorrentes no mesmo diretório |

Senhas, tokens e webhooks não são registrados. Falha ao persistir o estado interrompe o processamento para evitar envios sem histórico confiável.

## Homologação isolada

`ALERTAS_TESTE=true` exige `SILVER_CHAVE` sob o prefixo `homologacao/alertas/`. Use um diretório de estado exclusivo e uma amostra com horário atual. Os limites continuam vindo do banco real; as notificações são identificadas como `[HOMOLOGAÇÃO]` e descrevem a medição sintética.

As ocorrências de homologação têm identidade separada das ocorrências reais. Esse modo permite validar envio, atualização após cinco minutos e persistência após reinício sem alterar limites nem arquivos de produção.

## Desenvolvimento e testes

~~~bash
mvn verify
docker build -t streamguard-alertas .
~~~

Os testes automatizados usam relógio controlado, H2 em modo MySQL, cliente S3 simulado e servidores HTTP locais. Cobrem os dois esquemas de banco, limites, isolamento por empresa, ETag, duplicatas, recuperação, intervalo de envio, falhas parciais, histórico persistente e reinício. A homologação em ambiente implantado complementa esses testes com MySQL, S3, Jira e Slack reais.
