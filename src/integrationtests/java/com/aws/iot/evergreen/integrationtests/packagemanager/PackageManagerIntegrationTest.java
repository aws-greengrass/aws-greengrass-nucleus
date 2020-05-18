/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.packagemanager;

import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.GreengrassPackageServiceClientFactory;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.Semver.SemverType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import com.amazonaws.services.greengrasscomponentmanagement.AWSGreengrassComponentManagement;
import com.amazonaws.services.greengrasscomponentmanagement.model.DeleteComponentRequest;
import com.amazonaws.services.greengrasscomponentmanagement.model.DeleteComponentResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(EGExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PackageManagerIntegrationTest {

    // Based on PackageManager.java
    private static final String RECIPE_DIRECTORY = "recipes";
    private static final String ARTIFACT_DIRECTORY = "artifacts";

    private static PackageManager packageManager;
    private static Path packageStorePath;
    private static AWSGreengrassComponentManagement cmsClient;

    private static Kernel kernel;

    @TempDir
    static Path rootDir;

    @BeforeAll
    static void setupKernel() throws IOException, URISyntaxException {
        System.setProperty("root", rootDir.toAbsolutePath().toString());
        kernel = new Kernel();
        kernel.parseArgs("-i", PackageManagerIntegrationTest.class.getResource("onlyMain.yaml").toString());
        kernel.getContext().put("greengrassServiceEndpoint",
                                "https://3w5ajog718.execute-api.us-east-1.amazonaws.com/Beta/");
        kernel.getContext().put("greengrassServiceRegion", "us-east-1");

        // The integration test will pick up credentials from the default provider chain
        // In automated testing, the device environment should ideally have credentials for all tests
        // For dev work, this requires you to have a working set of AWS Credentials on your dev box and/or your IDE
        // environment

        kernel.launch();

        // get required instances from context
        packageManager = kernel.getContext().get(PackageManager.class);
        packageStorePath = kernel.getPackageStorePath();

        cmsClient = kernel.getContext().get(GreengrassPackageServiceClientFactory.class).getCmsClient();

        // TODO: Ideally integ test should clean up after itself. Unfortunately the delete API is not implemented
        // on the service side yet. Enable this code when that is ready. You'll also need to add the required import
        // statements. The delete code is already included in @AfterAll tagged function below
        /*
        Path testPackagePath =
                Paths.get(PackageManagerIntegrationTest.class.getResource("test_packages").toURI())
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

    @AfterAll
    static void tearDown() {
        kernel.shutdown();
        DeleteComponentRequest deleteComponentRequest
                = new DeleteComponentRequest().withComponentName("KernelIntegTest")
                                              .withComponentVersion("1.0.0");
        DeleteComponentResult result = cmsClient.deleteComponent(deleteComponentRequest);
        assertEquals(200, result.getSdkHttpMetadata().getHttpStatusCode());
    }

    @Test
    @Order(1)
    void GIVEN_sample_deployment_doc_WHEN_submitted_to_deployment_task_THEN_services_start_in_kernel()
            throws Exception {
        PackageIdentifier pkgIdt
                = new PackageIdentifier("KernelIntegTest", new Semver("1.0.0", SemverType.NPM));
        List<PackageIdentifier> pkgList = new ArrayList<>();
        pkgList.add(pkgIdt);
        Future<Void> testFuture = packageManager.preparePackages(pkgList);
        testFuture.get();//10, TimeUnit.SECONDS);

        assertTrue(Files.exists(packageStorePath));
        assertTrue(Files.exists(packageStorePath.resolve(RECIPE_DIRECTORY)));
        assertTrue(Files.exists(packageStorePath.resolve(ARTIFACT_DIRECTORY)));

        assertTrue(Files.exists(packageStorePath.resolve(RECIPE_DIRECTORY).resolve("KernelIntegTest-1.0.0.yaml")));

        assertTrue(Files.exists(packageStorePath.resolve(ARTIFACT_DIRECTORY).resolve("KernelIntegTest")));
        assertTrue(Files.exists(packageStorePath.resolve(ARTIFACT_DIRECTORY).resolve("KernelIntegTest").resolve("1.0.0")));

        assertTrue(Files.exists(packageStorePath.resolve(ARTIFACT_DIRECTORY).resolve("KernelIntegTest").resolve("1.0.0")
                                                .resolve("kernel_integ_test_artifact.txt")));
    }
}
