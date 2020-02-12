package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.deployment.model.DeploymentPacket;
import com.aws.iot.evergreen.deployment.state.CanceledState;
import com.aws.iot.evergreen.deployment.state.DownloadedState;
import com.aws.iot.evergreen.deployment.state.DownloadingState;
import com.aws.iot.evergreen.deployment.state.FinishedState;
import com.aws.iot.evergreen.deployment.state.InitializedState;
import com.aws.iot.evergreen.deployment.state.State;
import com.aws.iot.evergreen.deployment.state.UpdatingKernelState;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import com.aws.iot.evergreen.packagemanager.model.Package;
import java.util.Collections;
import java.util.Map;

public class DeploymentProcess {

    private final State initializedState;
    private final State downloadingState;
    private final State downloadedState;
    private final State updatingKernelState;
    private final State finishedState;
    private final State canceledState;

    volatile private State currentState;

    private final DeploymentPacket deploymentPacket;

    //initialize in initialized state
    private Map<String, Package> targetPackages;

    //placeholder for resolved kernel config, change the type if necessary
    //resolved in downloaded state
    private Map<String, Object> resolvedKernelConfig;

    public DeploymentProcess(DeploymentPacket packet, Kernel kernel, PackageManager packageManager) {
        this.initializedState = new InitializedState(this, kernel, packageManager);
        this.downloadingState = new DownloadingState(this, packageManager);
        this.downloadedState = new DownloadedState(this, kernel);
        this.updatingKernelState = new UpdatingKernelState(this, kernel);
        this.finishedState = new FinishedState();
        this.canceledState = new CanceledState();
        this.currentState = initializedState;

        this.deploymentPacket = packet;
    }

    public void setCurrentState(State state) {
        this.currentState = state;
    }

    public State getCurrentState() {
        return currentState;
    }

    public State getInitializedState() {
        return initializedState;
    }

    public State getDownloadingState() {
        return downloadingState;
    }

    public State getDownloadedState() {
        return downloadedState;
    }

    public State getUpdatingKernelState() {
        return updatingKernelState;
    }

    public State getFinishedState() {
        return finishedState;
    }

    public State getCanceledState() {
        return canceledState;
    }

    public DeploymentPacket getDeploymentPacket() {
        return deploymentPacket;
    }

    public void setTargetPackages(Map<String, Package> packages) {
        this.targetPackages = Collections.unmodifiableMap(packages);
    }

    public Map<String, Package> getTargetPackages() {
        return targetPackages;
    }

    public void setResolvedKernelConfig(Map<String, Object> kernelConfig) {
        this.resolvedKernelConfig = Collections.unmodifiableMap(kernelConfig);
    }

    public Map<String, Object> getResolvedKernelConfig() {
        return resolvedKernelConfig;
    }

    public void proceed() {
        while (!currentState.isFinalState()) {
            if (currentState.canProceed()) {
                currentState.proceed();
            } else {
                try {
                    int duration = 10;
                    System.out.println(String.format("deployment sleep for %d seconds", duration));
                    Thread.sleep(duration * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        System.out.println("final state is " + currentState.getClass().getSimpleName());
    }

    public void cancel() {
        currentState.cancel();
    }
}
