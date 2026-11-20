-- ============================================================
-- 安心保 业务库（insurance_biz）完整建表脚本
-- 由 deploy/postgres/init/01-init-biz-schema.sql 翻译而来
-- 共 16 张表：基础 / 费率 / 报价 / 交易 / 方案 / 会话
--
-- 翻译约定：
--   BIGSERIAL          -> BIGINT AUTO_INCREMENT
--   UUID               -> CHAR(36)
--   TIMESTAMP WITH TZ  -> DATETIME
--   JSONB / TEXT[]     -> JSON
--   ON CONFLICT        -> INSERT IGNORE / ON DUPLICATE KEY UPDATE
--   GIN 索引           -> MySQL 无对应，已省略（见文件末尾说明）
-- ============================================================

SET NAMES utf8mb4;

-- ============================================================
-- 一、基础
-- ============================================================

CREATE TABLE IF NOT EXISTS app_metadata (
    `key`      VARCHAR(100) NOT NULL COMMENT '元数据键',
    value      TEXT         NOT NULL COMMENT '元数据值',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '业务服务的数据库元数据与初始化标记';

CREATE TABLE IF NOT EXISTS users (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户主键',
    username      VARCHAR(80)  NOT NULL COMMENT '登录名',
    email         VARCHAR(160) NOT NULL COMMENT '邮箱',
    password_hash VARCHAR(255) NOT NULL COMMENT 'BCrypt 密码摘要',
    display_name  VARCHAR(100) COMMENT '展示名',
    status        VARCHAR(30)  NOT NULL DEFAULT 'active',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '用户账号';

CREATE TABLE IF NOT EXISTS products (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '产品主键',
    name         VARCHAR(200)  NOT NULL COMMENT '产品名称',
    clause_name  VARCHAR(300)  NOT NULL COMMENT '条款文件名（含 .pdf）',
    category     VARCHAR(50)   NOT NULL COMMENT '险种',
    insurer      VARCHAR(120)  NOT NULL COMMENT '承保公司',
    image_url    TEXT          COMMENT '产品配图',
    description  TEXT          COMMENT '产品简介',
    min_premium  DECIMAL(14,2) COMMENT '最低保费',
    max_premium  DECIMAL(14,2) COMMENT '最高保费',
    target_group VARCHAR(300)  COMMENT '投保人群',
    highlights   JSON          COMMENT '亮点标签数组',
    status       VARCHAR(30)   NOT NULL DEFAULT 'active',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_products_category (category),
    KEY idx_products_status (status),
    CONSTRAINT chk_products_category
        CHECK (category IN ('medical', 'critical_illness', 'accident', 'life'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '保险产品';

-- ============================================================
-- 二、费率表
-- ============================================================

CREATE TABLE IF NOT EXISTS rate_table_files (
    id                CHAR(36)     NOT NULL,
    product_id        BIGINT       NOT NULL,
    file_name         VARCHAR(300) NOT NULL COMMENT '费率表文件名',
    file_path         VARCHAR(500) NOT NULL COMMENT '存储路径',
    file_hash         VARCHAR(64)  COMMENT '文件摘要，用于去重',
    parser_version    VARCHAR(50)  COMMENT '解析器版本',
    status            VARCHAR(20)  NOT NULL DEFAULT 'pending',
    validation_report JSON         COMMENT '解析校验报告',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at      DATETIME,
    PRIMARY KEY (id),
    KEY idx_rate_files_product (product_id),
    CONSTRAINT fk_rate_files_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_rate_files_status
        CHECK (status IN ('pending', 'parsed', 'validated', 'active', 'failed'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '费率表源文件';

CREATE TABLE IF NOT EXISTS rate_plans (
    id              CHAR(36)     NOT NULL,
    product_id      BIGINT       NOT NULL,
    plan_code       VARCHAR(80)  NOT NULL COMMENT '计划编码',
    plan_name       VARCHAR(200) NOT NULL COMMENT '计划名称',
    rate_type       VARCHAR(50)  NOT NULL COMMENT '费率类型',
    currency        VARCHAR(10)  NOT NULL DEFAULT 'CNY',
    required_params JSON         NOT NULL DEFAULT (JSON_ARRAY()) COMMENT '必填参数列表',
    optional_params JSON         NOT NULL DEFAULT (JSON_ARRAY()) COMMENT '可选参数列表',
    status          VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rate_plans_product_code (product_id, plan_code),
    CONSTRAINT fk_rate_plans_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_rate_plans_status CHECK (status IN ('active', 'inactive')),
    CONSTRAINT chk_rate_plans_type CHECK (rate_type IN (
        'fixed_plan_premium',
        'age_band_plan_premium',
        'per_1000_sum_assured',
        'per_10000_sum_assured',
        'coverage_amount_matrix',
        'rider_rate'
    ))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '费率的计划维度定义';

CREATE TABLE IF NOT EXISTS rate_table_items (
    id               CHAR(36)      NOT NULL,
    rate_file_id     CHAR(36)      COMMENT '来源费率文件',
    product_id       BIGINT        NOT NULL,
    plan_code        VARCHAR(80)   NOT NULL,
    rate_type        VARCHAR(50)   NOT NULL,
    age_min          INT,
    age_max          INT,
    gender           VARCHAR(20)   NOT NULL DEFAULT 'all',
    occupation_class VARCHAR(20)   NOT NULL DEFAULT 'all',
    social_security  VARCHAR(20)   NOT NULL DEFAULT 'all',
    payment_period   VARCHAR(50)   COMMENT '缴费年限',
    coverage_period  VARCHAR(50)   COMMENT '保障期限',
    coverage_amount  DECIMAL(14,2) COMMENT '保额',
    deductible       DECIMAL(14,2) COMMENT '免赔额',
    dimensions       JSON          NOT NULL DEFAULT (JSON_OBJECT()) COMMENT '扩展维度',
    rate_value       DECIMAL(18,6) COMMENT '费率值',
    premium          DECIMAL(14,2) COMMENT '保费',
    source_page      INT           COMMENT '来源页码',
    source_table     VARCHAR(200)  COMMENT '来源表格标题',
    status           VARCHAR(20)   NOT NULL DEFAULT 'active',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_rate_items_lookup
        (product_id, plan_code, age_min, age_max, gender, payment_period, coverage_period, status),
    CONSTRAINT fk_rate_items_file FOREIGN KEY (rate_file_id) REFERENCES rate_table_files (id),
    CONSTRAINT fk_rate_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_rate_items_status CHECK (status IN ('active', 'inactive')),
    CONSTRAINT chk_rate_items_gender CHECK (gender IN ('male', 'female', 'unisex', 'all')),
    CONSTRAINT chk_rate_items_social_security CHECK (social_security IN ('yes', 'no', 'all')),
    CONSTRAINT chk_rate_items_non_negative CHECK (
        (rate_value IS NULL OR rate_value >= 0)
        AND (premium IS NULL OR premium >= 0)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '费率明细行';

-- ============================================================
-- 三、报价与试算适配
-- ============================================================

CREATE TABLE IF NOT EXISTS insurer_api_adapters (
    id           CHAR(36)     NOT NULL,
    insurer_code VARCHAR(80)  NOT NULL COMMENT '保险公司编码',
    adapter_type VARCHAR(40)  NOT NULL COMMENT '适配器类型',
    adapter_name VARCHAR(120) NOT NULL,
    base_url     VARCHAR(500),
    auth_type    VARCHAR(40)  NOT NULL DEFAULT 'none',
    config       JSON         NOT NULL DEFAULT (JSON_OBJECT()),
    status       VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_insurer_adapters_code (insurer_code),
    CONSTRAINT chk_insurer_adapters_type CHECK (adapter_type IN ('mock', 'http')),
    CONSTRAINT chk_insurer_adapters_status CHECK (status IN ('active', 'inactive'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '保险公司试算接口适配器';

CREATE TABLE IF NOT EXISTS product_pricing_schemas (
    id             CHAR(36)    NOT NULL,
    product_id     BIGINT      NOT NULL,
    scenario       VARCHAR(80) NOT NULL DEFAULT 'premium_by_coverage',
    schema_version VARCHAR(80) NOT NULL,
    schema_json    JSON        NOT NULL COMMENT '动态表单定义',
    schema_hash    VARCHAR(64),
    status         VARCHAR(20) NOT NULL DEFAULT 'draft',
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at   DATETIME,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pricing_schema_version (product_id, scenario, schema_version),
    KEY idx_pricing_schemas_product (product_id, scenario, status),
    CONSTRAINT fk_pricing_schemas_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_pricing_schemas_status CHECK (status IN ('draft', 'active', 'inactive'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '产品试算的动态表单 Schema';

CREATE TABLE IF NOT EXISTS product_pricing_bindings (
    id                    CHAR(36)     NOT NULL,
    product_id            BIGINT       NOT NULL,
    pricing_schema_id     CHAR(36)     NOT NULL,
    adapter_id            CHAR(36)     NOT NULL,
    external_product_code VARCHAR(120) NOT NULL COMMENT '保险公司侧产品码',
    api_code              VARCHAR(120) NOT NULL COMMENT '调用接口编码',
    field_mapping         JSON         NOT NULL DEFAULT (JSON_OBJECT()),
    response_mapping      JSON         NOT NULL DEFAULT (JSON_OBJECT()),
    mock_config           JSON         NOT NULL DEFAULT (JSON_OBJECT()),
    status                VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_pricing_bindings_product (product_id, status),
    CONSTRAINT fk_bindings_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_bindings_schema FOREIGN KEY (pricing_schema_id) REFERENCES product_pricing_schemas (id),
    CONSTRAINT fk_bindings_adapter FOREIGN KEY (adapter_id) REFERENCES insurer_api_adapters (id),
    CONSTRAINT chk_pricing_bindings_status CHECK (status IN ('active', 'inactive'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '产品与试算接口的绑定关系';

CREATE TABLE IF NOT EXISTS premium_quotes (
    id                CHAR(36)      NOT NULL,
    user_id           BIGINT,
    product_id        BIGINT        NOT NULL,
    request_params    JSON          NOT NULL COMMENT '试算入参快照',
    result            JSON          NOT NULL COMMENT '试算结果快照',
    total_premium     DECIMAL(14,2) NOT NULL,
    pricing_mode      VARCHAR(30)   NOT NULL,
    expires_at        DATETIME      NOT NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    pricing_schema_id CHAR(36),
    schema_version    VARCHAR(80),
    adapter_id        CHAR(36),
    answers           JSON          COMMENT '用户填写答案',
    external_request  JSON          COMMENT '外部接口请求体',
    external_response JSON          COMMENT '外部接口响应体',
    PRIMARY KEY (id),
    KEY idx_premium_quotes_user (user_id),
    CONSTRAINT fk_quotes_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_quotes_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_quotes_schema FOREIGN KEY (pricing_schema_id) REFERENCES product_pricing_schemas (id),
    CONSTRAINT fk_quotes_adapter FOREIGN KEY (adapter_id) REFERENCES insurer_api_adapters (id),
    CONSTRAINT chk_quotes_pricing_mode CHECK (
        pricing_mode IN ('table', 'demo_formula', 'mock_adapter', 'insurer_api', 'local_fallback')
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '保费试算记录';

-- ============================================================
-- 四、交易：订单 / 保单 / 理赔
-- ============================================================

CREATE TABLE IF NOT EXISTS orders (
    id                      CHAR(36)      NOT NULL,
    user_id                 BIGINT        NOT NULL,
    thread_id               CHAR(36)      COMMENT '来源会话，不设外键',
    order_number            VARCHAR(40)   NOT NULL,
    confirmation_id         VARCHAR(60),
    idempotency_key         VARCHAR(160)  COMMENT '幂等键',
    idempotency_fingerprint VARCHAR(64)   COMMENT '幂等指纹',
    items                   JSON          NOT NULL COMMENT '订单项',
    quote_ids               JSON          NOT NULL DEFAULT (JSON_ARRAY()) COMMENT '引用的试算记录 ID',
    total_premium           DECIMAL(14,2) NOT NULL,
    recommendation_snapshot JSON          COMMENT '推荐方案快照',
    status                  VARCHAR(30)   NOT NULL DEFAULT 'pending_payment',
    created_at              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orders_number (order_number),
    UNIQUE KEY uk_orders_confirmation (confirmation_id),
    UNIQUE KEY uk_orders_idempotency (idempotency_key),
    KEY idx_orders_user (user_id),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_orders_status CHECK (status IN (
        'pending_payment', 'pending_policy', 'paid', 'completed', 'cancelled'
    ))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '投保订单';

CREATE TABLE IF NOT EXISTS policies (
    id            CHAR(36)      NOT NULL,
    user_id       BIGINT        NOT NULL,
    product_id    BIGINT        NOT NULL,
    policy_number VARCHAR(60)   NOT NULL,
    status        VARCHAR(20)   NOT NULL DEFAULT 'active',
    effective_at  DATETIME      NOT NULL COMMENT '生效时间',
    expires_at    DATETIME      COMMENT '失效时间',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_policies_number (policy_number),
    KEY idx_policies_user_status_effective (user_id, status, effective_at DESC),
    CONSTRAINT fk_policies_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_policies_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_policies_status CHECK (status IN ('pending', 'active', 'expired', 'terminated'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '保单';

CREATE TABLE IF NOT EXISTS claims (
    id           CHAR(36)      NOT NULL,
    user_id      BIGINT        NOT NULL,
    product_id   BIGINT,
    policy_id    CHAR(36),
    claim_number VARCHAR(60)   COMMENT '理赔单号，MySQL 唯一索引允许多个 NULL',
    claim_type   VARCHAR(50)   NOT NULL,
    amount       DECIMAL(14,2) COMMENT '赔付金额',
    description  TEXT,
    status       VARCHAR(30)   NOT NULL DEFAULT 'submitted',
    submitted_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_claims_number (claim_number),
    KEY idx_claims_user (user_id),
    KEY idx_claims_user_policy_updated (user_id, policy_id, updated_at DESC),
    CONSTRAINT fk_claims_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_claims_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_claims_policy FOREIGN KEY (policy_id) REFERENCES policies (id),
    CONSTRAINT chk_claims_type CHECK (claim_type IN ('medical', 'accident', 'critical_illness', 'life')),
    CONSTRAINT chk_claims_status CHECK (status IN ('submitted', 'reviewing', 'approved', 'rejected', 'paid'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '理赔申请';

-- ============================================================
-- 五、保险方案
-- ============================================================

CREATE TABLE IF NOT EXISTS insurance_plans (
    id                    CHAR(36)      NOT NULL,
    user_id               BIGINT        NOT NULL,
    plan_name             VARCHAR(120)  NOT NULL,
    summary               TEXT,
    insured_profile       JSON          NOT NULL DEFAULT (JSON_OBJECT()) COMMENT '被保人画像',
    annual_premium_budget DECIMAL(14,2) COMMENT '年预算',
    status                VARCHAR(30)   NOT NULL DEFAULT 'uninsured',
    created_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_insurance_plans_user (user_id, status),
    CONSTRAINT fk_plans_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_plans_budget CHECK (annual_premium_budget IS NULL OR annual_premium_budget >= 0),
    CONSTRAINT chk_plans_status CHECK (status IN ('uninsured', 'applying', 'insured'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Agent 生成的保险方案';

CREATE TABLE IF NOT EXISTS insurance_plan_items (
    id                    CHAR(36)      NOT NULL,
    plan_id               CHAR(36)      NOT NULL,
    product_id            BIGINT        NOT NULL,
    priority              INT           NOT NULL DEFAULT 1,
    recommendation_reason TEXT,
    annual_premium_budget DECIMAL(14,2),
    status                VARCHAR(30)   NOT NULL DEFAULT 'uninsured',
    created_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plan_items_plan_product (plan_id, product_id),
    KEY idx_plan_items_plan (plan_id, status),
    CONSTRAINT fk_plan_items_plan FOREIGN KEY (plan_id) REFERENCES insurance_plans (id) ON DELETE CASCADE,
    CONSTRAINT fk_plan_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_plan_items_priority CHECK (priority >= 1),
    CONSTRAINT chk_plan_items_budget CHECK (annual_premium_budget IS NULL OR annual_premium_budget >= 0),
    CONSTRAINT chk_plan_items_status CHECK (status IN ('uninsured', 'insured'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '保险方案明细';

-- ============================================================
-- 六、会话（原属 PG 的 agent schema，双栈后归业务库）
-- ============================================================

CREATE TABLE IF NOT EXISTS chat_threads (
    id         CHAR(36)     NOT NULL COMMENT '会话主键，同时作为 LangGraph thread_id',
    user_id    BIGINT       NOT NULL COMMENT '归属用户',
    title      VARCHAR(200) NOT NULL DEFAULT '新会话',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_chat_threads_user_updated (user_id, updated_at DESC),
    CONSTRAINT fk_chat_threads_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '用户与智能客服的会话元数据，消息正文由 LangGraph Checkpointer 保存';

-- ------------------------------------------------------------
-- 关于未翻译的索引：
--   idx_rate_items_dimensions ... USING GIN(dimensions)
--   MySQL 没有 JSON 的 GIN 索引。若后续确实需要按 dimensions 检索，
--   可以建生成列 + 普通索引，或使用 MySQL 8.0.17+ 的多值索引：
--     ALTER TABLE rate_table_items
--       ADD INDEX idx_dimensions ((CAST(dimensions->>'$.key' AS CHAR(64))));
--   当前数据量下不需要，先略过。
-- ------------------------------------------------------------
