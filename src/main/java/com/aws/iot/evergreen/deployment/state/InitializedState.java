package com.aws.iot.evergreen.deployment.state;

import com.aws.iot.evergreen.deployment.DeploymentProcess;
import com.aws.iot.evergreen.deployment.model.DeploymentPacket;
import com.aws.iot.evergreen.deployment.model.Parameter;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import com.aws.iot.evergreen.packagemanager.model.Package;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class InitializedState implements State {

    private final DeploymentProcess deploymentProcess;

    private final PackageManager packageManager;

    public InitializedState(DeploymentProcess deploymentProcess, PackageManager packageManager) {
        this.deploymentProcess = deploymentProcess;
        this.packageManager = packageManager;
    }

    @Override
    public boolean canProceed() {
        System.out.println("<Initialized>: checking if deployment can proceed");
        return true;
    }

    @Override
    public void proceed() {
        System.out.println("<Initialized>: proceed");
        //invoke package manager to resolve dependency tree
        Map<String, Map<String, Parameter>> targetPackageConfigs = deploymentProcess.getDeploymentPacket().getTargetPackageConfigs();
        deploymentProcess.setPendingDownloadPackages(getPendingDownloadPackages(targetPackageConfigs.keySet()));

        deploymentProcess.setCurrentState(deploymentProcess.getDownloadingState());
    }

    private Set<Package> getPendingDownloadPackages(Set<String> targetPackageIdentifiers) {
        return packageManager.getPendingDownloadPackages(targetPackageIdentifiers);
    }

    @Override
    public void cancel() {
        deploymentProcess.setCurrentState(deploymentProcess.getCanceledState());
    }
}
