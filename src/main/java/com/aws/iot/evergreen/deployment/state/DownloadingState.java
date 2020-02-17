package com.aws.iot.evergreen.deployment.state;

import com.aws.iot.evergreen.deployment.DeploymentProcess;
import com.aws.iot.evergreen.deployment.model.DeploymentPacket;
import com.aws.iot.evergreen.deployment.model.Parameter;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.function.BiPredicate;

public class DownloadingState implements State {

    private final DeploymentProcess deploymentProcess;

    private final Kernel kernel;

    private final PackageManager packageManager;

    private Future<Boolean> downloadTask;

    public DownloadingState(DeploymentProcess deploymentProcess, Kernel kernel, PackageManager packageManager) {
        this.deploymentProcess = deploymentProcess;
        this.kernel = kernel;
        this.packageManager = packageManager;

    }

    @Override
    public boolean canProceed() {
        //download finished, proceed
        if (downloadTask != null && downloadTask.isDone() && !downloadTask.isCancelled()) {
            System.out.println("download finished");
            return true;
        }

        if (proceedDownload()) {
            //reschedule download if null or cancelled in the middle
            //package manager downloading is supposed to be idempotent
            if (downloadTask == null || downloadTask.isCancelled()) {
                System.out.println("start/resume download task.");
                downloadTask = packageManager.downloadPackages(deploymentProcess.getPendingDownloadPackages());
            }
        } else {
            // need to cancel the download
            if (downloadTask != null && !downloadTask.isCancelled()) {
                System.out.println("Cancel download task.");
                downloadTask.cancel(true);
            }
        }

        return false;
    }

    private boolean proceedDownload() {
        System.out.println("<Downloading>: checking if can proceed downloading.");
        DeploymentPacket packet = deploymentProcess.getDeploymentPacket();
        BiPredicate<Kernel, Map<String, Map<String, Parameter>>> downloadCondition = packet.getDownloadCondition();
        if (downloadCondition != null) {
            return downloadCondition.test(kernel, packet.getTargetPackageConfigs());
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
