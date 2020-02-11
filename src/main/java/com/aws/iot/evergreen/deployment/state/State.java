package com.aws.iot.evergreen.deployment.state;

public interface State {

    boolean canProceed();

    void proceed();

    void cancel();

    default boolean isFinalState() {
        return false;
    }

}
