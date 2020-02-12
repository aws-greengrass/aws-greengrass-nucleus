package com.aws.iot.evergreen.deployment.state;

import com.aws.iot.evergreen.deployment.DeploymentProcess;
import com.aws.iot.evergreen.deployment.model.DeploymentPacket;
import com.aws.iot.evergreen.deployment.model.Parameter;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.model.Package;
import java.util.Collections;
import java.util.Map;
import java.util.function.BiPredicate;

public class DownloadedState implements State {

    private final DeploymentProcess deploymentProcess;

    private final Kernel kernel;

    public DownloadedState(DeploymentProcess deploymentProcess, Kernel kernel) {
        this.deploymentProcess = deploymentProcess;
        this.kernel = kernel;
    }

    @Override
    public boolean canProceed() {
        System.out.println("<Downloaded>: checking if deployment can proceed");
        // check update kernel conditions
        DeploymentPacket packet = deploymentProcess.getDeploymentPacket();
        BiPredicate<Kernel, Map<String, Map<String, Parameter>>> updateCondition = packet.getUpdateCondition();
        if (updateCondition != null) {
            return updateCondition.test(kernel, packet.getTargetPackageConfigs());
        }
        return true;
    }

    @Override
    public void proceed() {
        System.out.println("<Downloaded>: proceed");
        // resolve kernel config
        deploymentProcess.setResolvedKernelConfig(resolveKernelConfig());
        deploymentProcess.setCurrentState(deploymentProcess.getUpdatingKernelState());
    }

    @Override
    public void cancel() {
        deploymentProcess.setCurrentState(deploymentProcess.getCanceledState());
    }

    private Map<String, Object> resolveKernelConfig() {
        Map<String, Package> targetPackages = deploymentProcess.getTargetPackages();
        Map<String, Map<String, Parameter>> targetPackageConfigs = deploymentProcess.getDeploymentPacket().getTargetPackageConfigs();

        return Collections.emptyMap();
    }
}
