/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.aws.iot.evergreen.deployment.DeploymentDirectoryManager.BOOTSTRAP_TASK_FILE;
import static com.aws.iot.evergreen.deployment.DeploymentDirectoryManager.DEPLOYMENT_METADATA_FILE;
import static com.aws.iot.evergreen.deployment.DeploymentDirectoryManager.ROLLBACK_SNAPSHOT_FILE;
import static com.aws.iot.evergreen.deployment.DeploymentDirectoryManager.TARGET_CONFIG_FILE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.internal.verification.VerificationModeFactory.times;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class DeploymentDirectoryManagerTest {
    private static final String mockArn = "arn:aws:greengrass:us-east-1:12345678910:configuration:thinggroup/group1:1";
    private static final String expectedDirectoryName =
            "arn.aws.greengrass.us-east-1.12345678910.configuration.thinggroup+group1.1";
    @TempDir
    Path deploymentsDir;
    @Mock
    private Kernel kernel;

    private DeploymentDirectoryManager deploymentDirectoryManager;

    @BeforeEach
    public void beforeEach() {
        doReturn(deploymentsDir).when(kernel).getDeploymentsPath();
        deploymentDirectoryManager = new DeploymentDirectoryManager(kernel);
    }

    @Test
    public Path WHEN_create_new_deployment_dir_THEN_setup_directory_and_symlink() throws Exception {
        Path actual = createNewDeploymentDir(mockArn);
        assertTrue(Files.exists(actual));
        assertEquals(deploymentsDir.resolve(expectedDirectoryName), actual);
        assertEquals(actual, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
        return actual;
    }

    @Test
    public void GIVEN_ongoing_dir_WHEN_deployment_succeeds_THEN_persist_deployment_info() throws Exception {
        Path actual = createNewDeploymentDir(mockArn);

        deploymentDirectoryManager.persistLastSuccessfulDeployment();
        assertEquals(actual, Files.readSymbolicLink(deploymentDirectoryManager.getPreviousSuccessDir()));
        assertFalse(Files.exists(deploymentDirectoryManager.getOngoingDir()));
        assertFalse(Files.exists(deploymentDirectoryManager.getPreviousFailureDir()));
    }

    @Test
    public void GIVEN_ongoing_dir_WHEN_deployment_fails_THEN_persist_deployment_info() throws Exception {
        Path actual = createNewDeploymentDir(mockArn);
        deploymentDirectoryManager.persistLastFailedDeployment();
        assertEquals(actual, Files.readSymbolicLink(deploymentDirectoryManager.getPreviousFailureDir()));
        assertFalse(Files.exists(deploymentDirectoryManager.getOngoingDir()));
        assertFalse(Files.exists(deploymentDirectoryManager.getPreviousSuccessDir()));
    }

    @Test
    public void GIVEN_previous_deployment_WHEN_new_deployment_finishes_THEN_cleanup_previous_deployment() throws Exception {
        Path actual1 = createNewDeploymentDir(mockArn);
        deploymentDirectoryManager.persistLastFailedDeployment();

        String mockArn2 = "arn:aws:greengrass:us-east-1:12345678910:configuration:thinggroup/group1:2";
        String expectedDirectoryName2 =
                "arn.aws.greengrass.us-east-1.12345678910.configuration.thinggroup+group1.2";
        Path actual2 = createNewDeploymentDir(mockArn2);
        deploymentDirectoryManager.persistLastSuccessfulDeployment();

        assertFalse(Files.exists(actual1));
        assertFalse(Files.exists(deploymentDirectoryManager.getPreviousFailureDir()));
        assertFalse(Files.exists(deploymentDirectoryManager.getOngoingDir()));
        assertEquals(actual2, Files.readSymbolicLink(deploymentDirectoryManager.getPreviousSuccessDir()));
        assertEquals(deploymentsDir.resolve(expectedDirectoryName2), actual2);
    }

    @Test
    public void GIVEN_deployment_WHEN_write_to_file_and_read_THEN_restore_deployment() throws Exception {
        Path actual1 = createNewDeploymentDir(mockArn);
        Deployment expected = new Deployment("mockDoc", Deployment.DeploymentType.IOT_JOBS, "mockId");
        deploymentDirectoryManager.writeDeploymentMetadata(expected);
        assertTrue(Files.exists(actual1.resolve(DEPLOYMENT_METADATA_FILE)));
        Deployment actual = deploymentDirectoryManager.readDeploymentMetadata();
        assertEquals(expected, actual);
    }

    @Test
    public void GIVEN_file_path_WHEN_take_config_snapshot_THEN_call_kernel() throws Exception {
        deploymentDirectoryManager.takeConfigSnapshot(mock(Path.class));
        verify(kernel, times(1)).writeEffectiveConfigAsTransactionLog(any());
    }

    @Test
    public void GIVEN_ongoing_dir_WHEN_get_file_THEN_resolve_path() throws Exception {
        Path actual = createNewDeploymentDir(mockArn);
        assertEquals(actual.resolve(ROLLBACK_SNAPSHOT_FILE), deploymentDirectoryManager.getSnapshotFilePath());
        assertEquals(actual.resolve(TARGET_CONFIG_FILE), deploymentDirectoryManager.getTargetConfigFilePath());
        assertEquals(actual.resolve(BOOTSTRAP_TASK_FILE), deploymentDirectoryManager.getBootstrapTaskFilePath());
    }

    private Path createNewDeploymentDir(String arn) throws IOException {
        return deploymentDirectoryManager.createNewDeploymentDirectoryIfNotExists(arn);
    }
}
