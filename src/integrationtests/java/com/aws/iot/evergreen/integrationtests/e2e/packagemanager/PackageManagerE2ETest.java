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
import com.aws.iot.evergreen.integrationtests.e2e.util.FileUtils;
import com.aws.iot.evergreen.kernel.Kernel;
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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.io.FileMatchers.anExistingDirectory;
import static org.hamcrest.io.FileMatchers.anExistingFile;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(EGExtension.class)
@Tag("E2E")
class PackageManagerE2ETest extends BaseE2ETestCase {

    // Based on PackageManager.java
    private static final String RECIPE_DIRECTORY = "recipes";
    private static final String ARTIFACT_DIRECTORY = "artifacts";

    private static PackageManager packageManager;
    private static DependencyResolver dependencyResolver;
    private static Path packageStorePath;
    private static AWSGreengrassComponentManagement cmsClient;

    private static Kernel kernel;

    @TempDir
    static Path rootDir;

    @BeforeEach
    void setupKernel() throws IOException {
        System.setProperty("root", rootDir.toAbsolutePath().toString());
        kernel = new Kernel();
        kernel.parseArgs("-i", PackageManagerE2ETest.class.getResource("onlyMain.yaml").toString());
        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel, thingInfo, BETA_REGION.toString());
        deviceProvisioningHelper.updateKernelConfigWithCMSConfiguration(kernel, BETA_REGION.toString());

        // The integration test will pick up credentials from the default provider chain
        // In automated testing, the device environment should ideally have credentials for all tests
        // For dev work, this requires you to have a working set of AWS Credentials on your dev box and/or your IDE
        // environment

        kernel.launch();

        Path localStoreContentPath = Paths.get(PackageManagerE2ETest.class.getResource("local_store_content").getPath());
        // pre-load contents to package store
        FileUtils.copyFolderRecursively(localStoreContentPath, kernel.getPackageStorePath());

        // get required instances from context
        packageManager = kernel.getContext().get(PackageManager.class);
        dependencyResolver = kernel.getContext().get(DependencyResolver.class);
        packageStorePath = kernel.getPackageStorePath();

        cmsClient = kernel.getContext().get(GreengrassPackageServiceClientFactory.class).getCmsClient();

        // TODO: Ideally integ test should clean up after itself. Unfortunately the delete API is not implemented
        // on the service side yet. Enable this code when that is ready. You'll also need to add the required import
        // statements. The delete code is already included in @AfterAll tagged function below
        /*
        Path testPackagePath =
                Paths.get(PackageManagerE2ETest.class.getResource("test_packages").toURI())
                     .resolve("KernelIntegTest-1.0.0");

        Path testRecipePath = testPackagePath.resolve("recipe.yaml");
        ByteBuffer recipeBuf = ByteBuffer.wrap(Files.readAllBytes(testRecipePath));
        try {
            CreateComponentRequest createComponentRequest = new CreateComponentRequest().withRecipe(recipeBuf);
            CreateComponentResult createComponentResult = cmsClient.createComponent(createComponentRequest);
            assertEquals("DRAFT", createComponentResult.getStatus());

            CreateComponentArtifactUploadUrlRequest artifactUploadUrlRequest
                    = new CreateComponentArtifactUploadUrlRequest().withArtifactName("kernel_integ_test_artifact.txt")
                                                                   .withComponentName("KernelIntegTest")
                                                                   .withComponentVersion("1.0.0");
            CreateComponentArtifactUploadUrlResult artifactUploadUrlResult
                    = cmsClient.createComponentArtifactUploadUrl(artifactUploadUrlRequest);
            URL s3PreSignedURL = new URL(artifactUploadUrlResult.getUrl());
            HttpURLConnection connection = (HttpURLConnection) s3PreSignedURL.openConnection();
            connection.setDoOutput(true);
            connection.setRequestMethod("PUT");
            OutputStreamWriter out = new OutputStreamWriter(connection.getOutputStream());
            out.write("Integration test artifact for Evergreen Kernel");
            out.close();
        } catch (Exception e) {
            System.out.println(e.toString());
        }
        */
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
