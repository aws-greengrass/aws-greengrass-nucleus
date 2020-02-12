package com.aws.iot.evergreen.deployment.state;

import com.aws.iot.evergreen.deployment.DeploymentProcess;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import com.aws.iot.evergreen.packagemanager.model.Package;

public class DownloadingState implements State {

    private final DeploymentProcess deploymentProcess;

    private final PackageManager packageManager;

    public DownloadingState(DeploymentProcess deploymentProcess, PackageManager packageManager) {
        this.deploymentProcess = deploymentProcess;
        this.packageManager = packageManager;
    }

    @Override
    public boolean canProceed() {
        System.out.println("<Downloading>: checking if deployment can proceed");
        //invoke package manager to download artifact, download can be asynchronous
        for (Package pkg : deploymentProcess.getTargetPackages().values()) {
            packageManager.downloadArtifacts(pkg);
        }
        return true;
    }

    @Override
    public void proceed() {
        System.out.println("<Downloading>: proceed");
        deploymentProcess.setCurrentState(deploymentProcess.getDownloadedState());
    }

    @Override
    public void cancel() {
        deploymentProcess.setCurrentState(deploymentProcess.getCanceledState());
        // cancel package manager downloading
    }
}
