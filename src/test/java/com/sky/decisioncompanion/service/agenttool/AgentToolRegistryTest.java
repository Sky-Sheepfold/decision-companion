package com.sky.decisioncompanion.service.agenttool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolRegistryTest {

    private final AgentToolRegistry registry = new AgentToolRegistry();

    @Test
    void readAndAnalysisToolsAreDefaultExposedButWriteToolIsNot() {
        assertThat(registry.defaultExposedNames())
                .containsExactlyInAnyOrder("searchDecisionHistory", "searchSemanticMemory", "generateDecisionMatrix");
        assertThat(registry.defaultExposedNames()).doesNotContain(AgentToolRegistry.UPDATE_USER_PROFILE);
    }

    @Test
    void writeToolIsRegisteredWithContractRevision() {
        assertThat(registry.isWrite(AgentToolRegistry.UPDATE_USER_PROFILE)).isTrue();
        assertThat(registry.contractRevision(AgentToolRegistry.UPDATE_USER_PROFILE)).isNotBlank();
        assertThat(registry.writeToolNames()).contains(AgentToolRegistry.UPDATE_USER_PROFILE);
        assertThat(registry.primaryWriteName()).isEqualTo(AgentToolRegistry.UPDATE_USER_PROFILE);
    }

    @Test
    void validatePassesForCurrentRegistration() {
        // @PostConstruct 校验通过即不抛异常；用工具名做关卡
        registry.validate();
        assertThat(registry.spec(AgentToolRegistry.UPDATE_USER_PROFILE).deepSensitive()).isTrue();
    }
}