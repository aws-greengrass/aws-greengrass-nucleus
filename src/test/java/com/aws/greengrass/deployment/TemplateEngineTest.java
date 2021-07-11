/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.amazon.aws.iot.greengrass.configuration.common.Configuration;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.exceptions.PackagingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.model.ConfigurationUpdateOperation;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.LocalOverrideRequest;
import com.aws.greengrass.deployment.templating.TemplateExecutionException;
import com.aws.greengrass.deployment.templating.exceptions.IllegalTemplateDependencyException;
import com.aws.greengrass.deployment.templating.exceptions.MultipleTemplateDependencyException;
import com.aws.greengrass.deployment.templating.RecipeTransformerException;
import com.aws.greengrass.deployment.templating.TemplateEngine;
import com.aws.greengrass.helper.PreloadComponentStoreHelper;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.status.FleetStatusService;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.NoOpPathOwnershipHandler;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType;
import static com.aws.greengrass.status.FleetStatusService.FLEET_STATUS_SERVICE_TOPICS;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.util.Utils.copyFolderRecursively;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class TemplateEngineTest extends BaseITCase {
    private static final Logger logger = LogManager.getLogger(TemplateEngineTest.class);
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private Kernel kernel;
    private DeploymentQueue deploymentQueue;
    private Path localStoreContentPath;
    @Mock
    private DeploymentDocumentDownloader deploymentDocumentDownloader;

    @BeforeEach
    void before(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, PackageDownloadException.class);
        ignoreExceptionOfType(context, SdkClientException.class);

        kernel = new Kernel();
        kernel.getContext().put(DeploymentDocumentDownloader.class, deploymentDocumentDownloader);
        NoOpPathOwnershipHandler.register(kernel);
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                TemplateEngineTest.class.getResource("onlyMain.yaml"));

        // ensure deployment service starts
        CountDownLatch deploymentServiceLatch = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(DEPLOYMENT_SERVICE_TOPICS) && newState.equals(State.RUNNING)) {
                deploymentServiceLatch.countDown();

            }
        });
        setDeviceConfig(kernel, DeviceConfiguration.DEPLOYMENT_POLLING_FREQUENCY_SECONDS, 1L);

        kernel.launch();
        assertTrue(deploymentServiceLatch.await(10, TimeUnit.SECONDS));
        deploymentQueue =  kernel.getContext().get(DeploymentQueue.class);

        FleetStatusService fleetStatusService = (FleetStatusService) kernel.locate(FLEET_STATUS_SERVICE_TOPICS);
        fleetStatusService.getIsConnected().set(false);
        // pre-load contents to package store
        localStoreContentPath =
                Paths.get(TemplateEngineTest.class.getResource("templating").toURI());
        PreloadComponentStoreHelper.preloadRecipesFromTestResourceDir(localStoreContentPath.resolve("recipes"),
                kernel.getNucleusPaths().recipePath());
        copyFolderRecursively(localStoreContentPath.resolve("artifacts"), kernel.getNucleusPaths().artifactPath(),
                REPLACE_EXISTING);
    }

    @AfterEach
    void after() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @Test
    void integTest() throws Exception {

        CountDownLatch firstDeploymentCDL = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(DeploymentType.LOCAL, (status) -> {
            if(status.get(DEPLOYMENT_ID_KEY_NAME).equals("firstDeployment") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")){
                firstDeploymentCDL.countDown();
            }
            return true;
        },"TemplateEngineTest" );

        String recipeDir = localStoreContentPath.resolve("recipes").toAbsolutePath().toString();
        String artifactsDir = localStoreContentPath.resolve("artifacts").toAbsolutePath().toString();

        Map<String, String> componentsToMerge = new HashMap<>();
        componentsToMerge.put("A", "1.0.0");
        componentsToMerge.put("ATemplate", "1.0.0");

        String dependencyUpdateConfigString =
                "{" +
                "  \"MERGE\": {" +
                "    \"param1\" : \"New param 1\"," +
                "    \"param2\" : \"New param2\"" +
                "  }," +
                "  \"RESET\": [" +
                "    \"/resetParam1\", \"/resetParam2\"" +
                "  ]" +
                "}";
        Map<String, ConfigurationUpdateOperation> updateConfig = new HashMap<>();
        updateConfig.put("A",
                OBJECT_MAPPER.readValue(dependencyUpdateConfigString, ConfigurationUpdateOperation.class));

        LocalOverrideRequest request = LocalOverrideRequest.builder().requestId("firstDeployment")
                .componentsToMerge(componentsToMerge)
                .requestTimestamp(System.currentTimeMillis())
                .configurationUpdate(updateConfig)
                .recipeDirectoryPath(recipeDir).artifactsDirectoryPath(artifactsDir).build();

        submitLocalDocument(request);

        assertTrue(firstDeploymentCDL.await(10, TimeUnit.SECONDS), "First deployment did not succeed");
    }

    @Test
    void unitTest() throws PackagingException, TemplateExecutionException, IOException, RecipeTransformerException {
        CountDownLatch firstDeploymentCDL = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(DeploymentType.LOCAL, (status) -> {
            if(status.get(DEPLOYMENT_ID_KEY_NAME).equals("firstDeployment") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")){
                firstDeploymentCDL.countDown();
            }
            return true;
        },"TemplateEngineTest" );

        Path recipeDir = localStoreContentPath.resolve("recipes").toAbsolutePath();
        Path artifactsDir = localStoreContentPath.resolve("artifacts").toAbsolutePath();
        Path recipeWorkDir = localStoreContentPath.resolve("_recipes_out");
        Path artifactsWorkDir = localStoreContentPath.resolve("_artifacts_out");
        try {
            Files.createDirectory(recipeWorkDir);
        } catch (FileAlreadyExistsException e) {}
        try {
            Files.createDirectory(artifactsWorkDir);
        } catch (FileAlreadyExistsException e) {}

        // if there are files, delete them first
        for (File file : Objects.requireNonNull(recipeWorkDir.toFile().listFiles())) {
            if (!file.delete()) throw new IOException("Could not delete work file " + file.getAbsolutePath());
        }
        Files.walk(artifactsWorkDir).collect(Collectors.toList()).forEach(source -> source.toFile().delete());

        // copy files to work directories
        for (File file : Objects.requireNonNull(recipeDir.toFile().listFiles())) {
            System.out.println(file.getPath());
            System.out.println(recipeWorkDir.resolve(file.getName()));
            Files.copy(Paths.get(file.getPath()), recipeWorkDir.resolve(file.getName()));
        }
        Files.walk(artifactsDir).collect(Collectors.toList()).forEach(source -> {
            try {
                Files.copy(source,
                        artifactsWorkDir.resolve(artifactsDir.relativize(source)), REPLACE_EXISTING);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        List<ComponentIdentifier> desiredPackages = Arrays.asList(
                new ComponentIdentifier("A", new Semver("1.0.0")),
                new ComponentIdentifier("ATemplate", new Semver("1.0.0")));

        String configStr = "{\n" + "  \"services\": {\n" + "    \"A\": {\n" + "      \"configuration\": {\n"
                + "        \"param1\": \"new param 1\",\n" + "        \"param2\": \"new param2\"\n" + "      }\n"
                + "    },\n" + "    \"ATemplate\": {\n" + "      \"configuration\": {\n"
                + "        \"resetParam1\": \"new old reset param 1\"\n" + "      }\n" + "    }\n" + "  }\n" + "}\n";
        Map<String, Object> configMap = OBJECT_MAPPER.readValue(configStr, HashMap.class);
        TemplateEngine templateEngine = new TemplateEngine(recipeWorkDir, artifactsWorkDir, desiredPackages, configMap);
        templateEngine.process();
    }

    private void submitSampleJobDocument(URI uri, String arn, DeploymentType type) throws Exception {
        Configuration deploymentConfiguration = OBJECT_MAPPER.readValue(new File(uri), Configuration.class);
        deploymentConfiguration.setCreationTimestamp(System.currentTimeMillis());
        deploymentConfiguration.setConfigurationArn(arn);
        Deployment deployment = new Deployment(OBJECT_MAPPER.writeValueAsString(deploymentConfiguration), type, deploymentConfiguration.getConfigurationArn());
        deploymentQueue.offer(deployment);
    }

    private void submitLocalDocument(LocalOverrideRequest request) throws Exception {
        Deployment deployment = new Deployment(OBJECT_MAPPER.writeValueAsString(request), DeploymentType.LOCAL, request.getRequestId());
        deploymentQueue.offer(deployment);
    }
}

