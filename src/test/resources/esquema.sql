CREATE TABLE empresa (
    id_empresa INT AUTO_INCREMENT PRIMARY KEY,
    razao_social VARCHAR(100) NOT NULL,
    cnpj CHAR(14) NOT NULL UNIQUE,
    nome_resp VARCHAR(100),
    email VARCHAR(100),
    telefone CHAR(13),
    codigo_empresa CHAR(8) NOT NULL UNIQUE,
    dt_cadastro DATETIME DEFAULT NOW()
);

CREATE TABLE permissao (
    id_permissao INT AUTO_INCREMENT PRIMARY KEY,
    nome VARCHAR(100) NOT NULL
);

CREATE TABLE usuario (
    id_usuario INT AUTO_INCREMENT PRIMARY KEY,
    nome VARCHAR(100) NOT NULL,
    cargo VARCHAR(50),
    email VARCHAR(100) NOT NULL UNIQUE,
    senha_hash VARCHAR(255) NOT NULL,
    fk_empresa INT NOT NULL,
    fk_permissao INT NOT NULL,
    dt_ultimo_acesso DATETIME,
    ativo TINYINT(1) NOT NULL DEFAULT 1,

    FOREIGN KEY (fk_empresa)
        REFERENCES empresa(id_empresa),

    FOREIGN KEY (fk_permissao)
        REFERENCES permissao(id_permissao)
);

CREATE TABLE equipamentos (
    id_equipamentos INT AUTO_INCREMENT PRIMARY KEY,
    hostname VARCHAR(100) NOT NULL,
    codigo_agente VARCHAR(64) NOT NULL UNIQUE,
    sistema_operacional VARCHAR(100),
    versao_so VARCHAR(100),
    arquitetura VARCHAR(50),
    tipo VARCHAR(45),
    ip VARCHAR(50),
    status VARCHAR(20),
    dt_atualizacao DATETIME,
    fk_empresa INT NOT NULL,

    FOREIGN KEY (fk_empresa)
        REFERENCES empresa(id_empresa)
);

-- tipo UNIQUE: garante um único componente por tipo (CPU, RAM, ...),
-- evitando que o "SELECT ... WHERE tipo = X LIMIT 1" pegue um id diferente do atual
CREATE TABLE componente (
    id_componente INT AUTO_INCREMENT PRIMARY KEY,
    nome VARCHAR(100) NOT NULL,
    tipo VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE equipamento_componente (
    id_equipamento_componente INT AUTO_INCREMENT PRIMARY KEY,
    fk_equipamento INT NOT NULL,
    fk_componente INT NOT NULL,
    data_instalacao DATETIME,
    status VARCHAR(20),

    FOREIGN KEY (fk_equipamento)
        REFERENCES equipamentos(id_equipamentos),

    FOREIGN KEY (fk_componente)
        REFERENCES componente(id_componente),

    UNIQUE (fk_equipamento, fk_componente)
);

CREATE TABLE metrica (
    id_metrica INT AUTO_INCREMENT PRIMARY KEY,
    tipo VARCHAR(50) NOT NULL,
    nome VARCHAR(255) NOT NULL,
    unidade VARCHAR(20),
    fk_componente INT NOT NULL,

    FOREIGN KEY (fk_componente)
        REFERENCES componente(id_componente)
);

CREATE TABLE config_metrica (
    id_config_metrica INT AUTO_INCREMENT PRIMARY KEY,
    habilitado TINYINT(1) NOT NULL DEFAULT 1,
    intervalo_coleta INT NOT NULL,
    limite_maximo DECIMAL(10,2),
    limite_minimo DECIMAL(10,2),
    fk_equipamento_componente INT NOT NULL,
    fk_metrica INT NOT NULL,

    FOREIGN KEY (fk_equipamento_componente)
        REFERENCES equipamento_componente(id_equipamento_componente),

    FOREIGN KEY (fk_metrica)
        REFERENCES metrica(id_metrica),

    UNIQUE (fk_equipamento_componente, fk_metrica)
);
