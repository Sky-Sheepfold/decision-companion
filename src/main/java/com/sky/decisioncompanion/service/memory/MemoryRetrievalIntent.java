package com.sky.decisioncompanion.service.memory;

public enum MemoryRetrievalIntent {

    MAJOR_DECISION("major_decision"),
    VENTING("venting"),
    REVIEW("review"),
    GOAL_PLANNING("goal_planning"),
    RELATIONSHIP_PRESSURE("relationship_pressure"),
    GENERAL("general");

    private final String code;

    MemoryRetrievalIntent(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
