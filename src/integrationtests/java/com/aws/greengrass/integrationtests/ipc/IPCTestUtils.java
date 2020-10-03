package com.aws.greengrass.integrationtests.ipc;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentStatusKeeper;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.ipc.config.KernelIPCClientConfig;
import com.aws.greengrass.ipc.services.cli.models.DeploymentStatus;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.util.Coerce;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.ipc.AuthenticationHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.greengrass.ipc.IPCService.KERNEL_URI_ENV_VARIABLE_NAME;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SETENV_CONFIG_NAMESPACE;
import static org.junit.jupiter.api.Assertions.assertTrue;


public final class IPCTestUtils {

    public static String TEST_SERVICE_NAME = "ServiceName";

    private IPCTestUtils() {

    }

    public static KernelIPCClientConfig getIPCConfigForService(String serviceName, Kernel kernel) throws ServiceLoadException, URISyntaxException {
        Topic kernelUri = kernel.getConfig().getRoot().lookup(SETENV_CONFIG_NAMESPACE, KERNEL_URI_ENV_VARIABLE_NAME);
        URI serverUri = null;
        serverUri = new URI((String) kernelUri.getOnce());

        int port = serverUri.getPort();
        String address = serverUri.getHost();

        return KernelIPCClientConfig.builder().hostAddress(address).port(port)
                .token(Coerce.toString(kernel.locate(serviceName).getPrivateConfig().findLeafChild(SERVICE_UNIQUE_ID_KEY)
                        .getOnce())).build();
    }

    public static Kernel prepareKernelFromConfigFile(String configFile, Class testClass, String... serviceNames) throws InterruptedException {
        Kernel kernel = new Kernel();
        kernel.parseArgs("-i", testClass.getResource(configFile).toString());

        // ensure awaitIpcServiceLatch starts
        CountDownLatch awaitIpcServiceLatch = new CountDownLatch(serviceNames.length);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (serviceNames != null && serviceNames.length != 0) {
                for (String serviceName:serviceNames) {
                    if (service.getName().equals(serviceName) && newState.equals(State.RUNNING)) {
                        awaitIpcServiceLatch.countDown();
                        break;
                    }
                }
            }
        });

        kernel.launch();
        assertTrue(awaitIpcServiceLatch.await(10, TimeUnit.SECONDS));
        return kernel;
    }

    public static CountDownLatch waitForDeploymentToBeSuccessful(String deploymentId, Kernel kernel) {
        CountDownLatch deploymentLatch = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.LOCAL, (deploymentDetails) ->
        {
            String receivedDeploymentId =
                    deploymentDetails.get(DEPLOYMENT_ID_KEY_NAME).toString();
            if (receivedDeploymentId.equals(deploymentId)) {
                DeploymentStatus status = DeploymentStatus.valueOf(deploymentDetails
                        .get(DEPLOYMENT_STATUS_KEY_NAME).toString());
                if (status == DeploymentStatus.SUCCEEDED) {
                    deploymentLatch.countDown();
                }
            }
            return true;
        }, deploymentId);
        return deploymentLatch;
    }

    public static CountDownLatch waitForServiceToComeInState(String serviceName, State state, Kernel kernel) {
        // wait for service to come up
        CountDownLatch awaitServiceLatch = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(serviceName) && newState.equals(state)) {
                awaitServiceLatch.countDown();
            }
        });
        return awaitServiceLatch;
    }
}
