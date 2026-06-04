create database if not exists companion;
USE companion;
-- 用户表
CREATE TABLE IF NOT EXISTS user (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    username    VARCHAR(50) UNIQUE NOT NULL COMMENT '用户名，唯一，支持中文',
    password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密',
    onboarded   BOOLEAN DEFAULT FALSE COMMENT '是否完成冷启动',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 冷启动进度
CREATE TABLE IF NOT EXISTS onboarding_progress (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT NOT NULL COMMENT '用户ID',
    step        TINYINT NOT NULL COMMENT '步骤号',
    status      VARCHAR(20) NOT NULL COMMENT '状态 answered/skipped',
    answer      TEXT COMMENT '用户回答',
    reply       TEXT COMMENT 'AI回复',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_onboarding_progress_user_step (user_id, step),
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='冷启动进度';

-- 档案一：价值观
CREATE TABLE IF NOT EXISTS profile_values (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id      BIGINT NOT NULL COMMENT '用户ID',
    active       BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否有效',
    item         VARCHAR(100) COMMENT '价值维度',
    preference   VARCHAR(200) COMMENT '倾向描述',
    confidence   DECIMAL(3,2) COMMENT '置信度 0.00~1.00',
    evidence     JSON COMMENT '支撑证据',
    updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='价值观档案';

-- 档案二：决策历史
CREATE TABLE IF NOT EXISTS profile_decision (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id      BIGINT NOT NULL COMMENT '用户ID',
    topic        VARCHAR(200) COMMENT '决策主题',
    choice       VARCHAR(500) COMMENT '做出的选择',
    reason       TEXT COMMENT '决策原因',
    outcome      TEXT COMMENT '决策结果',
    satisfaction TINYINT COMMENT '满意度 1-5',
    tags         JSON COMMENT '标签',
    decision_date VARCHAR(20) COMMENT '决策日期',
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='决策历史';

-- Agent 工具调用日志
CREATE TABLE IF NOT EXISTS agent_tool_call_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT NOT NULL COMMENT '用户ID',
    conversation_id BIGINT COMMENT '会话ID',
    tool_name       VARCHAR(100) NOT NULL COMMENT '工具名称',
    input_summary   VARCHAR(1000) COMMENT '输入摘要',
    output_summary  VARCHAR(1000) COMMENT '输出摘要',
    status          VARCHAR(20) NOT NULL COMMENT '状态：成功/失败/跳过，取值 success/failed/skipped',
    latency_ms      INT COMMENT '耗时毫秒',
    error_message   VARCHAR(500) COMMENT '错误信息',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_agent_tool_user_created (user_id, created_at),
    INDEX idx_agent_tool_conversation_created (conversation_id, created_at),
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent工具调用日志';

-- Memory RAG 召回日志
CREATE TABLE IF NOT EXISTS memory_retrieval_log (
    id                            BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id                       BIGINT NOT NULL COMMENT '用户ID',
    retrieval_source              VARCHAR(50) NOT NULL COMMENT '召回来源，当前自动注入召回为 auto_prompt',
    query_text                    VARCHAR(1000) COMMENT '召回查询文本',
    value_count                   INT NOT NULL DEFAULT 0 COMMENT '价值观命中数',
    emotion_count                 INT NOT NULL DEFAULT 0 COMMENT '情绪模式命中数',
    decision_count                INT NOT NULL DEFAULT 0 COMMENT '历史决策命中数',
    relationship_count            INT NOT NULL DEFAULT 0 COMMENT '关系影响命中数',
    fear_count                    INT NOT NULL DEFAULT 0 COMMENT '恐惧与边界命中数',
    semantic_hit_count            INT NOT NULL DEFAULT 0 COMMENT '语义记忆命中数',
    max_semantic_score            DECIMAL(6,4) COMMENT '最高语义相似度',
    vector_available              BOOLEAN NOT NULL DEFAULT TRUE COMMENT '向量库是否可用',
    degraded                      BOOLEAN NOT NULL DEFAULT FALSE COMMENT '本次召回是否降级',
    semantic_top_k                INT COMMENT '语义召回TopK',
    intent                        VARCHAR(50) COMMENT '召回意图',
    semantic_query                VARCHAR(1000) COMMENT '语义检索改写后的查询文本',
    semantic_candidate_top_k      INT COMMENT '语义召回候选TopK',
    preferred_memory_types        JSON COMMENT '本次意图优先召回的记忆类型',
    semantic_similarity_threshold DECIMAL(4,3) COMMENT '语义召回阈值',
    semantic_hit_summary          JSON COMMENT 'Top语义命中摘要',
    prompt_context_length         INT COMMENT '长期记忆背景长度',
    created_at                    DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_memory_retrieval_user_created (user_id, created_at),
    INDEX idx_memory_retrieval_degraded_created (degraded, created_at),
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Memory RAG召回日志';

-- 档案三：情绪模式
CREATE TABLE IF NOT EXISTS profile_emotion (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id      BIGINT NOT NULL COMMENT '用户ID',
    active       BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否有效',
    trigger_desc VARCHAR(200) COMMENT '触发描述',
    emotion      VARCHAR(100) COMMENT '情绪类型',
    behavior     VARCHAR(500) COMMENT '行为表现',
    agent_note   VARCHAR(500) COMMENT 'AI记录',
    updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='情绪模式';

-- 档案四：关系图谱
CREATE TABLE IF NOT EXISTS profile_relationship (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT NOT NULL COMMENT '用户ID',
    active          BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否有效',
    name            VARCHAR(50) COMMENT '关系人姓名',
    role            VARCHAR(50) COMMENT '关系角色',
    influence_level VARCHAR(10) COMMENT '影响力等级 高/中/低',
    influence_style VARCHAR(200) COMMENT '影响方式',
    note            VARCHAR(500) COMMENT '备注',
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='关系图谱';

-- 档案五：恐惧与边界
CREATE TABLE IF NOT EXISTS profile_fear (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id        BIGINT NOT NULL COMMENT '用户ID',
    active         BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否有效',
    type           VARCHAR(10) COMMENT '类型 fear/boundary',
    description    VARCHAR(500) COMMENT '描述',
    manifestation  VARCHAR(500) COMMENT '表现形式',
    confidence     DECIMAL(3,2) COMMENT '置信度',
    evidence       JSON COMMENT '证据',
    boundary_type  VARCHAR(10) COMMENT '边界类型 hard/soft',
    updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='恐惧与边界';

CREATE TABLE IF NOT EXISTS profile_memory_candidate (
    id                     BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id                BIGINT NOT NULL COMMENT '用户ID',
    profile_type           VARCHAR(20) NOT NULL COMMENT 'value/emotion/relationship/fear/boundary',
    subject                VARCHAR(500) NOT NULL COMMENT '画像主体',
    content                VARCHAR(1000) NOT NULL COMMENT '画像内容',
    detail                 VARCHAR(500) COMMENT '补充字段',
    confidence             DECIMAL(3,2) COMMENT '置信度',
    evidence               JSON COMMENT '证据',
    source                 VARCHAR(50) NOT NULL COMMENT 'profile_extract/agent_tool_update',
    source_conversation_id BIGINT COMMENT '来源会话ID',
    status                 VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending/confirmed/rejected/expired',
    expires_at             DATETIME NOT NULL COMMENT '过期时间',
    handled_at             DATETIME COMMENT '处理时间',
    created_at             DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at             DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_memory_candidate_user_status_expires_created (user_id, status, expires_at, created_at),
    INDEX idx_memory_candidate_user_type_status (user_id, profile_type, status),
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='待确认画像记忆';

CREATE TABLE IF NOT EXISTS profile_memory_audit_log (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id           BIGINT NOT NULL COMMENT '用户ID',
    profile_type      VARCHAR(20) NOT NULL COMMENT '画像类型',
    profile_record_id BIGINT COMMENT '正式画像记录ID',
    candidate_id      BIGINT COMMENT '候选ID',
    action            VARCHAR(20) NOT NULL COMMENT 'confirm/reject/correct/delete',
    before_snapshot   JSON COMMENT '变更前快照',
    after_snapshot    JSON COMMENT '变更后快照',
    reason            VARCHAR(500) COMMENT '用户原因或系统备注',
    created_at        DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_memory_audit_user_created (user_id, created_at),
    INDEX idx_memory_audit_user_type_created (user_id, profile_type, created_at),
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='画像记忆治理审计日志';

CREATE TABLE IF NOT EXISTS profile_scene_memory_link (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id           BIGINT NOT NULL COMMENT '用户ID',
    profile_type      VARCHAR(20) NOT NULL COMMENT '画像类型',
    profile_record_id BIGINT NOT NULL COMMENT '正式画像记录ID',
    document_id       VARCHAR(200) NOT NULL COMMENT 'Chroma Document ID',
    source            VARCHAR(50) NOT NULL COMMENT 'profile_extract/agent_tool_update/user_confirm/user_correction',
    active            BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否有效',
    delete_status     VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT 'active/deleted/delete_failed',
    created_at        DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_scene_memory_link_profile (profile_type, profile_record_id, active),
    INDEX idx_scene_memory_link_user_active (user_id, active),
    UNIQUE KEY uk_scene_memory_link_document (document_id),
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='画像记录与场景记忆向量关联';

-- 聊天会话
CREATE TABLE IF NOT EXISTS chat_conversation (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id       BIGINT NOT NULL COMMENT '用户ID',
    title         VARCHAR(100) NOT NULL COMMENT '会话标题',
    message_count INT NOT NULL DEFAULT 0 COMMENT '消息数量',
    deleted       BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否删除',
    created_at    DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_chat_conversation_user_deleted_updated (user_id, deleted, updated_at),
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天会话';

-- 聊天消息
CREATE TABLE IF NOT EXISTS chat_message (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL COMMENT '会话ID',
    user_id         BIGINT NOT NULL COMMENT '用户ID',
    role            VARCHAR(20) NOT NULL COMMENT '消息角色 user/assistant',
    content         TEXT NOT NULL COMMENT '消息内容',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_chat_message_conversation_created (conversation_id, created_at),
    INDEX idx_chat_message_user_created (user_id, created_at),
    FOREIGN KEY (conversation_id) REFERENCES chat_conversation(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天消息';
