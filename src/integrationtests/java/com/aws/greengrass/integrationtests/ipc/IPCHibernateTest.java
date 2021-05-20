/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.ipc;

import com.aws.greengrass.lifecyclemanager.GenericExternalService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.platforms.unix.linux.Cgroup;
import com.aws.greengrass.util.platforms.unix.linux.LinuxSystemResourceController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.model.PauseComponentRequest;
import software.amazon.awssdk.aws.greengrass.model.ResumeComponentRequest;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.prepareKernelFromConfigFile;
import static com.aws.greengrass.lifecyclemanager.GenericExternalService.LIFECYCLE_RUN_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith({GGExtension.class})
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
        GenericExternalService component = (GenericExternalService) kernel.locate(TARGET_COMPONENT_NAME);
        String runCmdStr = Coerce.toString(
                component.getServiceConfig().find(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, LIFECYCLE_RUN_NAMESPACE_TOPIC));

        PauseComponentRequest pauseRequest = new PauseComponentRequest();
        pauseRequest.setComponentName(TARGET_COMPONENT_NAME);
        greengrassCoreIPCClient.pauseComponent(pauseRequest, Optional.empty()).getResponse().get(5, TimeUnit.SECONDS);

        assertTrue(component.isPaused());
        assertThat(getCgroupFreezerState(component.getServiceName()),
                anyOf(is(LinuxSystemResourceController.CgroupFreezerState.FROZEN),
                        is(LinuxSystemResourceController.CgroupFreezerState.FREEZING)));

        List<String> pids = Files.readAllLines(Cgroup.Freezer.getCgroupProcsPath(component.getServiceName()));
        assertThat("Long running test component must have at least one process alive", pids, is(not(empty())));

        for (String pid : pids) {
            Pair<String, String> status = processStatus(pid);
            assertThat("Paused process must belong to the correct paused component", runCmdStr.replaceAll("\\n", " "),
                    containsString(status.getRight()));
            assertThat("CPU utilization of paused component processes must be 0", status.getLeft(), is("0"));
        }

        ResumeComponentRequest resumeRequest = new ResumeComponentRequest();
        resumeRequest.setComponentName(TARGET_COMPONENT_NAME);
        greengrassCoreIPCClient.resumeComponent(resumeRequest, Optional.empty()).getResponse().get(5, TimeUnit.SECONDS);

        assertFalse(component.isPaused());
        assertThat(getCgroupFreezerState(component.getServiceName()),
                is(LinuxSystemResourceController.CgroupFreezerState.THAWED));
    }

    // Pair of cpu usage and cmd for a given pid
    private Pair<String, String> processStatus(String pid) throws IOException, InterruptedException {
        StringBuilder op = new StringBuilder();
        Process proc = new ProcessBuilder().command("ps", "-o", "c,cmd", "fp", pid).start();
        proc.waitFor(5, TimeUnit.SECONDS);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            br.lines().forEach(l -> op.append(l));
        }

        String cpuAndCmd = op.toString().replaceAll("C\\p{Zs}+CMD", "").trim();
        int separator = cpuAndCmd.indexOf(' ');
        return new Pair<>(cpuAndCmd.substring(0, separator).trim(),
                cpuAndCmd.substring(separator + 1).replaceAll("sh -c", "")
                        .replaceAll("^sudo -n -E -H -u [\\w\\d\\-\\p{Zs}#]* --", "").trim());
    }

    private LinuxSystemResourceController.CgroupFreezerState getCgroupFreezerState(String serviceName)
            throws IOException {
        return LinuxSystemResourceController.CgroupFreezerState.valueOf(
                new String(Files.readAllBytes(Cgroup.Freezer.getCgroupFreezerStateFilePath(serviceName)),
                        StandardCharsets.UTF_8).trim());
    }
}

