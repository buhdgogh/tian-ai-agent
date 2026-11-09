package com.tianaiagent.types.exception;

/** Safe model error details that can be returned to the requesting user. */
public final class ModelUnavailableException extends IllegalStateException {
    private final String code;
    private String runId;

    public ModelUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }

    public String runId() { return runId; }

    public void attachRunId(String id) {
        if (runId == null) runId = id;
    }
}
