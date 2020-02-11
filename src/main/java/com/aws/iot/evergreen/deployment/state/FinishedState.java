package com.aws.iot.evergreen.deployment.state;

public class FinishedState implements State {
    @Override
    public boolean canProceed() {
        return true;
    }

    @Override
    public void proceed() {

    }

    @Override
    public void cancel() {
        //unsupported, ignore
    }

    @Override
    public boolean isFinalState() {
        return true;
    }
}
