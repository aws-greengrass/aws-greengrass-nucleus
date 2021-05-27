/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.e2e.componentmanager;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.DependencyResolver;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.deployment.DeploymentDocumentDownloader;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentPackageConfiguration;
import com.aws.greengrass.deployment.model.FailureHandlingPolicy;
import com.aws.greengrass.helper.PreloadComponentStoreHelper;
import com.aws.greengrass.integrationtests.e2e.BaseE2ETestCase;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.FileSystemPermission;
import com.aws.greengrass.util.platforms.Platform;
import org.apache.commons.lang3.SystemUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.componentmanager.ComponentStore.ARTIFACT_DIRECTORY;
import static com.aws.greengrass.componentmanager.ComponentStore.RECIPE_DIRECTORY;
import static com.aws.greengrass.testcommons.testutilities.Matchers.hasPermission;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.io.FileMatchers.anExistingDirectory;
import static org.hamcrest.io.FileMatchers.anExistingFile;

@ExtendWith(GGExtension.class)
@Tag("E2E")
class ComponentManagerE2ETest extends BaseE2ETestCase {

    private static ComponentManager componentManager;
    private static DependencyResolver dependencyResolver;
    private static Path componentStorePath;
    private final String kernelIntegTestPkgName = getTestComponentNameInCloud("KernelIntegTest");

    private static DeploymentDocumentDownloader downloader;

    protected ComponentManagerE2ETest() throws Exception {
        super();
    }

    @BeforeEach
    void setupKernel() throws Exception {
        // The integration test will pick up credentials from the default provider chain
        // In automated testing, the device environment should ideally have credentials for all tests
        // For dev work, this requires you to have a working set of AWS Credentials on your dev box and/or your IDE
        // environment
        initKernel();
        kernel.launch();

        // get required instances from context
        componentManager = kernel.getContext().get(ComponentManager.class);
        dependencyResolver = kernel.getContext().get(DependencyResolver.class);
        componentStorePath = kernel.getNucleusPaths().componentStorePath();

        downloader = kernel.getContext().get(DeploymentDocumentDownloader.class);
    }

    @AfterEach
    void tearDown() throws IOException {
        kernel.shutdown();
        cleanup();
    }

    @Test
    @Order(1)
    void GIVEN_package_identifier_WHEN_resolve_dependencies_and_prepare_THEN_package_and_dependencies_downloaded_with_artifacts()
            throws Exception {

        downloader.download("stub-deployment-id");

        List<String> rootPackageList = new ArrayList<>();
        rootPackageList.add(kernelIntegTestPkgName);
        List<DeploymentPackageConfiguration> configList = new ArrayList<>();
        configList.add(new DeploymentPackageConfiguration(kernelIntegTestPkgName, true, "1.0.0"));
        DeploymentDocument testDeploymentDocument =
                DeploymentDocument.builder().deploymentId("test").timestamp(12345678L)
                        .deploymentPackageConfigurationList(configList)
                        .failureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING).groupName("mockGroup").build();

        List<ComponentIdentifier> resolutionResult =
                dependencyResolver.resolveDependencies(testDeploymentDocument, new HashMap<>());
        Future<Void> testFuture = componentManager.preparePackages(resolutionResult);
        testFuture.get(10, TimeUnit.SECONDS);

        assertThat(componentStorePath.toFile(), anExistingDirectory());
        assertThat(componentStorePath.resolve(RECIPE_DIRECTORY).toFile(), anExistingDirectory());
        assertThat(componentStorePath.resolve(ARTIFACT_DIRECTORY).toFile(), anExistingDirectory());

        File downloadedRecipe = componentStorePath.resolve(RECIPE_DIRECTORY).resolve(PreloadComponentStoreHelper
                .getRecipeStorageFilenameFromTestSource(
                        getTestComponentNameInCloud("KernelIntegTestDependency") + "-1.0.0.yaml")).toFile();

        assertThat(downloadedRecipe, anExistingFile());

        assertThat(componentStorePath.resolve(componentStorePath.resolve(RECIPE_DIRECTORY).resolve(
                PreloadComponentStoreHelper.getRecipeStorageFilenameFromTestSource(
                        getTestComponentNameInCloud("Log") + "-2.0.0" + ".yaml"))).toFile(), anExistingFile());

        assertThat(componentStorePath.resolve(ARTIFACT_DIRECTORY).resolve(kernelIntegTestPkgName).resolve("1.0.0")
                .resolve("kernel_integ_test_artifact.txt").toFile(), anExistingFile());
    }

    @Test
    @Order(2)
    void GIVEN_package_with_s3_artifacts_WHEN_deployed_THEN_download_artifacts_from_customer_s3_and_perform_integrity_check()
            throws Exception {
        String appWithS3ArtifactsPackageName = getTestComponentNameInCloud("AppWithS3Artifacts");
        List<String> rootPackageList = new ArrayList<>();
        rootPackageList.add(appWithS3ArtifactsPackageName);
        List<DeploymentPackageConfiguration> configList = new ArrayList<>();
        configList.add(new DeploymentPackageConfiguration(appWithS3ArtifactsPackageName, true, "1.0.0"));
        DeploymentDocument testDeploymentDocument =
                DeploymentDocument.builder().deploymentId("test").timestamp(12345678L)
                        .deploymentPackageConfigurationList(configList)
                        .failureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING).groupName("mockGroup").build();

        List<ComponentIdentifier> resolutionResult =
                dependencyResolver.resolveDependencies(testDeploymentDocument, new HashMap<>());
        Future<Void> testFuture = componentManager.preparePackages(resolutionResult);
        testFuture.get(10, TimeUnit.SECONDS);

        // Validate artifact was downloaded and integrity check passed
        assertThat(componentStorePath.toFile(), anExistingDirectory());
        assertThat(componentStorePath.resolve(RECIPE_DIRECTORY).toFile(), anExistingDirectory());
        assertThat(componentStorePath.resolve(ARTIFACT_DIRECTORY).toFile(), anExistingDirectory());

        String testResourceRecipeFilename =
                getTestComponentNameInCloud(appWithS3ArtifactsPackageName) + "-1.0.0" + ".yaml";

        assertThat(componentStorePath.resolve(RECIPE_DIRECTORY).resolve(
                PreloadComponentStoreHelper.getRecipeStorageFilenameFromTestSource(testResourceRecipeFilename))
                .toFile(), anExistingFile());
        Path artifactTxt = componentStorePath.resolve(ARTIFACT_DIRECTORY).resolve(appWithS3ArtifactsPackageName)
                .resolve("1.0.0").resolve("artifact.txt");
        assertThat(artifactTxt.toFile(), anExistingFile());

        assertThat(artifactTxt, hasPermission(
                FileSystemPermission.builder().ownerRead(true).groupRead(true).otherRead(true)
                        .ownerWrite(!SystemUtils.USER_NAME.equals(Platform.getInstance().getPrivilegedUser()))
                        .ownerExecute(true).groupExecute(true).build()));

    }
}
