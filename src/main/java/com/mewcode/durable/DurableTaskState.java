package com.mewcode.durable;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Durable execution lifecycle used by local and remote agent work. */
public enum DurableTaskState {
    CREATED,
    PLANNING,
    EXECUTING,
    VERIFYING,
    FAILED_RETRYABLE,
    WAITING_APPROVAL,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELED;

    private static final Map<DurableTaskState, Set<DurableTaskState>> TRANSITIONS = Map.of(
            CREATED, EnumSet.of(PLANNING, CANCELED),
            PLANNING, EnumSet.of(EXECUTING, WAITING_APPROVAL, FAILED_RETRYABLE, PAUSED, CANCELED),
            EXECUTING, EnumSet.of(VERIFYING, WAITING_APPROVAL, FAILED_RETRYABLE, PAUSED, COMPLETED, CANCELED),
            VERIFYING, EnumSet.of(EXECUTING, COMPLETED, FAILED_RETRYABLE, WAITING_APPROVAL, PAUSED, CANCELED),
            FAILED_RETRYABLE, EnumSet.of(EXECUTING, PAUSED, FAILED, CANCELED),
            WAITING_APPROVAL, EnumSet.of(EXECUTING, PAUSED, CANCELED),
            PAUSED, EnumSet.of(PLANNING, EXECUTING, VERIFYING, CANCELED),
            COMPLETED, EnumSet.noneOf(DurableTaskState.class),
            FAILED, EnumSet.noneOf(DurableTaskState.class),
            CANCELED, EnumSet.noneOf(DurableTaskState.class)
    );

    public boolean canTransitionTo(DurableTaskState next) {
        return next == this || TRANSITIONS.get(this).contains(next);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED;
    }

    public boolean isRecoverable() {
        return !isTerminal();
    }
}
