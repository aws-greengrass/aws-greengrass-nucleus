package com.aws.iot.evergreen.deployment;


import com.aws.iot.evergreen.deployment.model.DeploymentPacket;
import com.aws.iot.evergreen.deployment.model.Parameter;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import java.util.Collections;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class DeploymentAgentTest {

    private DeploymentAgent deploymentAgent = new DeploymentAgent(null, new PackageManager());


    @Test
    void testRunning() throws InterruptedException {
        DeploymentPacket packet = new DeploymentPacket();

        Parameter p1 = new Parameter("p1", "1234", Parameter.ParameterType.NUMBER);
        packet.setTargetPackageConfigs(Collections.singletonMap("Belt-1.0", Collections.singletonMap(p1.getName(),
                p1)));


        packet.setDownloadCondition((kernel, configs) -> {
            long now = new Date().getTime()/1000;

            int window = (int) (now%60);

            if (window >= 20 && window <= 40 ) {
                System.out.println("[Decision] proceed download");
                return true;
            } else {
                System.out.println("[Decision] can't download");
                return false;
            }
        });


        packet.setUpdateCondition((kernel, configs) -> {
            if (new Random().nextBoolean()) {
                System.out.println("[Decision] proceed update");
                return true;
            } else {
                System.out.println("[Decision] can't update");
                return false;
            }
        });

        Future<?> task = deploymentAgent.deploy(packet);
        DeploymentProcess deploymentProcess = deploymentAgent.getCurrentDeploymentProcess();

        while (!task.isDone()) {
//            System.out.println(String.format("=== Current state <%s> ===", deploymentProcess.getCurrentState()
//                    .getClass()
//                    .getSimpleName()));
            Thread.sleep(2000);
        }
    }

}
