package com.aws.iot.evergreen.deployment.model;

public enum ComponentUpdatePolicyAction {
    NOTIFY_COMPONENTS("NOTIFY_COMPONENTS"),
    SKIP_NOTIFY_COMPONENTS("SKIP_NOTIFY_COMPONENTS");

    private String deploymentSafetyPolicy;

    ComponentUpdatePolicyAction(final String val) {
        this.deploymentSafetyPolicy = val;
    }
}
