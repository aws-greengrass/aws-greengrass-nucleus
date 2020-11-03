/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.NucleusPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.aws.greengrass.deployment.DeploymentDirectoryManager.BOOTSTRAP_TASK_FILE;
import static com.aws.greengrass.deployment.DeploymentDirectoryManager.DEPLOYMENT_METADATA_FILE;
import static com.aws.greengrass.deployment.DeploymentDirectoryManager.ROLLBACK_SNAPSHOT_FILE;
import static com.aws.greengrass.deployment.DeploymentDirectoryManager.TARGET_CONFIG_FILE;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.io.FileMatchers.anExistingDirectory;
import static org.hamcrest.io.FileMatchers.anExistingFile;
import static org.hamcrest.io.FileMatchers.anExistingFileOrDirectory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.internal.verification.VerificationModeFactory.times;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class DeploymentDirectoryManagerTest {
    private static final String mockArn = "arn:aws:greengrass:us-east-1:12345678910:configuration:thinggroup/group1:1";
    private static final String expectedDirectoryName =
            "arn.aws.greengrass.us-east-1.12345678910.configuration.thinggroup+group1.1";
    @TempDir
    Path deploymentsDir;
    @Mock
    private Kernel kernel;
    @Mock
    private NucleusPaths nucleusPaths;

    private DeploymentDirectoryManager deploymentDirectoryManager;

    @BeforeEach
    void beforeEach() {
        doReturn(deploymentsDir).when(nucleusPaths).deploymentPath();
        deploymentDirectoryManager = new DeploymentDirectoryManager(kernel, nucleusPaths);
    }

    @Test
    void WHEN_create_new_deployment_dir_THEN_setup_directory_and_symlink() throws Exception {
        Path actual = createNewDeploymentDir(mockArn);
        assertThat(actual.toFile(), anExistingDirectory());
        assertEquals(deploymentsDir.resolve(expectedDirectoryName), actual);
        assertEquals(actual, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
    }

    @Test
    void GIVEN_deployment_dir_exists_WHEN_create_new_deployment_dir_THEN_reset_directory_and_symlink() throws Exception {
        Path actual = createNewDeploymentDir(mockArn);
        Path oldFile = actual.resolve("oldfile");
        Files.createFile(oldFile);
        assertThat(oldFile.toFile(), anExistingFile());
        deploymentDirectoryManager.persistLastSuccessfulDeployment();
        assertThat(oldFile.toFile(), anExistingFile());
        assertThat(deploymentDirectoryManager.getPreviousSuccessDir().toFile(), anExistingDirectory());

        createNewDeploymentDir(mockArn);
        assertThat(oldFile.toFile(), not(anExistingFileOrDirectory()));
        assertEquals(deploymentsDir.resolve(expectedDirectoryName), actual);
        assertEquals(actual, Files.readSymbolicLink(deploymentDirectoryManager.getOngoingDir()));
        assertThat(deploymentDirectoryManager.getPreviousSuccessDir().toFile(), not(anExistingFileOrDirectory()));
    }

    @Test
    void GIVEN_ongoing_dir_WHEN_deployment_succeeds_THEN_persist_deployment_info() throws Exception {
        Path actual = createNewDeploymentDir(mockArn);

        deploymentDirectoryManager.persistLastSuccessfulDeployment();
        assertEquals(actual, Files.readSymbolicLink(deploymentDirectoryManager.getPreviousSuccessDir()));
        assertThat(deploymentDirectoryManager.getOngoingDir().toFile(), not(anExistingFileOrDirectory()));
        assertThat(deploymentDirectoryManager.getPreviousFailureDir().toFile(), not(anExistingFileOrDirectory()));
    }

    @Test
    void GIVEN_ongoing_dir_WHEN_deployment_fails_THEN_persist_deployment_info() throws Exception {
        Path actual = createNewDeploymentDir(mockArn);
        deploymentDirectoryManager.persistLastFailedDeployment();
        assertEquals(actual, Files.readSymbolicLink(deploymentDirectoryManager.getPreviousFailureDir()));
        assertThat(deploymentDirectoryManager.getOngoingDir().toFile(), not(anExistingFileOrDirectory()));
        assertThat(deploymentDirectoryManager.getPreviousSuccessDir().toFile(), not(anExistingFileOrDirectory()));
    }

    @Test
    void GIVEN_previous_deployment_WHEN_new_deployment_finishes_THEN_cleanup_previous_deployment() throws Exception {
        Path actual1 = createNewDeploymentDir(mockArn);
        deploymentDirectoryManager.persistLastFailedDeployment();

        String mockArn2 = "arn:aws:greengrass:us-east-1:12345678910:configuration:thinggroup/group1:2";
        String expectedDirectoryName2 =
                "arn.aws.greengrass.us-east-1.12345678910.configuration.thinggroup+group1.2";
        Path actual2 = createNewDeploymentDir(mockArn2);
        deploymentDirectoryManager.persistLastSuccessfulDeployment();

        assertThat(actual1.toFile(), not(anExistingFileOrDirectory()));
        assertThat(deploymentDirectoryManager.getOngoingDir().toFile(), not(anExistingFileOrDirectory()));
        assertThat(deploymentDirectoryManager.getPreviousFailureDir().toFile(), not(anExistingFileOrDirectory()));
        assertEquals(actual2, Files.readSymbolicLink(deploymentDirectoryManager.getPreviousSuccessDir()));
        assertEquals(deploymentsDir.resolve(expectedDirectoryName2), actual2);
    }

    @Test
    void GIVEN_deployment_WHEN_write_to_file_and_read_THEN_restore_deployment() throws Exception {
        Path actual1 = createNewDeploymentDir(mockArn);
        DeploymentDocument document = mock(DeploymentDocument.class);
        doReturn("mockId").when(document).getDeploymentId();
        Deployment expected = new Deployment(document, Deployment.DeploymentType.IOT_JOBS, "mockId", DEFAULT);
        deploymentDirectoryManager.writeDeploymentMetadata(expected);
        assertThat(actual1.resolve(DEPLOYMENT_METADATA_FILE).toFile(), anExistingFile());
        Deployment actual = deploymentDirectoryManager.readDeploymentMetadata();
        assertEquals(expected, actual);
    }

    @Test
    void GIVEN_file_path_WHEN_take_config_snapshot_THEN_call_kernel() throws Exception {
        deploymentDirectoryManager.takeConfigSnapshot(mock(Path.class));
        verify(kernel, times(1)).writeEffectiveConfigAsTransactionLog(any());
    }

    @Test
    void GIVEN_ongoing_dir_WHEN_get_file_THEN_resolve_path() throws Exception {
        Path actual = createNewDeploymentDir(mockArn);
        assertEquals(actual.resolve(ROLLBACK_SNAPSHOT_FILE), deploymentDirectoryManager.getSnapshotFilePath());
        assertEquals(actual.resolve(TARGET_CONFIG_FILE), deploymentDirectoryManager.getTargetConfigFilePath());
        assertEquals(actual.resolve(BOOTSTRAP_TASK_FILE), deploymentDirectoryManager.getBootstrapTaskFilePath());
    }

    private Path createNewDeploymentDir(String arn) throws IOException {
        return deploymentDirectoryManager.createNewDeploymentDirectory(arn);
    }
}
