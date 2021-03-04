/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.amazon.aws.iot.greengrass.component.common.ComponentType;
import com.amazon.aws.iot.greengrass.component.common.RecipeFormatVersion;
import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.exceptions.ComponentConfigurationValidationException;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Utils;
import com.vdurmont.semver4j.Semver;
import lombok.Getter;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.config.Topic.DEFAULT_VALUE_TIMESTAMP;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.deployment.DeviceConfiguration.FALLBACK_DEFAULT_REGION;
import static com.aws.greengrass.deployment.DeviceConfiguration.GGC_VERSION_ENV;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SETENV_CONFIG_NAMESPACE;
import static com.aws.greengrass.lifecyclemanager.Kernel.SERVICE_TYPE_TOPIC_KEY;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class DeviceConfigurationTest {
    @Mock
    Kernel mockKernel;
    @Mock
    Configuration configuration;
    @Mock
    Topic mockTopic;
    @Mock
    Topics mockTopics;

    DeviceConfiguration deviceConfiguration;

    @BeforeEach
    void beforeEach() {
        NucleusPaths nucleusPaths = mock(NucleusPaths.class);
        Topics rootConfigTopics = mock(Topics.class);
        when(rootConfigTopics.findOrDefault(any(), anyString(), anyString(), anyString())).thenReturn(new ArrayList<>());
        lenient().when(configuration.lookup(anyString(), anyString(), anyString())).thenReturn(mock(Topic.class));
        when(configuration.lookup(anyString(), anyString(), anyString(), anyString())).thenReturn(mockTopic);
        lenient().when(configuration.getRoot()).thenReturn(rootConfigTopics);
        when(mockKernel.getConfig()).thenReturn(configuration);
        lenient().when(mockKernel.getNucleusPaths()).thenReturn(nucleusPaths);

        Topics topics = Topics.of(mock(Context.class), SERVICES_NAMESPACE_TOPIC, mock(Topics.class));
        when(mockTopics.subscribe(any())).thenReturn(mockTopics);
        when(configuration.lookupTopics(anyString(), anyString(), anyString())).thenReturn(mockTopics);
        when(configuration.lookupTopics(anyString(), anyString(), anyString(), anyString())).thenReturn(mockTopics);
        when(configuration.lookupTopics(anyString())).thenReturn(topics);
        lenient().when(configuration.lookupTopics(anyString())).thenReturn(topics);
    }

    @Test
    void WHEN_isDeviceConfiguredToTalkToCloud_THEN_validate_called_when_cache_is_null() throws DeviceConfigurationException {
        deviceConfiguration = spy(new DeviceConfiguration(mockKernel));
        doNothing().when(deviceConfiguration).validate(true);
        deviceConfiguration.isDeviceConfiguredToTalkToCloud();
        verify(deviceConfiguration, times(1)).validate(true);
        deviceConfiguration.isDeviceConfiguredToTalkToCloud();
        verify(deviceConfiguration, times(1)).validate(true);
    }

    @Test
    void GIVEN_good_config_WHEN_validate_THEN_succeeds() {
        deviceConfiguration = new DeviceConfiguration(mockKernel);
        assertDoesNotThrow(() -> deviceConfiguration.validateEndpoints("us-east-1", "xxxxxx.credentials.iot.us-east-1.amazonaws.com",
                "xxxxxx-ats.iot.us-east-1.amazonaws.com"));
    }

    @Test
    void GIVEN_bad_cred_endpoint_config_WHEN_validate_THEN_fails() {
        deviceConfiguration = new DeviceConfiguration(mockKernel);
        ComponentConfigurationValidationException ex = assertThrows(ComponentConfigurationValidationException.class,
                () -> deviceConfiguration.validateEndpoints("us-east-1", "xxxxxx.credentials.iot.us-east-2.amazonaws.com",
                        "xxxxxx-ats.iot.us-east-1.amazonaws.com"));
        assertEquals("IoT credential endpoint region xxxxxx.credentials.iot.us-east-2.amazonaws.com does not match the AWS region us-east-1 of the device", ex.getMessage());
    }

    @Test
    void GIVEN_bad_data_endpoint_config_WHEN_validate_THEN_fails() {
        deviceConfiguration = new DeviceConfiguration(mockKernel);
        ComponentConfigurationValidationException ex = assertThrows(ComponentConfigurationValidationException.class,
                () -> deviceConfiguration.validateEndpoints("us-east-1", "xxxxxx.credentials.iot.us-east-1.amazonaws.com",
                        "xxxxxx-ats.iot.us-east-2.amazonaws.com"));
        assertEquals("IoT data endpoint region xxxxxx-ats.iot.us-east-2.amazonaws.com does not match the AWS region us-east-1 of the device", ex.getMessage());
    }

    @Test
    void GIVEN_config_WHEN_set_bad_aws_region_THEN_fallback_to_default(@Mock Context mockContext) {
        Topic testingTopic = Topic.of(mockContext, "testing", null);
        when(configuration.lookup(anyString(), anyString(), anyString(), anyString())).thenReturn(testingTopic);
        when(mockTopic.withValue(anyString())).thenReturn(mockTopic);
        when(configuration.lookup(eq(SETENV_CONFIG_NAMESPACE), anyString())).thenReturn(mockTopic);

        deviceConfiguration = new DeviceConfiguration(mockKernel);
        deviceConfiguration.setAWSRegion("nowhere-south-42");
        assertEquals(FALLBACK_DEFAULT_REGION, testingTopic.getOnce());
    }

    @Test
    void GIVEN_no_launch_param_file_WHEN_persistInitialLaunchParams_THEN_write_jvm_args_to_file(@TempDir Path tempDir)
            throws Exception {
        deviceConfiguration = new DeviceConfiguration(mockKernel);
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        Path tempFile = tempDir.resolve("testFile");
        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();
        deviceConfiguration.persistInitialLaunchParams(kernelAlternatives);
        verify(kernelAlternatives).writeLaunchParamsToFile(anyString());
    }

    @Test
    void GIVEN_existing_launch_param_file_WHEN_persistInitialLaunchParams_THEN_skip(@TempDir Path tempDir)
            throws Exception {
        deviceConfiguration = new DeviceConfiguration(mockKernel);
        KernelAlternatives kernelAlternatives = mock(KernelAlternatives.class);
        Path tempFile = tempDir.resolve("testFile");
        Files.createFile(tempFile);

        doReturn(tempFile).when(kernelAlternatives).getLaunchParamsPath();
        deviceConfiguration.persistInitialLaunchParams(kernelAlternatives);
        verify(kernelAlternatives, times(0)).writeLaunchParamsToFile(anyString());
    }

    @Test
    void GIVEN_recipe_WHEN_initializeNucleusLifecycleConfig_THEN_init_lifecycle_and_dependencies(
            @Mock KernelConfigResolver kernelConfigResolver, @Mock Context context) throws Exception {
        deviceConfiguration = new DeviceConfiguration(mockKernel);
        List mockDependencies = Arrays.asList("A", "B", "C");
        doReturn(mockDependencies).when(kernelConfigResolver).generateServiceDependencies(anyMap());
        Object interpolatedLifecycle = new HashMap<String, String>() {{
            put("lifecycle", "step");
        }};
        doReturn(interpolatedLifecycle).when(kernelConfigResolver).interpolate(anyMap(), any(), anySet(), anyMap());

        doReturn(kernelConfigResolver).when(context).get(KernelConfigResolver.class);
        doReturn(context).when(mockKernel).getContext();

        String nucleusComponentName = "test.component.name";
        ComponentRecipe componentRecipe =
                ComponentRecipe.builder().lifecycle((Map<String, Object>) interpolatedLifecycle).build();

        Topics lifecycleTopics = spy(Topics.of(context, SERVICE_LIFECYCLE_NAMESPACE_TOPIC, null));
        when(configuration.lookupTopics(eq(DEFAULT_VALUE_TIMESTAMP), eq(SERVICES_NAMESPACE_TOPIC),
                eq(nucleusComponentName), eq(SERVICE_LIFECYCLE_NAMESPACE_TOPIC))).thenReturn(lifecycleTopics);
        Topic dependencyTopic = Topic.of(context, SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, null);
        when(configuration.lookup(eq(DEFAULT_VALUE_TIMESTAMP), eq(SERVICES_NAMESPACE_TOPIC),
                eq(nucleusComponentName), eq(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC))).thenReturn(dependencyTopic);

        deviceConfiguration.initializeNucleusLifecycleConfig(nucleusComponentName, componentRecipe);
        assertEquals(mockDependencies, dependencyTopic.toPOJO());
        verify(lifecycleTopics).replaceAndWait((Map<String, Object>) interpolatedLifecycle);
    }

    @Test
    void GIVEN_version_WHEN_initializeNucleusVersion_THEN_init_component_version_and_env_var(@Mock Context context) {
        deviceConfiguration = new DeviceConfiguration(mockKernel);
        String nucleusComponentName = "test.component.name";
        String nucleusComponentVersion = "test-version";

        Topic versionTopic = Topic.of(context, VERSION_CONFIG_KEY, null);
        when(configuration.lookup(eq(SERVICES_NAMESPACE_TOPIC), eq(nucleusComponentName), eq(VERSION_CONFIG_KEY)))
                .thenReturn(versionTopic);
        Topic envTopic = Topic.of(context, GGC_VERSION_ENV, null);
        when(configuration.lookup(eq(SETENV_CONFIG_NAMESPACE), eq(GGC_VERSION_ENV)))
                .thenReturn(envTopic);

        deviceConfiguration.initializeNucleusVersion(nucleusComponentName, nucleusComponentVersion);
        assertEquals(nucleusComponentVersion, versionTopic.getOnce());
        assertEquals(nucleusComponentVersion, envTopic.getOnce());
    }

    @Test
    void GIVEN_component_store_already_setup_WHEN_initializeComponentStore_THEN_do_nothing(
            @TempDir Path tempDir, @Mock Context context, @Mock ComponentStore componentStore,
            @Mock NucleusPaths nucleusPaths) throws Exception {
        deviceConfiguration = new DeviceConfiguration(mockKernel);
        String nucleusComponentName = "test.component.name";
        Semver nucleusComponentVersion = new Semver("1.0.0");

        Path recipePath = tempDir.resolve("mockRecipe");
        Files.createFile(recipePath);
        doReturn(recipePath).when(componentStore).resolveRecipePath(any());
        doReturn(tempDir).when(nucleusPaths).unarchiveArtifactPath(any(), anyString());

        doReturn(componentStore).when(context).get(ComponentStore.class);
        doReturn(nucleusPaths).when(context).get(NucleusPaths.class);
        doReturn(context).when(mockKernel).getContext();

        DeviceConfiguration spyDeviceConfig = spy(deviceConfiguration);
        spyDeviceConfig.initializeComponentStore(nucleusComponentName, nucleusComponentVersion, recipePath,
                tempDir);
        verify(spyDeviceConfig, times(0)).copyUnpackedNucleusArtifacts(any(), any());
        verify(componentStore, times(0)).savePackageRecipe(any(), any());
    }

    @Test
    void GIVEN_component_store_not_setup_WHEN_initializeComponentStore_THEN_copy_to_component_store(
            @TempDir Path dstRecipeDir, @TempDir Path dstArtifactsDir, @TempDir Path unpackDir, @Mock Context context,
            @Mock ComponentStore componentStore, @Mock NucleusPaths nucleusPaths) throws Exception {
        deviceConfiguration = new DeviceConfiguration(mockKernel);
        String nucleusComponentName = "test.component.name";
        Semver nucleusComponentVersion = new Semver("1.0.0");

        Path dstRecipePath = dstRecipeDir.resolve("recipe.yaml");
        doReturn(dstRecipePath).when(componentStore).resolveRecipePath(any());
        doReturn(dstArtifactsDir).when(nucleusPaths).unarchiveArtifactPath(any(), anyString());
        doReturn(componentStore).when(context).get(ComponentStore.class);
        doReturn(nucleusPaths).when(context).get(NucleusPaths.class);
        doReturn(context).when(mockKernel).getContext();

        MockNucleusUnpackDir mockNucleusUnpackDir = new MockNucleusUnpackDir(unpackDir);
        String mockRecipeContent = getRecipeSerializer().writeValueAsString(
                com.amazon.aws.iot.greengrass.component.common.ComponentRecipe.builder()
                        .componentName(nucleusComponentName).componentVersion(nucleusComponentVersion)
                        .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020).build());
        mockNucleusUnpackDir.setup(mockRecipeContent);

        deviceConfiguration.initializeComponentStore(nucleusComponentName, nucleusComponentVersion,
                mockNucleusUnpackDir.getConfRecipe(), unpackDir);

        ComponentIdentifier componentIdentifier = new ComponentIdentifier(nucleusComponentName,
                nucleusComponentVersion);
        verify(componentStore).savePackageRecipe(eq(componentIdentifier), eq(mockRecipeContent));
        mockNucleusUnpackDir.assertDirectoryEquals(dstArtifactsDir);
    }

    @Test
    void GIVEN_unpack_dir_is_nucleus_root_WHEN_initializeComponentStore_THEN_copy_to_component_store(
            @TempDir Path unpackDir, @Mock Context context, @Mock ComponentStore componentStore) throws Exception {
        deviceConfiguration = new DeviceConfiguration(mockKernel);
        String nucleusComponentName = "test.component.name";
        Semver nucleusComponentVersion = new Semver("1.0.0");

        // Set up Nucleus root
        NucleusPaths nucleusPaths = new NucleusPaths();
        nucleusPaths.setRootPath(unpackDir);
        nucleusPaths.initPaths(unpackDir, unpackDir.resolve("work"), unpackDir.resolve("packages"),
                unpackDir.resolve("config"), unpackDir.resolve("alts"), unpackDir.resolve("deployments"),
                unpackDir.resolve("cli_ipc_info"), unpackDir.resolve("bin"));
        Files.createFile(nucleusPaths.binPath().resolve("greengrass-cli"));
        Files.createFile(nucleusPaths.recipePath().resolve("someRecipe.yaml"));

        Path actualRecipe = nucleusPaths.recipePath().resolve("nucleusRecipe.yaml");
        doReturn(actualRecipe).when(componentStore).resolveRecipePath(any());
        doReturn(componentStore).when(context).get(ComponentStore.class);
        doReturn(nucleusPaths).when(context).get(NucleusPaths.class);
        doReturn(context).when(mockKernel).getContext();

        // Set up unpack dir in Nucleus root
        MockNucleusUnpackDir mockNucleusUnpackDir = new MockNucleusUnpackDir(unpackDir);
        String mockRecipeContent = getRecipeSerializer().writeValueAsString(
                com.amazon.aws.iot.greengrass.component.common.ComponentRecipe.builder()
                        .componentName(nucleusComponentName).componentVersion(nucleusComponentVersion)
                        .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020).build());
        mockNucleusUnpackDir.setup(mockRecipeContent);

        // Should only copy artifact files to component store
        deviceConfiguration.initializeComponentStore(nucleusComponentName, nucleusComponentVersion,
                mockNucleusUnpackDir.getConfRecipe(), unpackDir);

        ComponentIdentifier componentIdentifier = new ComponentIdentifier(nucleusComponentName,
                nucleusComponentVersion);
        verify(componentStore).savePackageRecipe(eq(componentIdentifier), eq(mockRecipeContent));
        mockNucleusUnpackDir.assertDirectoryEquals(nucleusPaths.unarchiveArtifactPath(
                componentIdentifier, DEFAULT_NUCLEUS_COMPONENT_NAME.toLowerCase()));
    }

    @Test
    void GIVEN_existing_config_including_nucleus_version_WHEN_init_device_config_THEN_use_nucleus_version_from_config()
            throws Exception {
        try (Context context = new Context()) {
            Topics servicesConfig = Topics.of(context, SERVICES_NAMESPACE_TOPIC, null);
            Topics nucleusConfig = servicesConfig.lookupTopics(DEFAULT_NUCLEUS_COMPONENT_NAME);
            Topic componentTypeConfig =
                    nucleusConfig.lookup(SERVICE_TYPE_TOPIC_KEY).withValue(ComponentType.NUCLEUS.name());
            Topic nucleusVersionConfig = nucleusConfig.lookup(VERSION_CONFIG_KEY).withValue("99.99.99");

            lenient().when(configuration.lookupTopics(SERVICES_NAMESPACE_TOPIC)).thenReturn(servicesConfig);
            lenient().when(configuration
                    .lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, VERSION_CONFIG_KEY))
                    .thenReturn(nucleusVersionConfig);
            lenient().when(configuration
                    .lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, SERVICE_TYPE_TOPIC_KEY))
                    .thenReturn(componentTypeConfig);
            when(mockKernel.findServiceTopic(DEFAULT_NUCLEUS_COMPONENT_NAME)).thenReturn(nucleusConfig);
            deviceConfiguration = new DeviceConfiguration(mockKernel);

            // Confirm version config didn't get overwritten with default
            assertEquals("99.99.99", Coerce.toString(nucleusVersionConfig));
            assertEquals("99.99.99", deviceConfiguration.getNucleusVersion());
        }

    }

    @Test
    void GIVEN_existing_config_with_no_nucleus_version_WHEN_init_device_config_THEN_use_default_nucleus_version()
            throws Exception {
        try (Context context = new Context()) {
            Topics servicesConfig = Topics.of(context, SERVICES_NAMESPACE_TOPIC, null);
            Topics nucleusConfig = servicesConfig.lookupTopics(DEFAULT_NUCLEUS_COMPONENT_NAME);
            Topic componentTypeConfig =
                    nucleusConfig.lookup(SERVICE_TYPE_TOPIC_KEY).withValue(ComponentType.NUCLEUS.name());

            lenient().when(configuration.lookupTopics(SERVICES_NAMESPACE_TOPIC)).thenReturn(servicesConfig);
            lenient().when(configuration
                    .lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, SERVICE_TYPE_TOPIC_KEY))
                    .thenReturn(componentTypeConfig);
            when(mockKernel.findServiceTopic(DEFAULT_NUCLEUS_COMPONENT_NAME)).thenReturn(nucleusConfig);

            deviceConfiguration = new DeviceConfiguration(mockKernel);

            // Expect fallback version in the absence of version information from build files
            assertEquals("0.0.0", deviceConfiguration.getNucleusVersion());
        }
    }

    @Test
    void GIVEN_no_existing_config_WHEN_init_device_config_THEN_use_default_nucleus_config() throws Exception {
        try (Context context = new Context()) {
            Topics servicesConfig = Topics.of(context, SERVICES_NAMESPACE_TOPIC, null);

            lenient().when(configuration.lookupTopics(SERVICES_NAMESPACE_TOPIC)).thenReturn(servicesConfig);

            deviceConfiguration = new DeviceConfiguration(mockKernel);

            when(mockKernel.findServiceTopic(DEFAULT_NUCLEUS_COMPONENT_NAME))
                    .thenReturn(servicesConfig.findTopics(DEFAULT_NUCLEUS_COMPONENT_NAME));

            // Expect fallback version in the absence of version information from build files
            assertEquals("0.0.0", deviceConfiguration.getNucleusVersion());
        }
    }

    class MockNucleusUnpackDir {
        Path root;
        Path conf;
        @Getter
        Path bin;
        Path lib;
        Path license;
        @Getter
        Path confRecipe;
        Path libJar;
        Path binLoader;
        Path binTemplate;

        public MockNucleusUnpackDir(Path root) {
            this.root = root;
            this.conf = root.resolve("conf");
            this.bin = root.resolve("bin");
            this.lib = root.resolve("lib");
            this.license = root.resolve("LICENSE");
            this.confRecipe = conf.resolve("recipe.yaml");
            this.libJar = lib.resolve("Greengrass.jar");
            this.binLoader = bin.resolve("loader");
            this.binTemplate = bin.resolve("greengrass.service.template");
        }

        public void setup(String recipeContent) throws IOException {
            Utils.createPaths(conf);
            Utils.createPaths(bin);
            Utils.createPaths(lib);

            for (Path file : Arrays.asList(license, libJar, binLoader, binTemplate)) {
                try (BufferedWriter writer = Files.newBufferedWriter(file)) {
                    writer.append(file.getFileName().toString());
                }
            }
            FileUtils.writeStringToFile(confRecipe.toFile(), recipeContent);
        }

        public void assertDirectoryEquals(Path actual) {
            assertThat(Arrays.asList(actual.toFile().list()), containsInAnyOrder("conf", "bin", "lib", "LICENSE"));
            assertThat(Arrays.asList(actual.resolve("bin").toFile().list()), containsInAnyOrder(
                    "loader", "greengrass.service.template"));
            assertThat(Arrays.asList(actual.resolve("conf").toFile().list()), containsInAnyOrder("recipe.yaml"));
            assertThat(Arrays.asList(actual.resolve("lib").toFile().list()), containsInAnyOrder("Greengrass.jar"));
        }
    }

}
