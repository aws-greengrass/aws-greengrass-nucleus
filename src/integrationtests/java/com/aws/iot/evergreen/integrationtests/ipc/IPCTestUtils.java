package com.aws.iot.evergreen.integrationtests.ipc;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.ipc.config.KernelIPCClientConfig;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.util.Coerce;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.ipc.AuthenticationHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.iot.evergreen.ipc.IPCService.KERNEL_URI_ENV_VARIABLE_NAME;
import static com.aws.iot.evergreen.kernel.EvergreenService.SETENV_CONFIG_NAMESPACE;
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
                    }
                }
            }
        });

        kernel.launch();
        assertTrue(awaitIpcServiceLatch.await(10, TimeUnit.SECONDS));
        return kernel;
    }

}
