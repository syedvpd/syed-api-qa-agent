package com.syed.apiqa.domain;

public enum RunStatus {
    CREATED,
    QUEUED,
    DISCOVERING,
    FETCHING_SPEC,
    PARSING_SPEC,
    PLANNING,
    EXECUTING,
    PAUSING,
    PAUSED,
    CANCELLING,
    CANCELLED,
    ANALYZING,
    CLEANUP,
    CLEANING_UP,
    REPORTING,
    GENERATING_REPORT,
    COMPLETED,
    FAILED,
    TIMED_OUT;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }

    public boolean isActive() {
        return !isTerminal();
    }
}
