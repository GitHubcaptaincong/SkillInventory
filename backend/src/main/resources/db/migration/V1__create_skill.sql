CREATE TABLE skill (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL,
    description VARCHAR(1024) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    published_snapshot JSON NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_name (name),
    KEY ix_skill_updated (updated_at DESC, id DESC)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
