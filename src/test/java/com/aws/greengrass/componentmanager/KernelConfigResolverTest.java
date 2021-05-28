/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazon.aws.iot.greengrass.component.common.ComponentConfiguration;
import com.amazon.aws.iot.greengrass.component.common.DependencyProperties;
import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.amazon.aws.iot.greengrass.component.common.RecipeFormatVersion;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.UpdateBehaviorTree;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.model.ConfigurationUpdateOperation;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentPackageConfiguration;
import com.aws.greengrass.deployment.model.RunWith;
import com.aws.greengrass.deployment.model.SystemResourceLimits;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.NucleusPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.POSIX_USER_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUN_WITH_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SYSTEM_RESOURCE_LIMITS_TOPICS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.collection.IsMapContaining.hasKey;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
@SuppressWarnings("PMD.ExcessiveClassLength")
class KernelConfigResolverTest {
    private static final String LIFECYCLE_INSTALL_KEY = "install";
    private static final String LIFECYCLE_RUN_KEY = "run";
    private static final String LIFECYCLE_SCRIPT_KEY = "script";
    private static final String LIFECYCLE_MOCK_INSTALL_COMMAND_FORMAT =
            "echo installing service in Package %s with param , kernel rootPath as {" + KernelConfigResolver.KERNEL_NAMESPACE + ":" + KernelConfigResolver.KERNEL_ROOT_PATH + "} and "
                    + "unpack dir as {{" + KernelConfigResolver.ARTIFACTS_NAMESPACE + ":" + KernelConfigResolver.DECOMPRESSED_PATH_KEY + "}}";
    private static final String LIFECYCLE_INSTALL_COMMAND_FORMAT =
            "echo installing service in Component %s with param {" + KernelConfigResolver.CONFIGURATION_NAMESPACE + ":%s}, kernel rootPath as {" + KernelConfigResolver.KERNEL_NAMESPACE + ":" + KernelConfigResolver.KERNEL_ROOT_PATH + "} and "
                    + "unpack dir as {" + KernelConfigResolver.ARTIFACTS_NAMESPACE + ":" + KernelConfigResolver.DECOMPRESSED_PATH_KEY + "}";

    private static final String LIFECYCLE_MOCK_RUN_COMMAND_FORMAT =
            "echo running service in Package %s ";
    private static final String LIFECYCLE_RUN_COMMAND_FORMAT =
            "echo running service in Component %s with param {" + KernelConfigResolver.CONFIGURATION_NAMESPACE + ":%s}";

    private static final String LIFECYCLE_MOCK_CROSS_COMPONENT_FORMAT =
            "Package %s with param {{%s:params:%s_Param_1.value}} {{%s:" + KernelConfigResolver.ARTIFACTS_NAMESPACE + ":" + KernelConfigResolver.PATH_KEY + "}}";
    private static final String LIFECYCLE_CROSS_COMPONENT_FORMAT =
            "Component %s with param {%s:" + KernelConfigResolver.CONFIGURATION_NAMESPACE + ":%s}"
                    + " cross component %s artifact dir {%s:" + KernelConfigResolver.ARTIFACTS_NAMESPACE + ":"
                    + KernelConfigResolver.PATH_KEY + "}";

    private static final String TEST_INPUT_PACKAGE_A = "PackageA";
    private static final String TEST_INPUT_PACKAGE_B = "PackageB";
    private static final String TEST_INPUT_PACKAGE_C = "PackageC";
    private static final String TEST_NAMESPACE = "test";
    private static final Path DUMMY_ROOT_PATH = Paths.get("/dummyroot");
    private static final Path DUMMY_DECOMPRESSED_PATH_KEY = Paths.get("/dummyCompDir");
    private static final Path DUMMY_ARTIFACT_PATH = Paths.get("/dummyArtifactDir");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private Kernel kernel;
    @Mock
    private ComponentStore componentStore;
    @Mock
    private NucleusPaths nucleusPaths;
    @Mock
    private DeviceConfiguration deviceConfiguration;
    @Mock
    private GreengrassService mainService;
    @Mock
    private GreengrassService alreadyRunningService;
    @Mock
    private Topics alreadyRunningServiceConfig;

    @Mock
    private Topics alreadyRunningServiceRunWithConfig;

    private Path path;
    private Configuration config;

    @BeforeEach
    void setupMocks() throws IOException {
        path = Paths.get("Artifacts", TEST_INPUT_PACKAGE_A);
        lenient().when(nucleusPaths.artifactPath(any())).thenReturn(path.toAbsolutePath());
        config = new Configuration(new Context());
        lenient().when(kernel.getConfig()).thenReturn(config);
        lenient().when(deviceConfiguration.getNucleusComponentName()).thenReturn(DEFAULT_NUCLEUS_COMPONENT_NAME);
    }

    @AfterEach
    void afterEach() throws IOException {
        kernel.getConfig().context.close();
    }

    @Test
    void GIVEN_deployment_for_package_WHEN_config_resolution_requested_THEN_add_service_and_dependency_service()
            throws Exception {
        // GIVEN
        ComponentIdentifier rootComponentIdentifier =
                new ComponentIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2.0", Semver.SemverType.NPM));
        ComponentIdentifier dependencyComponentIdentifier =
                new ComponentIdentifier(TEST_INPUT_PACKAGE_B, new Semver("2.3.0", Semver.SemverType.NPM));
        List<ComponentIdentifier> packagesToDeploy = Arrays.asList(rootComponentIdentifier,
                dependencyComponentIdentifier);

        ComponentRecipe rootComponentRecipe = getPackage(TEST_INPUT_PACKAGE_A, "1.2.0",
                Collections.singletonMap(TEST_INPUT_PACKAGE_B,
                        DependencyProperties.builder().versionRequirement("2.3").build()), TEST_INPUT_PACKAGE_A);
        ComponentRecipe dependencyComponentRecipe =
                getPackage(TEST_INPUT_PACKAGE_B, "2.3.0", Collections.emptyMap(), TEST_INPUT_PACKAGE_B);

        SystemResourceLimits systemResourceLimits = new SystemResourceLimits(
                new SystemResourceLimits.LinuxSystemResourceLimits(102400L, 1.5));
        Map<String, Object> expectedSystemResourceLimits = new HashMap<>();
        Map<String, Object> expectedLinuxMap = new HashMap<>();
        expectedLinuxMap.put("cpu", 1.5);
        expectedLinuxMap.put("memory", 102400L);
        expectedSystemResourceLimits.put("linux", expectedLinuxMap);
        DeploymentPackageConfiguration rootPackageDeploymentConfig = DeploymentPackageConfiguration.builder()
                .packageName(TEST_INPUT_PACKAGE_A)
                .rootComponent(true)
                .resolvedVersion("=1.2")
                .runWith(RunWith.builder().posixUser("foo:bar").systemResourceLimits(systemResourceLimits).build())
                .build();

        DeploymentPackageConfiguration dependencyPackageDeploymentConfig =  DeploymentPackageConfiguration.builder()
                .packageName(TEST_INPUT_PACKAGE_B)
                .rootComponent(false)
                .resolvedVersion("=2.3")
                .build();

        DeploymentDocument document = DeploymentDocument.builder()
                                                        .deploymentPackageConfigurationList(
                                                                Arrays.asList(rootPackageDeploymentConfig,
                                                                        dependencyPackageDeploymentConfig))
                                                        .timestamp(10_000L)
                                                        .build();

        when(componentStore.getPackageRecipe(rootComponentIdentifier)).thenReturn(rootComponentRecipe);
        when(componentStore.getPackageRecipe(dependencyComponentIdentifier)).thenReturn(dependencyComponentRecipe);
        when(nucleusPaths.unarchiveArtifactPath(any())).thenReturn(DUMMY_DECOMPRESSED_PATH_KEY);
        when(kernel.getMain()).thenReturn(mainService);
        when(nucleusPaths.rootPath()).thenReturn(DUMMY_ROOT_PATH);
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies()).thenReturn(
                Collections.singletonMap(alreadyRunningService, DependencyType.HARD));
        when(alreadyRunningService.getName()).thenReturn("IpcService");
        when(alreadyRunningService.isBuiltin()).thenReturn(true);

        // WHEN
        KernelConfigResolver kernelConfigResolver = new KernelConfigResolver(componentStore, kernel, nucleusPaths,
                deviceConfiguration);
        Map<String, Object> resolvedConfig =
                kernelConfigResolver.resolve(packagesToDeploy, document, Arrays.asList(TEST_INPUT_PACKAGE_A));

        // THEN
        // service config
        Map<String, Object> servicesConfig = (Map<String, Object>) resolvedConfig.get(SERVICES_NAMESPACE_TOPIC);
        assertThat("Must contain main service", servicesConfig, hasKey("main"));
        assertThat("Must contain top level package service", servicesConfig, hasKey(TEST_INPUT_PACKAGE_A));
        assertThat("Must contain dependency service", servicesConfig, hasKey(TEST_INPUT_PACKAGE_B));

        Map<String, Object> serviceA = (Map<String, Object>)servicesConfig.get(TEST_INPUT_PACKAGE_A);
        assertThat("Service A must contain runWith", serviceA, hasKey(RUN_WITH_NAMESPACE_TOPIC));
        Map<String, Object> runWith = (Map<String, Object>)serviceA.get(RUN_WITH_NAMESPACE_TOPIC);
        assertThat("Service A must set posix user", runWith, hasEntry(POSIX_USER_KEY, "foo:bar"));
        assertThat("Service A must have system resource limits",runWith,
                hasEntry(SYSTEM_RESOURCE_LIMITS_TOPICS, expectedSystemResourceLimits) );

        Map<String, Object> serviceB = (Map<String, Object>)servicesConfig.get(TEST_INPUT_PACKAGE_B);
        assertThat("Service B must not have runWith", serviceB, not(hasKey(RUN_WITH_NAMESPACE_TOPIC)));

        // dependencies
        assertThat("Main service must depend on new service",
                dependencyListContains("main", TEST_INPUT_PACKAGE_A, servicesConfig));
        assertThat("Main service must depend on existing service",
                dependencyListContains("main", "IpcService" + ":" + DependencyType.HARD, servicesConfig));
        assertThat("New service must depend on dependency service",
                dependencyListContains(TEST_INPUT_PACKAGE_A, TEST_INPUT_PACKAGE_B, servicesConfig));
    }

    @Test
    void GIVEN_deployment_for_existing_package_WHEN_config_resolution_requested_THEN_update_service() throws Exception {
        // GIVEN
        ComponentIdentifier rootComponentIdentifier =
                new ComponentIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2.0", Semver.SemverType.NPM));
        List<ComponentIdentifier> packagesToDeploy = Arrays.asList(rootComponentIdentifier);

        ComponentRecipe rootComponentRecipe =
                getPackage(TEST_INPUT_PACKAGE_A, "1.2.0", Collections.emptyMap(), TEST_INPUT_PACKAGE_A);

        DeploymentPackageConfiguration rootPackageDeploymentConfig =
                new DeploymentPackageConfiguration(TEST_INPUT_PACKAGE_A, true, "=1.2");
        DeploymentDocument document = DeploymentDocument.builder()
                                                        .deploymentPackageConfigurationList(
                                                                Arrays.asList(rootPackageDeploymentConfig))
                                                        .timestamp(10_000L)
                                                        .build();

        when(componentStore.getPackageRecipe(rootComponentIdentifier)).thenReturn(rootComponentRecipe);
        when(nucleusPaths.unarchiveArtifactPath(rootComponentIdentifier)).thenReturn(DUMMY_DECOMPRESSED_PATH_KEY);
        when(kernel.getMain()).thenReturn(mainService);
        when(nucleusPaths.rootPath()).thenReturn(DUMMY_ROOT_PATH);
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies()).thenReturn(
                Collections.singletonMap(alreadyRunningService, DependencyType.HARD));
        when(alreadyRunningService.getName()).thenReturn(TEST_INPUT_PACKAGE_A);
        when(alreadyRunningService.isBuiltin()).thenReturn(true);

        // WHEN
        KernelConfigResolver kernelConfigResolver = new KernelConfigResolver(componentStore, kernel, nucleusPaths,
                deviceConfiguration);
        Map<String, Object> resolvedConfig =
                kernelConfigResolver.resolve(packagesToDeploy, document, Arrays.asList(TEST_INPUT_PACKAGE_A));

        // THEN
        // service config
        Map<String, Object> servicesConfig = (Map<String, Object>) resolvedConfig.get(SERVICES_NAMESPACE_TOPIC);
        assertThat("Must contain main service", servicesConfig, hasKey("main"));
        assertThat("Must contain updated service", servicesConfig, hasKey(TEST_INPUT_PACKAGE_A));

        // dependencies
        assertThat("Main service must depend on updated service",
                dependencyListContains("main", TEST_INPUT_PACKAGE_A, servicesConfig));
    }

    @Test
    void GIVEN_deployment_removing_run_with_posix_user_WHEN_previous_deployment_had_params_THEN_remove_run_with_posix_user()
            throws Exception {
        // GIVEN
        ComponentIdentifier rootComponentIdentifier =
                new ComponentIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2.0"));
        List<ComponentIdentifier> packagesToDeploy = Arrays.asList(rootComponentIdentifier);

        ComponentRecipe rootComponentRecipe = getPackage(TEST_INPUT_PACKAGE_A, "1.2.0", Collections.emptyMap(),
                TEST_INPUT_PACKAGE_A);

        DeploymentPackageConfiguration rootPackageDeploymentConfig =
                DeploymentPackageConfiguration.builder().packageName(TEST_INPUT_PACKAGE_A)
                        .rootComponent(true)
                        .resolvedVersion("=1.2")
                        .runWith(RunWith.builder().posixUser(null).build())
                        .build();
        DeploymentDocument document = DeploymentDocument.builder()
                .deploymentPackageConfigurationList(
                        Arrays.asList(rootPackageDeploymentConfig))
                .timestamp(10_000L)
                .build();

        when(componentStore.getPackageRecipe(rootComponentIdentifier)).thenReturn(rootComponentRecipe);
        when(nucleusPaths.unarchiveArtifactPath(rootComponentIdentifier)).thenReturn(DUMMY_DECOMPRESSED_PATH_KEY);
        when(kernel.getMain()).thenReturn(mainService);
        when(nucleusPaths.rootPath()).thenReturn(DUMMY_ROOT_PATH);
        when(kernel.findServiceTopic(TEST_INPUT_PACKAGE_A)).thenReturn(alreadyRunningServiceConfig);
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies()).thenReturn(
                Collections.singletonMap(alreadyRunningService, DependencyType.HARD));
        when(alreadyRunningService.getName()).thenReturn(TEST_INPUT_PACKAGE_A);
        when(alreadyRunningService.isBuiltin()).thenReturn(true);
        when(alreadyRunningServiceConfig.findTopics(RUN_WITH_NAMESPACE_TOPIC)).thenReturn(alreadyRunningServiceRunWithConfig);
        SystemResourceLimits systemResourceLimits = new SystemResourceLimits(
                new SystemResourceLimits.LinuxSystemResourceLimits(102400L, 1.5));
        when(alreadyRunningServiceRunWithConfig.toPOJO()).thenReturn(new HashMap<String, Object>() {{
            put(POSIX_USER_KEY, "foo:bar");
            put(SYSTEM_RESOURCE_LIMITS_TOPICS, systemResourceLimits);
        }});

        // WHEN
        KernelConfigResolver kernelConfigResolver = new KernelConfigResolver(componentStore, kernel, nucleusPaths,
                deviceConfiguration);
        Map<String, Object> resolvedConfig =
                kernelConfigResolver.resolve(packagesToDeploy, document, Arrays.asList(TEST_INPUT_PACKAGE_A));

        // THEN
        // service config
        Map<String, Object> servicesConfig = (Map<String, Object>) resolvedConfig.get(SERVICES_NAMESPACE_TOPIC);
        assertThat("Must contain main service", servicesConfig, hasKey("main"));
        assertThat("Must contain top level package service", servicesConfig, hasKey(TEST_INPUT_PACKAGE_A));

        assertThat(getServiceConfig(TEST_INPUT_PACKAGE_A, servicesConfig), hasKey(RUN_WITH_NAMESPACE_TOPIC));
        assertThat((Map<String, Object>)getServiceConfig(TEST_INPUT_PACKAGE_A, servicesConfig)
                .get(RUN_WITH_NAMESPACE_TOPIC), not(hasKey(POSIX_USER_KEY)));
        assertThat((Map<String, Object>)getServiceConfig(TEST_INPUT_PACKAGE_A, servicesConfig)
                .get(RUN_WITH_NAMESPACE_TOPIC), not(hasKey(SYSTEM_RESOURCE_LIMITS_TOPICS)));
    }

    @Test
    void GIVEN_deployment_with_artifact_WHEN_config_resolution_requested_THEN_artifact_path_should_be_interpolated()
            throws Exception {
        // GIVEN
        ComponentIdentifier rootComponentIdentifier =
                new ComponentIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2", Semver.SemverType.NPM));
        List<ComponentIdentifier> packagesToDeploy = Arrays.asList(rootComponentIdentifier);

        ComponentRecipe rootComponentRecipe = new ComponentRecipe(RecipeFormatVersion.JAN_25_2020, TEST_INPUT_PACKAGE_A,
                rootComponentIdentifier.getVersion(), "", "", null, new HashMap<String, Object>() {{
            put(LIFECYCLE_RUN_KEY, "java -jar {artifacts:path}/test.jar -x arg");
        }}, Collections.emptyList(), Collections.emptyMap(), null);

        DeploymentPackageConfiguration rootPackageDeploymentConfig =
                new DeploymentPackageConfiguration(TEST_INPUT_PACKAGE_A, true, "=1.2");
        DeploymentDocument document = DeploymentDocument.builder()
                                                        .deploymentPackageConfigurationList(
                                                                Arrays.asList(rootPackageDeploymentConfig))
                                                        .timestamp(10_000L)
                                                        .build();

        when(componentStore.getPackageRecipe(rootComponentIdentifier)).thenReturn(rootComponentRecipe);
        when(nucleusPaths.artifactPath(rootComponentIdentifier)).thenReturn(Paths.get("/packages/artifacts"));
        when(kernel.getMain()).thenReturn(mainService);
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies()).thenReturn(Collections.emptyMap());

        // WHEN
        KernelConfigResolver kernelConfigResolver = new KernelConfigResolver(componentStore, kernel, nucleusPaths,
                deviceConfiguration);
        Map<String, Object> resolvedConfig =
                kernelConfigResolver.resolve(packagesToDeploy, document, Arrays.asList(TEST_INPUT_PACKAGE_A));

        // THEN
        Map<String, Object> servicesConfig = (Map<String, Object>) resolvedConfig.get(SERVICES_NAMESPACE_TOPIC);

        Path jarPath = Paths.get("/packages/artifacts").toAbsolutePath();
        assertThat("{artifacts:path} should be replace by the package's artifact path",
                getServiceRunCommand(TEST_INPUT_PACKAGE_A, servicesConfig),
                equalTo("java -jar " + jarPath + "/test.jar -x arg"));
    }

    @Test
    void GIVEN_deployment_WHEN_config_resolution_requested_THEN_work_path_interpolated() throws Exception {
        // GIVEN
        ComponentIdentifier rootComponentIdentifier =
                new ComponentIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2", Semver.SemverType.NPM));
        List<ComponentIdentifier> packagesToDeploy = Arrays.asList(rootComponentIdentifier);

        ComponentRecipe rootComponentRecipe = new ComponentRecipe(RecipeFormatVersion.JAN_25_2020, TEST_INPUT_PACKAGE_A,
                rootComponentIdentifier.getVersion(), "", "", null, new HashMap<String, Object>() {{
            put(LIFECYCLE_RUN_KEY, "java -jar {work:path}/test.jar -x arg");
        }}, Collections.emptyList(), Collections.emptyMap(), null);

        DeploymentPackageConfiguration rootPackageDeploymentConfig =
                new DeploymentPackageConfiguration(TEST_INPUT_PACKAGE_A, true, "=1.2");
        DeploymentDocument document = DeploymentDocument.builder()
                .deploymentPackageConfigurationList(
                        Arrays.asList(rootPackageDeploymentConfig))
                .timestamp(10_000L)
                .build();

        Path expectedWorkPath = Paths.get("/work/" + TEST_INPUT_PACKAGE_A).toAbsolutePath();

        when(componentStore.getPackageRecipe(rootComponentIdentifier)).thenReturn(rootComponentRecipe);
        when(nucleusPaths.workPath(TEST_INPUT_PACKAGE_A)).thenReturn(expectedWorkPath);
        when(kernel.getMain()).thenReturn(mainService);
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies()).thenReturn(Collections.emptyMap());

        // WHEN
        KernelConfigResolver kernelConfigResolver = new KernelConfigResolver(componentStore, kernel, nucleusPaths,
                deviceConfiguration);
        Map<String, Object> resolvedConfig =
                kernelConfigResolver.resolve(packagesToDeploy, document, Arrays.asList(TEST_INPUT_PACKAGE_A));

        // THEN
        Map<String, Object> servicesConfig = (Map<String, Object>) resolvedConfig.get(SERVICES_NAMESPACE_TOPIC);

        assertThat("{work:path} should be replaced by the component's work path",
                getServiceRunCommand(TEST_INPUT_PACKAGE_A, servicesConfig),
                equalTo("java -jar " + expectedWorkPath + "/test.jar -x arg"));
    }

    @Test
    void GIVEN_deployment_WHEN_recipe_interpolate_non_dependency_THEN_config_not_interpolated() throws Exception {
        // GIVEN
        ComponentIdentifier componentIdentifierA =
                new ComponentIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2.0"));
        ComponentIdentifier componentIdentifierB =
                new ComponentIdentifier(TEST_INPUT_PACKAGE_B, new Semver("2.3.0"));

        String expectedRunCommand = String.format("java -jar -Dtestval={%s:work:path}", TEST_INPUT_PACKAGE_B);
        ComponentRecipe componentRecipeA = new ComponentRecipe(RecipeFormatVersion.JAN_25_2020, TEST_INPUT_PACKAGE_A,
                componentIdentifierA.getVersion(), "", "", null, new HashMap<String, Object>() {{
            put(LIFECYCLE_RUN_KEY, expectedRunCommand);
        }}, Collections.emptyList(), Collections.emptyMap(), null);
        ComponentRecipe componentRecipeB = new ComponentRecipe(RecipeFormatVersion.JAN_25_2020, TEST_INPUT_PACKAGE_B,
                componentIdentifierB.getVersion(), "", "", null, null, Collections.emptyList(), Collections.emptyMap(),
                null);

        DeploymentPackageConfiguration componentDeploymentConfigA = DeploymentPackageConfiguration.builder()
                .packageName(TEST_INPUT_PACKAGE_A)
                .rootComponent(true)
                .resolvedVersion("=1.2")
                .build();

        DeploymentPackageConfiguration componentDeploymentConfigB = DeploymentPackageConfiguration.builder()
                .packageName(TEST_INPUT_PACKAGE_B)
                .rootComponent(true)
                .resolvedVersion("=2.3")
                .build();
        DeploymentDocument document = DeploymentDocument.builder()
                .deploymentPackageConfigurationList(Arrays.asList(componentDeploymentConfigA,
                        componentDeploymentConfigB))
                .timestamp(10_000L)
                .build();

        Map<ComponentIdentifier, ComponentRecipe> componentsToResolve = new HashMap<>();
        componentsToResolve.put(componentIdentifierA, componentRecipeA);
        componentsToResolve.put(componentIdentifierB, componentRecipeB);
        Map<String, Object> servicesConfig = serviceConfigurationProperlyResolved(document, componentsToResolve);

        assertThat("variables should not be interpolated because of non-dependency",
                getServiceRunCommand(TEST_INPUT_PACKAGE_A, servicesConfig),
                equalTo(expectedRunCommand));
    }

    @Test
    void GIVEN_deployment_with_dependencies_WHEN_config_resolution_requested_THEN_cross_component_work_path_interpolated()
            throws Exception {
        // A depends on both B and C
        // GIVEN
        ComponentIdentifier componentIdentifierA =
                new ComponentIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2.0"));
        ComponentIdentifier componentIdentifierB =
                new ComponentIdentifier(TEST_INPUT_PACKAGE_B, new Semver("2.3.0"));
        ComponentIdentifier componentIdentifierC =
                new ComponentIdentifier(TEST_INPUT_PACKAGE_C, new Semver("3.4.0"));

        Map<String, DependencyProperties> componentADependencies = new HashMap<>();
        componentADependencies.put(TEST_INPUT_PACKAGE_B,
                DependencyProperties.builder().versionRequirement("2.3").build());
        componentADependencies.put(TEST_INPUT_PACKAGE_C,
                DependencyProperties.builder().versionRequirement("3.4").build());

        ComponentRecipe componentRecipeA = new ComponentRecipe(RecipeFormatVersion.JAN_25_2020, TEST_INPUT_PACKAGE_A,
                componentIdentifierA.getVersion(), "", "", null, new HashMap<String, Object>() {{
            put(LIFECYCLE_RUN_KEY,
                    String.format("java -jar {%s:work:path}/test.jar -Dtestval={%s:work:path}", TEST_INPUT_PACKAGE_B,
                            TEST_INPUT_PACKAGE_C));
        }}, Collections.emptyList(), componentADependencies, null);
        ComponentRecipe componentRecipeB = new ComponentRecipe(RecipeFormatVersion.JAN_25_2020, TEST_INPUT_PACKAGE_B,
                componentIdentifierB.getVersion(), "", "", null, null, Collections.emptyList(), Collections.emptyMap(),
                null);
        ComponentRecipe componentRecipeC = new ComponentRecipe(RecipeFormatVersion.JAN_25_2020, TEST_INPUT_PACKAGE_C,
                componentIdentifierC.getVersion(), "", "", null, null, Collections.emptyList(), Collections.emptyMap(),
                null);

        DeploymentPackageConfiguration componentDeploymentConfigA = DeploymentPackageConfiguration.builder()
                .packageName(TEST_INPUT_PACKAGE_A)
                .rootComponent(true)
                .resolvedVersion("=1.2")
                .build();

        DeploymentPackageConfiguration componentDeploymentConfigB = DeploymentPackageConfiguration.builder()
                .packageName(TEST_INPUT_PACKAGE_B)
                .rootComponent(false)
                .resolvedVersion("=2.3")
                .build();
        DeploymentPackageConfiguration componentDeploymentConfigC = DeploymentPackageConfiguration.builder()
                .packageName(TEST_INPUT_PACKAGE_C)
                .rootComponent(false)
                .resolvedVersion("=3.4")
                .build();
        DeploymentDocument document = DeploymentDocument.builder()
                .deploymentPackageConfigurationList(Arrays.asList(componentDeploymentConfigA,
                        componentDeploymentConfigB, componentDeploymentConfigC))
                .timestamp(10_000L)
                .build();

        // WHEN
        Map<ComponentIdentifier, ComponentRecipe> componentsToResolve = new HashMap<>();
        componentsToResolve.put(componentIdentifierA, componentRecipeA);
        componentsToResolve.put(componentIdentifierB, componentRecipeB);
        componentsToResolve.put(componentIdentifierC, componentRecipeC);
        Path expectedCompBWorkPath = Paths.get("/work/" + TEST_INPUT_PACKAGE_B).toAbsolutePath();
        Path expectedCompCWorkPath = Paths.get("/work/" + TEST_INPUT_PACKAGE_C).toAbsolutePath();
        when(nucleusPaths.workPath(TEST_INPUT_PACKAGE_B)).thenReturn(expectedCompBWorkPath);
        when(nucleusPaths.workPath(TEST_INPUT_PACKAGE_C)).thenReturn(expectedCompCWorkPath);
        Map<String, Object> servicesConfig = serviceConfigurationProperlyResolved(document, componentsToResolve);

        // THEN
        String expectedRunCommand = String.format("java -jar %s/test.jar -Dtestval=%s",
                expectedCompBWorkPath, expectedCompCWorkPath);
        assertThat("{artifacts:path} should be replace by the package's artifact path",
                getServiceRunCommand(TEST_INPUT_PACKAGE_A, servicesConfig),
                equalTo(expectedRunCommand));
    }

    @Test
    void GIVEN_component_has_default_configuration_and_no_running_configuration_WHEN_config_resolution_requested_THEN_correct_value_applied() throws Exception {
        // GIVEN
        ComponentIdentifier rootComponentIdentifier =
                new ComponentIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2.0"));

        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.with("startup").put("paramA", "valueA");
        ComponentRecipe rootComponentRecipe = getComponent(TEST_INPUT_PACKAGE_A, "1.2.0", Collections.emptyMap(),
                node, "/startup/paramA", null, null);

        DeploymentPackageConfiguration rootPackageDeploymentConfig = DeploymentPackageConfiguration.builder()
                .packageName(TEST_INPUT_PACKAGE_A)
                .rootComponent(true)
                .resolvedVersion(">=1.2")
                .build();
        DeploymentDocument document = DeploymentDocument.builder()
                .deploymentPackageConfigurationList(Collections.singletonList(rootPackageDeploymentConfig))
                .build();

        Map<String, Object> servicesConfig = serviceConfigurationProperlyResolved(document,
                Collections.singletonMap(rootComponentIdentifier, rootComponentRecipe));

        // parameter interpolation
        Map<String, String> serviceInstallCommand =
                (Map<String, String>) getServiceInstallCommand(TEST_INPUT_PACKAGE_A, servicesConfig);

        assertThat("no running and no update configuration, the default value should be used",
                serviceInstallCommand.get(LIFECYCLE_SCRIPT_KEY),
                equalTo("echo installing service in Component PackageA with param valueA,"
                        + " kernel rootPath as " + DUMMY_ROOT_PATH.toAbsolutePath().toString() + " and unpack dir as "
                        + DUMMY_DECOMPRESSED_PATH_KEY.toAbsolutePath().toString()));

        assertThat("no running and no update configuration, the default value should be used",
                getServiceRunCommand(TEST_INPUT_PACKAGE_A, servicesConfig),
                equalTo("echo running service in Component PackageA with param valueA"));
    }

    @Test
    void GIVEN_component_has_running_configuration_and_no_update_WHEN_config_resolution_requested_THEN_correct_value_applied() throws Exception {
        // GIVEN
        ComponentIdentifier rootComponentIdentifier =
                new ComponentIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2.0"));

        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.with("startup").put("paramA", "valueA");
        ComponentRecipe rootComponentRecipe = getComponent(TEST_INPUT_PACKAGE_A, "1.2.0", Collections.emptyMap(),
                node, "/startup/paramA", null, null);

        DeploymentPackageConfiguration rootPackageDeploymentConfig = DeploymentPackageConfiguration.builder()
                .packageName(TEST_INPUT_PACKAGE_A)
                .rootComponent(true)
                .resolvedVersion(">=1.2")
                .build();
        DeploymentDocument document = DeploymentDocument.builder()
                .deploymentPackageConfigurationList(Collections.singletonList(rootPackageDeploymentConfig))
                .timestamp(10_000L)
                .build();
        when(kernel.findServiceTopic(TEST_INPUT_PACKAGE_A)).thenReturn(config.lookupTopics(SERVICES_NAMESPACE_TOPIC,
                TEST_INPUT_PACKAGE_A));
        config.lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_INPUT_PACKAGE_A, CONFIGURATION_CONFIG_KEY)
                .updateFromMap(Collections.singletonMap("startup", Collections.singletonMap("paramA",
                        "valueB")), new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.REPLACE, 9_000L));


        Map<String, Object> servicesConfig = serviceConfigurationProperlyResolved(document,
                Collections.singletonMap(rootComponentIdentifier, rootComponentRecipe));

        // parameter interpolation
        Map<String, String> serviceInstallCommand =
                (Map<String, String>) getServiceInstallCommand(TEST_INPUT_PACKAGE_A, servicesConfig);

        assertThat("has running and no update configuration, the running value should be used",
                serviceInstallCommand.get(LIFECYCLE_SCRIPT_KEY),
                equalTo("echo installing service in Component PackageA with param valueB,"
                        + " kernel rootPath as " + DUMMY_ROOT_PATH.toAbsolutePath().toString() + " and unpack dir as "
                        + DUMMY_DECOMPRESSED_PATH_KEY.toAbsolutePath().toString()));

        assertThat("has running and no update configuration, the running value should be used",
                getServiceRunCommand(TEST_INPUT_PACKAGE_A, servicesConfig),
                equalTo("echo running service in Component PackageA with param valueB"));

        assertThat("no runwith is present", getServiceConfig(TEST_INPUT_PACKAGE_A, servicesConfig),
                not(hasKey(RUN_WITH_NAMESPACE_TOPIC)));
    }

    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private static Stream<Arguments> multiTimestampDeploymentSource() {
        return Stream.of(
                // Previous deployment was in the past, so use the new version
                Arguments.of(9000L, "valueC"),
                // Previous deployment was in the future, so use its version
                Arguments.of(11_000L, "valueB"),
                // Previous deployment had no value and current deployment has no value, so use the default
                Arguments.of(-1L, "valueA"));
    }

    @ParameterizedTest
    @MethodSource("multiTimestampDeploymentSource")
    void GIVEN_deployment_with_configuration_update_WHEN_config_resolution_requested_THEN_correct_value_applied
            (long previousDeploymentTimestamp, String expectedValue) throws Exception {
        // GIVEN
        ComponentIdentifier rootComponentIdentifier =
                new ComponentIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2.0"));

        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.with("startup").put("paramA", "valueA");
        ComponentRecipe rootComponentRecipe = getComponent(TEST_INPUT_PACKAGE_A, "1.2.0", Collections.emptyMap(),
                node, "/startup/paramA", null, null);

        ConfigurationUpdateOperation updateOperation = new ConfigurationUpdateOperation();
        updateOperation.setValueToMerge(Collections.singletonMap("startup", Collections.singletonMap("paramA",
                "valueC")));
        DeploymentPackageConfiguration rootPackageDeploymentConfig = DeploymentPackageConfiguration.builder()
                .packageName(TEST_INPUT_PACKAGE_A)
                .rootComponent(true)
                .resolvedVersion(">=1.2")
                // For a timestamp of -1, we want to not update anything so that the default gets used instead
                .configurationUpdateOperation(previousDeploymentTimestamp == -1 ? null : updateOperation)
                .build();
        DeploymentDocument document = DeploymentDocument.builder()
                .deploymentPackageConfigurationList(Collections.singletonList(rootPackageDeploymentConfig))
                .timestamp(10_000L)
                .build();

        when(kernel.findServiceTopic(TEST_INPUT_PACKAGE_A)).thenReturn(config.lookupTopics(SERVICES_NAMESPACE_TOPIC,
                TEST_INPUT_PACKAGE_A));
        config.lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_INPUT_PACKAGE_A, CONFIGURATION_CONFIG_KEY)
                .updateFromMap(Collections.singletonMap("startup", Collections.singletonMap("paramA",
                        "valueB")), new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.REPLACE,
                        previousDeploymentTimestamp));

        Map<String, Object> servicesConfig =
                serviceConfigurationProperlyResolved(document, Collections.singletonMap(rootComponentIdentifier,
                        rootComponentRecipe));

        // parameter interpolation
        Map<String, String> serviceInstallCommand =
                (Map<String, String>) getServiceInstallCommand(TEST_INPUT_PACKAGE_A, servicesConfig);

        assertThat("has running and has update configuration, the updated value should be used",
                serviceInstallCommand.get(LIFECYCLE_SCRIPT_KEY),
                equalTo("echo installing service in Component PackageA with param "+expectedValue+","
                        + " kernel rootPath as " + DUMMY_ROOT_PATH.toAbsolutePath().toString() + " and unpack dir as "
                        + DUMMY_DECOMPRESSED_PATH_KEY.toAbsolutePath().toString()));

        assertThat("has running and has update configuration, the updated value should be used",
                getServiceRunCommand(TEST_INPUT_PACKAGE_A, servicesConfig),
                equalTo("echo running service in Component PackageA with param " + expectedValue));
    }

    @Test
    void GIVEN_deployment_with_configuration_reset_WHEN_config_resolution_requested_THEN_default_value_applied() throws Exception {
        // GIVEN
        ComponentIdentifier rootComponentIdentifier =
                new ComponentIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2.0"));

        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.with("startup").put("paramA", "valueA");
        ComponentRecipe rootComponentRecipe = getComponent(TEST_INPUT_PACKAGE_A, "1.2.0", Collections.emptyMap(),
                node, "/startup/paramA", null, null);

        ConfigurationUpdateOperation updateOperation = new ConfigurationUpdateOperation();
        updateOperation.setPathsToReset(Arrays.asList("/startup/paramA", "/startup/paramB"));
        DeploymentPackageConfiguration rootPackageDeploymentConfig = DeploymentPackageConfiguration.builder()
                .packageName(TEST_INPUT_PACKAGE_A)
                .rootComponent(true)
                .resolvedVersion(">=1.2")
                .configurationUpdateOperation(updateOperation)
                .build();
        DeploymentDocument document = DeploymentDocument.builder()
                .deploymentPackageConfigurationList(Collections.singletonList(rootPackageDeploymentConfig))
                .timestamp(10_000L)
                .build();
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("paramA", "valueB");
        paramMap.put("paramB", "valueD");
        when(kernel.findServiceTopic(TEST_INPUT_PACKAGE_A)).thenReturn(config.lookupTopics(SERVICES_NAMESPACE_TOPIC,
                TEST_INPUT_PACKAGE_A));
        config.lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_INPUT_PACKAGE_A, CONFIGURATION_CONFIG_KEY)
                .updateFromMap(paramMap, new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.REPLACE, 9_000L));

        Map<String, Object> servicesConfig =
                serviceConfigurationProperlyResolved(document, Collections.singletonMap(rootComponentIdentifier,
                        rootComponentRecipe));

        // parameter interpolation
        Map<String, String> serviceInstallCommand =
                (Map<String, String>) getServiceInstallCommand(TEST_INPUT_PACKAGE_A, servicesConfig);

        assertThat("reset configuration, the default value should be used",
                serviceInstallCommand.get(LIFECYCLE_SCRIPT_KEY),
                equalTo("echo installing service in Component PackageA with param valueA,"
                        + " kernel rootPath as " + DUMMY_ROOT_PATH.toAbsolutePath().toString() + " and unpack dir as "
                        + DUMMY_DECOMPRESSED_PATH_KEY.toAbsolutePath().toString()));

        assertThat("reset configuration, the default value should be used",
                getServiceRunCommand(TEST_INPUT_PACKAGE_A, servicesConfig),
                equalTo("echo running service in Component PackageA with param valueA"));

        Map<String, Object> serviceConfiguration = getServiceConfiguration(TEST_INPUT_PACKAGE_A, servicesConfig);
        assertThat("configuration with default value should be reset to default value", ((Map)serviceConfiguration.get(
                "startup")).get("paramA"), is("valueA"));
        assertThat("configuration without default value should be removed", ((Map)serviceConfiguration.get(
                "startup")).get("paramB"), nullValue());
    }

    @Test
    void GIVEN_deployment_with_configuration_update_on_dependency_component_WHEN_config_resolution_requested_THEN_correct_value_applied_to_dependent_component() throws Exception {
        // GIVEN
        ComponentIdentifier componentIdentifierA =
                new ComponentIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2.0"));
        ComponentIdentifier componentIdentifierB =
                new ComponentIdentifier(TEST_INPUT_PACKAGE_B, new Semver("2.3.0"));
        ComponentIdentifier componentIdentifierC =
                new ComponentIdentifier(TEST_INPUT_PACKAGE_C, new Semver("3.4.0"));

        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.with("startup").put("paramA", "valueA");
        ComponentRecipe componentRecipeA = getComponent(TEST_INPUT_PACKAGE_A, "1.2.0",
                Collections.singletonMap(TEST_INPUT_PACKAGE_B,
                DependencyProperties.builder().versionRequirement("2.3").build()),
                node, "/startup/paramA", TEST_INPUT_PACKAGE_B, "/startup/paramB");
        ComponentRecipe componentRecipeB =
                getComponent(TEST_INPUT_PACKAGE_B, "2.3.0", Collections.emptyMap(),
                        null, "/startup/paramC", null, null);
        ComponentRecipe componentRecipeC =
                getComponent(TEST_INPUT_PACKAGE_C, "3.4.0", Collections.emptyMap(),
                        null, "/startup/paramA", TEST_INPUT_PACKAGE_B, "/startup/paramB");

        DeploymentPackageConfiguration componentDeploymentConfigA = DeploymentPackageConfiguration.builder()
                .packageName(TEST_INPUT_PACKAGE_A)
                .rootComponent(true)
                .resolvedVersion("=1.2")
                .build();
        ConfigurationUpdateOperation updateOperation = new ConfigurationUpdateOperation();
        updateOperation.setValueToMerge(Collections.singletonMap("startup", Collections.singletonMap("paramB",
                "valueB1")));
        DeploymentPackageConfiguration componentDeploymentConfigB = DeploymentPackageConfiguration.builder()
                .packageName(TEST_INPUT_PACKAGE_B)
                .rootComponent(true)
                .resolvedVersion("=2.3")
                .configurationUpdateOperation(updateOperation)
                .build();
        DeploymentPackageConfiguration componentDeploymentConfigC = DeploymentPackageConfiguration.builder()
                .packageName(TEST_INPUT_PACKAGE_C)
                .rootComponent(true)
                .resolvedVersion("=3.4")
                .build();
        DeploymentDocument document = DeploymentDocument.builder()
                .deploymentPackageConfigurationList(Arrays.asList(componentDeploymentConfigA,
                        componentDeploymentConfigB, componentDeploymentConfigC))
                .timestamp(10_000L)
                .build();

        when(kernel.findServiceTopic(TEST_INPUT_PACKAGE_A)).thenReturn(null);
        when(kernel.findServiceTopic(TEST_INPUT_PACKAGE_C)).thenReturn(null);
        Map<String, String> runningConfig = new HashMap<>();
        runningConfig.put("paramB", "valueB");
        runningConfig.put("paramC", "valueC");
        when(kernel.findServiceTopic(TEST_INPUT_PACKAGE_B))
                .thenReturn(config.lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_INPUT_PACKAGE_B));
        config.lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_INPUT_PACKAGE_B, CONFIGURATION_CONFIG_KEY)
                .updateFromMap(Collections.singletonMap("startup", runningConfig),
                        new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.REPLACE, 9_000L));

        when(nucleusPaths.artifactPath(any())).thenReturn(DUMMY_ARTIFACT_PATH);

        Map<ComponentIdentifier, ComponentRecipe> componentsToResolve = new HashMap<>();
        componentsToResolve.put(componentIdentifierA, componentRecipeA);
        componentsToResolve.put(componentIdentifierB, componentRecipeB);
        componentsToResolve.put(componentIdentifierC, componentRecipeC);
        Map<String, Object> servicesConfig =
                serviceConfigurationProperlyResolved(document, componentsToResolve);

        // parameter interpolation
        assertThat("service can reference the configuration of dependency service",
                getValueForLifecycleKey(TEST_NAMESPACE, TEST_INPUT_PACKAGE_A, servicesConfig),
                equalTo("Component PackageA with param valueB1 cross component " + TEST_INPUT_PACKAGE_B + " artifact "
                        + "dir " + DUMMY_ARTIFACT_PATH.toAbsolutePath()));

        // Since package C didn't have a dependency on B, it should not be allowed to read from B's configuration
        // this results in the configuration not being filled in
        assertThat("service cannot reference the configuration of non dependency service",
                getValueForLifecycleKey(TEST_NAMESPACE, TEST_INPUT_PACKAGE_C, servicesConfig),
                equalTo("Component PackageC with param {PackageB:configuration:/startup/paramB} cross component PackageB artifact dir {PackageB:artifacts:path}"));
    }

    @Test
    void GIVEN_deployment_with_params_not_set_WHEN_previous_deployment_had_params_THEN_use_params_from_previous_deployment()
            throws Exception {
        // GIVEN
        ComponentIdentifier rootComponentIdentifier =
                new ComponentIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2.0"));
        List<ComponentIdentifier> packagesToDeploy = Arrays.asList(rootComponentIdentifier);

        ComponentRecipe rootComponentRecipe = getPackage(TEST_INPUT_PACKAGE_A, "1.2.0", Collections.emptyMap(),
                TEST_INPUT_PACKAGE_A);

        DeploymentPackageConfiguration rootPackageDeploymentConfig =
                new DeploymentPackageConfiguration(TEST_INPUT_PACKAGE_A, true, "=1.2");
        DeploymentDocument document = DeploymentDocument.builder()
                .deploymentPackageConfigurationList(
                        Arrays.asList(rootPackageDeploymentConfig))
                .timestamp(10_000L)
                .build();

        when(componentStore.getPackageRecipe(rootComponentIdentifier)).thenReturn(rootComponentRecipe);
        when(nucleusPaths.unarchiveArtifactPath(rootComponentIdentifier)).thenReturn(DUMMY_DECOMPRESSED_PATH_KEY);
        when(kernel.getMain()).thenReturn(mainService);
        when(nucleusPaths.rootPath()).thenReturn(DUMMY_ROOT_PATH);
        when(kernel.findServiceTopic(TEST_INPUT_PACKAGE_A)).thenReturn(alreadyRunningServiceConfig);
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies()).thenReturn(
                Collections.singletonMap(alreadyRunningService, DependencyType.HARD));
        when(alreadyRunningService.getName()).thenReturn(TEST_INPUT_PACKAGE_A);
        when(alreadyRunningService.isBuiltin()).thenReturn(true);
        when(alreadyRunningServiceConfig.findTopics(RUN_WITH_NAMESPACE_TOPIC)).thenReturn(alreadyRunningServiceRunWithConfig);
        when(alreadyRunningServiceRunWithConfig.toPOJO()).thenReturn(new HashMap<String, Object>() {{
            put("posixUser", "foo:bar");
        }});
        // WHEN
        KernelConfigResolver kernelConfigResolver = new KernelConfigResolver(componentStore, kernel, nucleusPaths,
                deviceConfiguration);
        Map<String, Object> resolvedConfig =
                kernelConfigResolver.resolve(packagesToDeploy, document, Arrays.asList(TEST_INPUT_PACKAGE_A));

        // THEN
        // service config
        Map<String, Object> servicesConfig = (Map<String, Object>) resolvedConfig.get(SERVICES_NAMESPACE_TOPIC);
        assertThat("Must contain main service", servicesConfig, hasKey("main"));
        assertThat("Must contain top level package service", servicesConfig, hasKey(TEST_INPUT_PACKAGE_A));

        assertThat(getServiceConfig(TEST_INPUT_PACKAGE_A, servicesConfig), hasKey(RUN_WITH_NAMESPACE_TOPIC));
        assertThat((Map<String,
                        Object>)getServiceConfig(TEST_INPUT_PACKAGE_A, servicesConfig).get(RUN_WITH_NAMESPACE_TOPIC),
                hasEntry(POSIX_USER_KEY, "foo:bar"));
    }

    @Test
    void GIVEN_deployment_for_package_WHEN_run_with_not_specified_on_non_root_component_THEN_existing_run_with_used()
            throws Exception {
        // GIVEN
        ComponentIdentifier rootComponentIdentifier =
                new ComponentIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2.0", Semver.SemverType.NPM));
        ComponentIdentifier dependencyComponentIdentifier =
                new ComponentIdentifier(TEST_INPUT_PACKAGE_B, new Semver("2.3.0", Semver.SemverType.NPM));
        List<ComponentIdentifier> packagesToDeploy = Arrays.asList(rootComponentIdentifier,
                dependencyComponentIdentifier);

        ComponentRecipe rootComponentRecipe = getPackage(TEST_INPUT_PACKAGE_A, "1.2.0",
                Collections.singletonMap(TEST_INPUT_PACKAGE_B,
                        DependencyProperties.builder().versionRequirement("2.3").build()), TEST_INPUT_PACKAGE_A);
        ComponentRecipe dependencyComponentRecipe =
                getPackage(TEST_INPUT_PACKAGE_B, "2.3.0", Collections.emptyMap(), TEST_INPUT_PACKAGE_B);

        DeploymentPackageConfiguration rootPackageDeploymentConfig = DeploymentPackageConfiguration.builder()
                .packageName(TEST_INPUT_PACKAGE_A)
                .rootComponent(true)
                .resolvedVersion("=1.2")
                .build();

        DeploymentPackageConfiguration dependencyPackageDeploymentConfig =  DeploymentPackageConfiguration.builder()
                .packageName(TEST_INPUT_PACKAGE_B)
                .rootComponent(false)
                .resolvedVersion("=2.3")
                .build();

        DeploymentDocument document = DeploymentDocument.builder()
                .deploymentPackageConfigurationList(
                        Arrays.asList(rootPackageDeploymentConfig,
                                dependencyPackageDeploymentConfig))
                .timestamp(10_000L)
                .build();

        when(componentStore.getPackageRecipe(rootComponentIdentifier)).thenReturn(rootComponentRecipe);
        when(componentStore.getPackageRecipe(dependencyComponentIdentifier)).thenReturn(dependencyComponentRecipe);
        when(nucleusPaths.unarchiveArtifactPath(any())).thenReturn(DUMMY_DECOMPRESSED_PATH_KEY);
        when(kernel.getMain()).thenReturn(mainService);
        when(nucleusPaths.rootPath()).thenReturn(DUMMY_ROOT_PATH);
        when(mainService.getName()).thenReturn("main");

        when(kernel.findServiceTopic(TEST_INPUT_PACKAGE_A)).thenReturn(null); // no config for service a

        // existing config for non root service b
        when(kernel.findServiceTopic(TEST_INPUT_PACKAGE_B)).thenReturn(alreadyRunningServiceConfig);
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies()).thenReturn(
                Collections.singletonMap(alreadyRunningService, DependencyType.HARD));
        when(alreadyRunningService.getName()).thenReturn(TEST_INPUT_PACKAGE_B); // not a root component
        when(alreadyRunningService.isBuiltin()).thenReturn(true);
        when(alreadyRunningServiceConfig.findTopics(RUN_WITH_NAMESPACE_TOPIC)).thenReturn(alreadyRunningServiceRunWithConfig);
        when(alreadyRunningServiceRunWithConfig.toPOJO()).thenReturn(new HashMap<String, Object>() {{
            put("posixUser", "foo:bar");
        }});
        when(alreadyRunningService.isBuiltin()).thenReturn(true);

        // WHEN
        KernelConfigResolver kernelConfigResolver = new KernelConfigResolver(componentStore, kernel, nucleusPaths,
                deviceConfiguration);
        Map<String, Object> resolvedConfig =
                kernelConfigResolver.resolve(packagesToDeploy, document, Arrays.asList(TEST_INPUT_PACKAGE_A));

        // THEN
        // service config
        Map<String, Object> servicesConfig = (Map<String, Object>) resolvedConfig.get(SERVICES_NAMESPACE_TOPIC);
        assertThat("Must contain main service", servicesConfig, hasKey("main"));
        assertThat("Must contain top level package service", servicesConfig, hasKey(TEST_INPUT_PACKAGE_A));
        assertThat("Must contain dependency service", servicesConfig, hasKey(TEST_INPUT_PACKAGE_B));

        Map<String, Object> serviceA = (Map<String, Object>)servicesConfig.get(TEST_INPUT_PACKAGE_A);
        assertThat("Service A must not have runWith", serviceA, not(hasKey(RUN_WITH_NAMESPACE_TOPIC)));

        Map<String, Object> serviceB = (Map<String, Object>)servicesConfig.get(TEST_INPUT_PACKAGE_B);
        assertThat("Service B must contain runWith", serviceB, hasKey(RUN_WITH_NAMESPACE_TOPIC));
        Map<String, Object> runWith = (Map<String, Object>)serviceB.get(RUN_WITH_NAMESPACE_TOPIC);
        assertThat("Service B must set posix user", runWith, hasEntry(POSIX_USER_KEY, "foo:bar"));
    }

    private Map<String, Object> serviceConfigurationProperlyResolved(DeploymentDocument deploymentDocument,
                                                                     Map<ComponentIdentifier,
                                                                             ComponentRecipe> componentsToResolve)
            throws Exception {
        for (ComponentIdentifier componentIdentifier : componentsToResolve.keySet()) {
            when(componentStore.getPackageRecipe(componentIdentifier)).thenReturn(componentsToResolve.get(componentIdentifier));
        }
        lenient().when(nucleusPaths.unarchiveArtifactPath(any())).thenReturn(DUMMY_DECOMPRESSED_PATH_KEY);
        when(kernel.getMain()).thenReturn(mainService);
        lenient().when(nucleusPaths.rootPath()).thenReturn(DUMMY_ROOT_PATH);
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies()).thenReturn(Collections.emptyMap());

        // WHEN
        KernelConfigResolver kernelConfigResolver = new KernelConfigResolver(componentStore, kernel, nucleusPaths,
                deviceConfiguration);
        Map<String, Object> resolvedConfig =
                kernelConfigResolver.resolve(new ArrayList<>(componentsToResolve.keySet()), deploymentDocument,
                        deploymentDocument.getDeploymentPackageConfigurationList().stream().filter(
                                DeploymentPackageConfiguration::isRootComponent).map(
                                DeploymentPackageConfiguration::getPackageName).collect(
                                Collectors.toList()));

        // THEN
        // service config
        Map<String, Object> servicesConfig = (Map<String, Object>) resolvedConfig.get(SERVICES_NAMESPACE_TOPIC);
        assertThat("Must contain main service", servicesConfig, hasKey("main"));
        for (ComponentIdentifier componentIdentifier : componentsToResolve.keySet()) {
            assertThat("Must contain top level package service", servicesConfig, hasKey(componentIdentifier.getName()));
        }

        return servicesConfig;
    }

    // utilities for mocking input
    private ComponentRecipe getPackage(String packageName, String packageVersion,
                                       Map<String, DependencyProperties> dependencies,
                                       String crossComponentName) {

        Semver version = new Semver(packageVersion);
        return new ComponentRecipe(RecipeFormatVersion.JAN_25_2020, packageName, version, "Test package", "Publisher",
                null, getSimplePackageLifecycle(packageName, crossComponentName), Collections.emptyList(),
                dependencies, null);
    }

    private ComponentRecipe getComponent(String componentName, String componentVersion, Map<String,
            DependencyProperties> dependencies, JsonNode defaultConfiguration, String jsonPointerStr,
                                         String crossComponentName, String crossComponentJsonPointerStr) {
        ComponentConfiguration componentConfiguration = defaultConfiguration == null ? null :
                ComponentConfiguration.builder().defaultConfiguration(defaultConfiguration).build();

        Semver version = new Semver(componentVersion);
        return new ComponentRecipe(RecipeFormatVersion.JAN_25_2020, componentName, version, "component in test",
                "publisher", componentConfiguration,
                getSimpleComponentLifecycle(componentName, jsonPointerStr, crossComponentName,
                        crossComponentJsonPointerStr), Collections.emptyList(), dependencies, null);
    }

    private Map<String, Object> getSimplePackageLifecycle(String packageName, String crossComponentName) {
        Map<String, Object> lifecycle = new HashMap<>();
        Map<String, Object> installCommands = new HashMap<>();
        lifecycle.put(TEST_NAMESPACE,
                String.format(LIFECYCLE_MOCK_CROSS_COMPONENT_FORMAT, packageName, crossComponentName,
                        crossComponentName, crossComponentName));
        lifecycle.put(LIFECYCLE_INSTALL_KEY, installCommands);
        installCommands.put(LIFECYCLE_SCRIPT_KEY,
                String.format(LIFECYCLE_MOCK_INSTALL_COMMAND_FORMAT, packageName, packageName));

        // Short form is allowed as well, test both cases
        lifecycle.put(LIFECYCLE_RUN_KEY, String.format(LIFECYCLE_MOCK_RUN_COMMAND_FORMAT, packageName, packageName));
        return lifecycle;
    }

    private Map<String, Object> getSimpleComponentLifecycle(String componentName, String jsonPointerStr,
                                                            String crossComponentName,
                                                            String crossComponentJsonPointerStr) {
        Map<String, Object> lifecycle = new HashMap<>();
        Map<String, Object> installCommands = new HashMap<>();

        if (crossComponentName != null) {
            lifecycle.put(TEST_NAMESPACE,
                    String.format(LIFECYCLE_CROSS_COMPONENT_FORMAT, componentName, crossComponentName,
                            crossComponentJsonPointerStr, crossComponentName, crossComponentName));
        }

        installCommands.put(LIFECYCLE_SCRIPT_KEY, String.format(LIFECYCLE_INSTALL_COMMAND_FORMAT, componentName, jsonPointerStr));
        lifecycle.put(LIFECYCLE_INSTALL_KEY, installCommands);

        // Short form is allowed as well, test both cases
        lifecycle.put(LIFECYCLE_RUN_KEY, String.format(LIFECYCLE_RUN_COMMAND_FORMAT, componentName, jsonPointerStr));
        return lifecycle;
    }

    // utilities for verification
    private Object getServiceRunCommand(String serviceName, Map<String, Object> config) {
        return getValueForLifecycleKey(LIFECYCLE_RUN_KEY, serviceName, config);
    }

    private Object getServiceInstallCommand(String serviceName, Map<String, Object> config) {
        return getValueForLifecycleKey(LIFECYCLE_INSTALL_KEY, serviceName, config);
    }

    private boolean dependencyListContains(String serviceName, String dependencyName, Map<String, Object> config) {
        Iterable<String> dependencyList =
                (Iterable<String>) getServiceConfig(serviceName, config).get(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC);
        return StreamSupport.stream(dependencyList.spliterator(), false).anyMatch(itr -> itr.contains(dependencyName));
    }

    private Object getValueForLifecycleKey(String key, String serviceName, Map<String, Object> config) {
        Map<String, Object> serviceConfig = getServiceConfig(serviceName, config);
        return ((Map<String, Object>) serviceConfig.get(GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC)).get(key);
    }

    private Map<String, Object> getServiceConfiguration(String serviceName, Map<String, Object> config) {
        Map<String, Object> serviceConfig = getServiceConfig(serviceName, config);
        return (Map<String, Object>) serviceConfig.get(CONFIGURATION_CONFIG_KEY);
    }

    private Map<String, Object> getServiceConfig(String serviceName, Map<String, Object> config) {
        return (Map<String, Object>) config.get(serviceName);
    }
}
