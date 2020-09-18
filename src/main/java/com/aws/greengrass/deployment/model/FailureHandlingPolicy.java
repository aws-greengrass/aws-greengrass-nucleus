package com.aws.greengrass.deployment.model;

public enum FailureHandlingPolicy {
    ROLLBACK("ROLLBACK"),
    DO_NOTHING("DO_NOTHING");

    private String failureHandlingPolicy;

    FailureHandlingPolicy(final String val) {
        this.failureHandlingPolicy = val;
    }
}
