/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.packagemanager;

import com.amazonaws.services.greengrasscomponentmanagement.AWSGreengrassComponentManagement;
import com.amazonaws.services.greengrasscomponentmanagement.model.DeleteComponentRequest;
import com.amazonaws.services.greengrasscomponentmanagement.model.DeleteComponentResult;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.deployment.model.FailureHandlingPolicy;
import com.aws.iot.evergreen.integrationtests.e2e.BaseE2ETestCase;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.GreengrassPackageServiceClientFactory;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.Semver.SemverType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.packagemanager.PackageStore.ARTIFACT_DIRECTORY;
import static com.aws.iot.evergreen.packagemanager.PackageStore.RECIPE_DIRECTORY;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.io.FileMatchers.anExistingDirectory;
import static org.hamcrest.io.FileMatchers.anExistingFile;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(EGExtension.class)
@Tag("E2E")
class PackageManagerE2ETest extends BaseE2ETestCase {

    private static PackageManager packageManager;
    private static DependencyResolver dependencyResolver;
    private static Path packageStorePath;
    private static AWSGreengrassComponentManagement cmsClient;

    @BeforeEach
    void setupKernel() throws Exception {
        // The integration test will pick up credentials from the default provider chain
        // In automated testing, the device environment should ideally have credentials for all tests
        // For dev work, this requires you to have a working set of AWS Credentials on your dev box and/or your IDE
        // environment
        initKernel();
        kernel.launch();

        // get required instances from context
        packageManager = kernel.getContext().get(PackageManager.class);
        dependencyResolver = kernel.getContext().get(DependencyResolver.class);
        packageStorePath = kernel.getPackageStorePath();
        cmsClient = kernel.getContext().get(GreengrassPackageServiceClientFactory.class).getCmsClient();

        // TODO: Ideally integ test should clean up after itself. Unfortunately the delete API is not implemented
        // on the service side yet. Enable this code when that is ready. You'll also need to add the required import
        // statements. The delete code is already included in @AfterAll tagged function below
        uploadTestComponentsToCms(true, new PackageIdentifier("KernelIntegTest", new Semver("1.0.0")),
                new PackageIdentifier("KernelIntegTestDependency", new Semver("1.0.0")),
                new PackageIdentifier("Log", new Semver("2.0.0")));
    }

    @AfterEach
    void tearDown() {
        try {
            kernel.shutdown();
        } finally {
            DeleteComponentRequest deleteComponentRequest
                    = new DeleteComponentRequest().withComponentName("KernelIntegTest")
                    .withComponentVersion("1.0.0");
            DeleteComponentResult result = cmsClient.deleteComponent(deleteComponentRequest);
            assertEquals(200, result.getSdkHttpMetadata().getHttpStatusCode());
        }
    }

    @Test
    @Order(1)
    void GIVEN_package_identifier_WHEN_request_package_from_cms_service_THEN_package_downloaded_with_artifacts()
            throws Exception {
        PackageIdentifier pkgIdt
                = new PackageIdentifier("KernelIntegTest", new Semver("1.0.0", SemverType.NPM));
        List<PackageIdentifier> pkgList = new ArrayList<>();
        pkgList.add(pkgIdt);
        Future<Void> testFuture = packageManager.preparePackages(pkgList);
        testFuture.get(10, TimeUnit.SECONDS);

        assertThat(packageStorePath.toFile(), anExistingDirectory());
        assertThat(packageStorePath.resolve(RECIPE_DIRECTORY).toFile(), anExistingDirectory());
        assertThat(packageStorePath.resolve(ARTIFACT_DIRECTORY).toFile(), anExistingDirectory());

        assertThat(packageStorePath.resolve(RECIPE_DIRECTORY).resolve("KernelIntegTest-1.0.0.yaml").toFile(), anExistingFile());

        assertThat(packageStorePath.resolve(ARTIFACT_DIRECTORY).resolve("KernelIntegTest").resolve("1.0.0")
                                                .resolve("kernel_integ_test_artifact.txt").toFile(), anExistingFile());
    }

    @Test
    @Order(2)
    void GIVEN_package_identifier_WHEN_resolve_dependencies_and_prepare_THEN_package_and_dependencies_downloaded_with_artifacts()
            throws Exception {
        List<String> rootPackageList = new ArrayList<>();
        rootPackageList.add("KernelIntegTest");
        List<DeploymentPackageConfiguration> configList = new ArrayList<>();
        configList.add(new DeploymentPackageConfiguration("KernelIntegTest", "1.0.0",
                                                          Collections.emptyMap()));
        DeploymentDocument testDeploymentDocument
                = DeploymentDocument.builder().deploymentId("test").timestamp(12345678L).rootPackages(rootPackageList)
                                    .deploymentPackageConfigurationList(configList)
                                    .failureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                                    .groupName("test").build();
        List<PackageIdentifier> resolutionResult
                = dependencyResolver.resolveDependencies(testDeploymentDocument, rootPackageList);
        Future<Void> testFuture = packageManager.preparePackages(resolutionResult);
        testFuture.get(10, TimeUnit.SECONDS);

        assertThat(packageStorePath.toFile(), anExistingDirectory());
        assertThat(packageStorePath.resolve(RECIPE_DIRECTORY).toFile(), anExistingDirectory());
        assertThat(packageStorePath.resolve(ARTIFACT_DIRECTORY).toFile(), anExistingDirectory());

        assertThat(packageStorePath.resolve(RECIPE_DIRECTORY).resolve("KernelIntegTest-1.0.0.yaml").toFile(), anExistingFile());
        assertThat(packageStorePath.resolve(RECIPE_DIRECTORY).resolve("KernelIntegTestDependency-1.0.0.yaml").toFile(),
                   anExistingFile());
        assertThat(packageStorePath.resolve(RECIPE_DIRECTORY).resolve("Log-2.0.0.yaml").toFile(), anExistingFile());

        assertThat(packageStorePath.resolve(ARTIFACT_DIRECTORY).resolve("KernelIntegTest").resolve("1.0.0")
                                   .resolve("kernel_integ_test_artifact.txt").toFile(), anExistingFile());
    }
}
