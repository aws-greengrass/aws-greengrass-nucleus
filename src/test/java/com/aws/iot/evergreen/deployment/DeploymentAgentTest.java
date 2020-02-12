package com.aws.iot.evergreen.deployment;


import com.aws.iot.evergreen.deployment.model.DeploymentPacket;
import com.aws.iot.evergreen.deployment.model.Parameter;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class DeploymentAgentTest {

    private DeploymentAgent deploymentAgent = new DeploymentAgent(null, new PackageManager());


    @Test
    void testRunning() throws InterruptedException {
        DeploymentPacket packet = new DeploymentPacket();

        Parameter p1 = new Parameter("p1", "1234", Parameter.ParameterType.NUMBER);
        packet.setTargetPackageConfigs(Collections.singletonMap("Belt-1.0", Collections.singletonMap(p1.getName(), p1)));

        packet.setDownloadCondition((kernel, configs) -> {
            System.out.println("checking download conditions");
            boolean random = new Random().nextBoolean();
            if (random) {
                System.out.println("proceed download");
            } else {
                System.out.println("can't download");
            }
            return random;
        });

        packet.setUpdateCondition((kernel, configs) -> {
            System.out.println("checking update conditions");
            boolean random = new Random().nextBoolean();
            if (random) {
                System.out.println("proceed update");
            } else {
                System.out.println("can't update");
            }
            return random;
        });

        deploymentAgent.deploy(packet);

        Future<?> task = deploymentAgent.getCurrentTask();
        while (!task.isDone()) {
            Thread.sleep(300);
        }
    }

}
