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

-- 档案三：情绪模式
CREATE TABLE IF NOT EXISTS profile_emotion (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id      BIGINT NOT NULL COMMENT '用户ID',
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
    type           VARCHAR(10) COMMENT '类型 fear/boundary',
    description    VARCHAR(500) COMMENT '描述',
    manifestation  VARCHAR(500) COMMENT '表现形式',
    confidence     DECIMAL(3,2) COMMENT '置信度',
    evidence       JSON COMMENT '证据',
    boundary_type  VARCHAR(10) COMMENT '边界类型 hard/soft',
    updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='恐惧与边界';

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
