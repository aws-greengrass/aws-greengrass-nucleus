package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.deployment.model.DeploymentPacket;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class DeploymentAgent {

    private ExecutorService executor = Executors.newSingleThreadExecutor();

    private DeploymentProcess currentDeploymentProcess;

    private Future<?> currentTask;

    private Kernel kernel;

    private PackageManager packageManager;

    public DeploymentAgent(Kernel kernel, PackageManager packageManager) {
        this.kernel = kernel;
        this.packageManager = packageManager;
    }


    public void deploy(DeploymentPacket deploymentPacket) {
        if (currentTask != null) {
            if (!currentTask.isDone()) {
                throw new RuntimeException("A deployment is processing");
            }
        }

        currentDeploymentProcess = new DeploymentProcess(deploymentPacket, kernel, packageManager);
        currentTask = executor.submit(currentDeploymentProcess::proceed);
    }

    public void cancelDeployment() {
        if (currentDeploymentProcess != null && !currentDeploymentProcess.getCurrentState().isFinalState()) {
            currentDeploymentProcess.cancel();
        }
    }

    public Future<?> getCurrentTask() {
        return currentTask;
    }
}
