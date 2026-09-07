package com.sky.decisioncompanion.service.agenttool;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 工具注册表：集中声明每个工具的元数据与生命周期契约（借鉴 waoowaoo 的 Operation Registry）。
 *
 * <p>把散落在各处的工具事实（名称、是否写、是否深层敏感、契约版本、默认可见性）收敛到一处，
 * 供上层做可见性策略、幂等契约版本、启动校验复用，避免字符串字面量漂移。
 *
 * <p>分层语义与 waoowaoo 一致：注册（Registry）≠ 对模型可见（默认暴露）≠ 有权执行（执行处判权）。
 * 默认只暴露读/分析类工具；写工具 {@code updateUserProfile} 是深层敏感写，需场景授权后方可执行。
 */
@Component
public class AgentToolRegistry {

    private static final Logger logger = LoggerFactory.getLogger(AgentToolRegistry.class);

    /** 写画像工具名（唯一写工具）。 */
    public static final String UPDATE_USER_PROFILE = "updateUserProfile";

    /** 契约版本：工具参数语义演进时递增，让旧幂等键自然失效。 */
    public static final String UPDATE_USER_PROFILE_CONTRACT = "updateUserProfile:v1";

    private final Map<String, ToolSpec> specs = new LinkedHashMap<>();

    public AgentToolRegistry() {
        // 类别：read=只读召回 / analysis=纯分析 / write=写档案
        register("searchDecisionHistory", "read", false, false, null, true);
        register("searchSemanticMemory", "read", false, false, null, true);
        register("generateDecisionMatrix", "analysis", false, false, null, true);
        register(UPDATE_USER_PROFILE, "write", true, true, UPDATE_USER_PROFILE_CONTRACT, false);
    }

    @PostConstruct
    void validate() {
        Set<String> defaultExposed = defaultExposedNames();
        if (specs.isEmpty()) {
            throw new IllegalStateException("AgentToolRegistry 为空：至少注册一个工具");
        }
        if (defaultExposed.isEmpty()) {
            throw new IllegalStateException("AgentToolRegistry 没有默认可见（读/分析）工具");
        }
        // 写工具必须声明契约版本，避免幂等键在契约演进后失效
        for (ToolSpec spec : specs.values()) {
            if (spec.write() && !StringUtils.hasText(spec.contractRevision())) {
                throw new IllegalStateException("写工具必须声明契约版本: " + spec.name());
            }
            if (spec.deepSensitive() && !spec.write()) {
                throw new IllegalStateException("深层敏感仅适用于写工具: " + spec.name());
            }
        }
        logger.info("AgentToolRegistry 校验通过, 工具数: {}, 默认可见: {}", specs.size(), defaultExposed);
    }

    private void register(String name, String intent, boolean write, boolean deepSensitive,
                          String contractRevision, boolean defaultExposed) {
        specs.put(name, new ToolSpec(name, intent, write, deepSensitive, contractRevision, defaultExposed));
    }

    public ToolSpec spec(String name) {
        return specs.get(name);
    }

    public boolean isWrite(String name) {
        ToolSpec spec = specs.get(name);
        return spec != null && spec.write();
    }

    /** 主写工具名（当前唯一写画像工具）。 */
    public String primaryWriteName() {
        return UPDATE_USER_PROFILE;
    }

    /** 默认对模型暴露（只读/分析）的工具名集合。 */
    public Set<String> defaultExposedNames() {
        Set<String> names = new LinkedHashSet<>();
        for (ToolSpec spec : specs.values()) {
            if (spec.defaultExposed()) {
                names.add(spec.name());
            }
        }
        return names;
    }

    /** 写工具集合。 */
    public Set<String> writeToolNames() {
        Set<String> names = new LinkedHashSet<>();
        for (ToolSpec spec : specs.values()) {
            if (spec.write()) {
                names.add(spec.name());
            }
        }
        return Collections.unmodifiableSet(names);
    }

    /** 工具契约版本；未声明返回 null。 */
    public String contractRevision(String name) {
        ToolSpec spec = specs.get(name);
        return spec == null ? null : spec.contractRevision();
    }

    /** 单个工具的登记元数据。 */
    public record ToolSpec(
            String name,
            String intent,
            boolean write,
            boolean deepSensitive,
            String contractRevision,
            boolean defaultExposed) {
    }
}