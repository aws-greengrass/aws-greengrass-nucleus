/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.ipc;

import com.aws.greengrass.lifecyclemanager.GenericExternalService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.platforms.unix.linux.CGroupV1;
import com.aws.greengrass.util.platforms.unix.linux.LinuxSystemResourceController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.model.PauseComponentRequest;
import software.amazon.awssdk.aws.greengrass.model.ResumeComponentRequest;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.prepareKernelFromConfigFile;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class IPCHibernateTest {
    private static final String TARGET_COMPONENT_NAME = "HibernateTarget";
    private static final String CONTROLLER_COMPONENT_NAME = "HibernateController";

    @TempDir
    static Path tempRootDir;
    private Kernel kernel;
    private EventStreamRPCConnection clientConnection;
    private SocketOptions socketOptions;
    private GreengrassCoreIPCClient greengrassCoreIPCClient;

    @AfterEach
    void afterEach() {
        if (clientConnection != null) {
            clientConnection.disconnect();
        }
        if (socketOptions != null) {
            socketOptions.close();
        }
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @BeforeEach
    void beforeEach(ExtensionContext context) throws Exception {
        ignoreExceptionWithMessage(context, "Connection reset by peer");
        // Ignore if IPC can't send us more lifecycle updates because the test is already done.
        ignoreExceptionUltimateCauseWithMessage(context, "Channel not found for given connection context");
        ignoreExceptionOfType(context, InterruptedException.class);
        ignoreExceptionOfType(context, FileSystemException.class);

        System.setProperty("root", tempRootDir.toAbsolutePath().toString());

        kernel =
                prepareKernelFromConfigFile("hibernate_init_config.yaml", IPCHibernateTest.class, TARGET_COMPONENT_NAME,
                        CONTROLLER_COMPONENT_NAME);
        String authToken = IPCTestUtils.getAuthTokeForService(kernel, CONTROLLER_COMPONENT_NAME);
        socketOptions = TestUtils.getSocketOptionsForIPC();
        clientConnection = IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions, authToken, kernel);
        greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
    }

    @SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingGenericException"})
    @EnabledOnOs({OS.LINUX})
    @Test
    void GIVEN_LifeCycleEventStreamClient_WHEN_pause_resume_component_THEN_target_service_paused_and_resumed()
            throws Exception {
        assumeTrue(!ifCgroupV2(), "skip this test case if v2 is enabled.");
        GenericExternalService component = (GenericExternalService) kernel.locate(TARGET_COMPONENT_NAME);

        PauseComponentRequest pauseRequest = new PauseComponentRequest();
        pauseRequest.setComponentName(TARGET_COMPONENT_NAME);
        greengrassCoreIPCClient.pauseComponent(pauseRequest, Optional.empty()).getResponse().get(5, TimeUnit.SECONDS);

        assertTrue(component.isPaused());
        assertThat(getCgroupFreezerState(component.getServiceName()),
                anyOf(is(LinuxSystemResourceController.CgroupFreezerState.FROZEN),
                        is(LinuxSystemResourceController.CgroupFreezerState.FREEZING)));

        ResumeComponentRequest resumeRequest = new ResumeComponentRequest();
        resumeRequest.setComponentName(TARGET_COMPONENT_NAME);
        greengrassCoreIPCClient.resumeComponent(resumeRequest, Optional.empty()).getResponse().get(5, TimeUnit.SECONDS);

        assertFalse(component.isPaused());
        assertThat(getCgroupFreezerState(component.getServiceName()),
                is(LinuxSystemResourceController.CgroupFreezerState.THAWED));
    }

    private LinuxSystemResourceController.CgroupFreezerState getCgroupFreezerState(String serviceName)
            throws IOException {
        return LinuxSystemResourceController.CgroupFreezerState.valueOf(
                new String(Files.readAllBytes(CGroupV1.Freezer.getCgroupFreezerStateFilePath(serviceName)),
                        StandardCharsets.UTF_8).trim());
    }

    private boolean ifCgroupV2() {
        return Files.exists(Paths.get("/sys/fs/cgroup/cgroup.controllers"));
    }
}

