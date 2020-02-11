package com.aws.iot.evergreen.deployment.state;

import com.aws.iot.evergreen.deployment.DeploymentProcess;
import com.aws.iot.evergreen.deployment.model.DeploymentPacket;
import com.aws.iot.evergreen.deployment.model.Parameter;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import com.aws.iot.evergreen.packagemanager.model.Package;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;

public class InitializedState implements State {

    private final DeploymentProcess deploymentProcess;

    private final Kernel kernel;

    private final PackageManager packageManager;

    public InitializedState(DeploymentProcess deploymentProcess, Kernel kernel, PackageManager packageManager) {
        this.deploymentProcess = deploymentProcess;
        this.kernel = kernel;
        this.packageManager = packageManager;
    }

    @Override
    public boolean canProceed() {
        // check deployment download condition
        DeploymentPacket packet = deploymentProcess.getDeploymentPacket();
        BiPredicate<Kernel, Map<String, Map<String, Parameter>>> downloadCondition = packet.getDownloadCondition();
        if (downloadCondition != null) {
            return downloadCondition.test(kernel, packet.getTargetPackageConfigs());
        }
        return true;
    }

    @Override
    public void proceed() {
        //invoke package manager to resolve dependency tree
        Map<String, Map<String, Parameter>> targetPackageConfigs = deploymentProcess.getDeploymentPacket().getTargetPackageConfigs();
        Map<String, Package> targetPackages = targetPackageConfigs.keySet().stream().collect(Collectors.toMap(Function.identity(), this::loadPackage));
        deploymentProcess.setTargetPackages(targetPackages);

        deploymentProcess.setCurrentState(deploymentProcess.getDownloadingState());
    }

    private Package loadPackage(String packageIdentifier) {
        //TODO validate
        String[] packageIdentifiers = packageIdentifier.split("-");
        if (packageIdentifier.length() != 2) {
            throw new RuntimeException("Failed to parse package identifier");
        }

        return packageManager.loadPackage(packageIdentifiers[0], packageIdentifiers[1]);
    }

    @Override
    public void cancel() {
        deploymentProcess.setCurrentState(deploymentProcess.getCanceledState());
    }
}
