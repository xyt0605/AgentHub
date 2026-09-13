-- v1.1.1 动态配置：管理后台可视化配置面板的持久化存储
-- 存储以 JSON 覆盖 application.yaml 的配置命名空间（ai / pipeline / agent 等），
-- 无记录的命名空间回落 yaml 默认值。

CREATE TABLE t_dynamic_config (
    id           VARCHAR(20)  NOT NULL PRIMARY KEY,
    config_key   VARCHAR(64)  NOT NULL,
    config_value TEXT         NOT NULL,
    update_by    VARCHAR(64),
    create_time  TIMESTAMP  DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP  DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_dynamic_config_key UNIQUE (config_key)
);

COMMENT ON TABLE t_dynamic_config IS '动态配置表，JSON 覆盖 yaml 配置命名空间';
COMMENT ON COLUMN t_dynamic_config.id IS '主键ID';
COMMENT ON COLUMN t_dynamic_config.config_key IS '配置命名空间标识，如 ai / pipeline / agent';
COMMENT ON COLUMN t_dynamic_config.config_value IS '配置 JSON 全量快照';
COMMENT ON COLUMN t_dynamic_config.update_by IS '最后修改人';
COMMENT ON COLUMN t_dynamic_config.create_time IS '创建时间';
COMMENT ON COLUMN t_dynamic_config.update_time IS '更新时间';
