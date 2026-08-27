package com.codeflow.eval;

/** Stable failure taxonomy used to build the regression dataset. */
public enum FailureType {
    TOOL_SELECTION_ERROR,
    INVALID_TOOL_PARAMS,
    REPEATED_TOOL_CALL,
    CONTEXT_LOSS,
    SUBAGENT_FAILURE,
    VERIFICATION_FAILURE,
    TOOL_EXECUTION_ERROR,
    MODEL_ERROR
}
