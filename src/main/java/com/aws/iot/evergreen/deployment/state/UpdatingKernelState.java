package com.aws.iot.evergreen.deployment.state;

import com.aws.iot.evergreen.deployment.DeploymentProcess;
import com.aws.iot.evergreen.kernel.Kernel;
import java.util.Map;

public class UpdatingKernelState implements State {

    private final DeploymentProcess deploymentProcess;

    private final Kernel kernel;

    public UpdatingKernelState(DeploymentProcess deploymentProcess, Kernel kernel) {
        this.deploymentProcess = deploymentProcess;
        this.kernel = kernel;
    }

    @Override
    public boolean canProceed() {
        System.out.println("<Updating>: checking if deployment can proceed");
        // update kernel with resolved kernel config
        // update kernel call can be asynchronous
        Map<String, Object> resolvedConfig = deploymentProcess.getResolvedKernelConfig();
        // kernel.update(resolvedConfig)
        return true;
    }

    @Override
    public void proceed() {
        System.out.println("<Updating>: proceed");
        deploymentProcess.setCurrentState(deploymentProcess.getFinishedState());
    }

    @Override
    public void cancel() {
        // unsupported, ignore
    }
}
