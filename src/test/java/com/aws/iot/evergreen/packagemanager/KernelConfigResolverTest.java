/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.DependencyType;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageLoadingException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.models.PackageParameter;
import com.aws.iot.evergreen.packagemanager.models.PackageRecipe;
import com.aws.iot.evergreen.packagemanager.models.RecipeDependencyProperties;
import com.aws.iot.evergreen.packagemanager.models.RecipeTemplateVersion;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.util.Utils;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.ARTIFACTS_NAMESPACE;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAM_NAMESPACE;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAM_VALUE_SUFFIX;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PATH_KEY;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsMapContaining.hasKey;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
class KernelConfigResolverTest {
    private static final String LIFECYCLE_INSTALL_KEY = "install";
    private static final String LIFECYCLE_RUN_KEY = "run";
    private static final String LIFECYCLE_SCRIPT_KEY = "script";
    private static final String LIFECYCLE_MOCK_INSTALL_COMMAND_FORMAT =
            "echo installing service in Package %s with param {{" + PARAM_NAMESPACE + ":%s_Param_1" + PARAM_VALUE_SUFFIX
                    + "}}, kernel rootPath as {{kernel:rootPath}} and unpack dir as {{artifacts:unpackPath}}";
                    
    private static final String LIFECYCLE_MOCK_RUN_COMMAND_FORMAT =
            "echo running service in Package %s with param {{" + PARAM_NAMESPACE + ":%s_Param_2" + PARAM_VALUE_SUFFIX
                    + "}}";
    private static final String LIFECYCLE_MOCK_CROSS_COMPONENT_FORMAT =
            "Package %s with param {{%s:params:%s_Param_1.value}} {{%s:" + ARTIFACTS_NAMESPACE + ":" + PATH_KEY + "}}";
    private static final String TEST_INPUT_PACKAGE_A = "PackageA";
    private static final String TEST_INPUT_PACKAGE_B = "PackageB";
    private static final String TEST_INPUT_PACKAGE_C = "PackageC";
    private static final String TEST_NAMESPACE = "test";
    @Mock
    private Kernel kernel;
    @Mock
    private PackageStore packageStore;
    @Mock
    private EvergreenService mainService;
    @Mock
    private EvergreenService alreadyRunningService;
    @Mock
    private Topics alreadyRunningServiceConfig;
    @Mock
    private Topic alreadyRunningServiceParameterConfig;
    private Path path;

    @BeforeEach
    void setupMocks() {
        path = Paths.get("Artifacts", TEST_INPUT_PACKAGE_A);
        lenient().when(packageStore.resolveArtifactDirectoryPath(any())).thenReturn(path.toAbsolutePath());
    }

    @Test
    void GIVEN_deployment_for_package_WHEN_config_resolution_requested_THEN_add_service_and_dependency_service()
            throws Exception {
        // GIVEN
        PackageIdentifier rootPackageIdentifier =
                new PackageIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2", Semver.SemverType.NPM));
        PackageIdentifier dependencyPackageIdentifier =
                new PackageIdentifier(TEST_INPUT_PACKAGE_B, new Semver("2.3", Semver.SemverType.NPM));
        List<PackageIdentifier> packagesToDeploy = Arrays.asList(rootPackageIdentifier, dependencyPackageIdentifier);

        PackageRecipe rootPackageRecipe = getPackage(TEST_INPUT_PACKAGE_A, "1.2",
                Collections.singletonMap(TEST_INPUT_PACKAGE_B, new RecipeDependencyProperties("2.3")),
                Collections.emptyMap(), TEST_INPUT_PACKAGE_A);
        PackageRecipe dependencyPackageRecipe =
                getPackage(TEST_INPUT_PACKAGE_B, "2.3", Collections.emptyMap(), Collections.emptyMap(),
                        TEST_INPUT_PACKAGE_B);

        DeploymentPackageConfiguration rootPackageDeploymentConfig =
                new DeploymentPackageConfiguration(TEST_INPUT_PACKAGE_A, true, "1.2", Collections.emptyMap());
        DeploymentPackageConfiguration dependencyPackageDeploymentConfig =
                new DeploymentPackageConfiguration(TEST_INPUT_PACKAGE_B, false, "2.3", Collections.emptyMap());
        DeploymentDocument document = DeploymentDocument.builder().rootPackages(Arrays.asList(TEST_INPUT_PACKAGE_A))
                .deploymentPackageConfigurationList(
                        Arrays.asList(rootPackageDeploymentConfig, dependencyPackageDeploymentConfig)).build();

        when(packageStore.getPackageRecipe(rootPackageIdentifier)).thenReturn(rootPackageRecipe);
        when(packageStore.getPackageRecipe(dependencyPackageIdentifier)).thenReturn(dependencyPackageRecipe);
        when(packageStore.resolveAndSetupArtifactsUnpackDirectory(any()))
                .thenReturn(Paths.get("/dummyUnpackDir"));
        when(kernel.getMain()).thenReturn(mainService);
        when(kernel.getRootPath()).thenReturn(Paths.get("/dummyroot"));
        when(kernel.locate(any())).thenThrow(new ServiceLoadException("Service not found"));
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies())
                .thenReturn(Collections.singletonMap(alreadyRunningService, DependencyType.HARD));
        when(alreadyRunningService.getName()).thenReturn("IpcService");
        when(alreadyRunningService.isAutostart()).thenReturn(true);

        // WHEN
        KernelConfigResolver kernelConfigResolver = new KernelConfigResolver(packageStore, kernel);
        Map<Object, Object> resolvedConfig =
                kernelConfigResolver.resolve(packagesToDeploy, document, Arrays.asList(TEST_INPUT_PACKAGE_A));

        // THEN
        // service config
        Map<Object, Object> servicesConfig = (Map<Object, Object>) resolvedConfig.get(SERVICES_NAMESPACE_TOPIC);
        assertThat("Must contain main service", servicesConfig, hasKey("main"));
        assertThat("Must contain top level package service", servicesConfig, hasKey(TEST_INPUT_PACKAGE_A));
        assertThat("Must contain dependency service", servicesConfig, hasKey(TEST_INPUT_PACKAGE_B));

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
        PackageIdentifier rootPackageIdentifier =
                new PackageIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2", Semver.SemverType.NPM));
        List<PackageIdentifier> packagesToDeploy = Arrays.asList(rootPackageIdentifier);

        PackageRecipe rootPackageRecipe =
                getPackage(TEST_INPUT_PACKAGE_A, "1.2", Collections.emptyMap(), Collections.emptyMap(),
                        TEST_INPUT_PACKAGE_A);

        DeploymentPackageConfiguration rootPackageDeploymentConfig =
                new DeploymentPackageConfiguration(TEST_INPUT_PACKAGE_A, true, "1.2", Collections.emptyMap());
        DeploymentDocument document = DeploymentDocument.builder().rootPackages(Arrays.asList(TEST_INPUT_PACKAGE_A))
                .deploymentPackageConfigurationList(Arrays.asList(rootPackageDeploymentConfig)).build();

        when(packageStore.getPackageRecipe(rootPackageIdentifier)).thenReturn(rootPackageRecipe);
        when(packageStore.resolveAndSetupArtifactsUnpackDirectory(rootPackageIdentifier))
                .thenReturn(Paths.get("/dummyUnpackDir"));
        when(kernel.getMain()).thenReturn(mainService);
        when(kernel.getRootPath()).thenReturn(Paths.get("/dummyroot"));
        when(kernel.locate(TEST_INPUT_PACKAGE_A)).thenReturn(alreadyRunningService);
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies())
                .thenReturn(Collections.singletonMap(alreadyRunningService, DependencyType.HARD));
        when(alreadyRunningService.getName()).thenReturn(TEST_INPUT_PACKAGE_A);
        when(alreadyRunningService.isAutostart()).thenReturn(true);

        // WHEN
        KernelConfigResolver kernelConfigResolver = new KernelConfigResolver(packageStore, kernel);
        Map<Object, Object> resolvedConfig =
                kernelConfigResolver.resolve(packagesToDeploy, document, Arrays.asList(TEST_INPUT_PACKAGE_A));

        // THEN
        // service config
        Map<Object, Object> servicesConfig = (Map<Object, Object>) resolvedConfig.get(SERVICES_NAMESPACE_TOPIC);
        assertThat("Must contain main service", servicesConfig, hasKey("main"));
        assertThat("Must contain updated service", servicesConfig, hasKey(TEST_INPUT_PACKAGE_A));

        // dependencies
        assertThat("Main service must depend on updated service",
                dependencyListContains("main", TEST_INPUT_PACKAGE_A, servicesConfig));
    }

    @Test
    void GIVEN_deployment_with_parameters_set_WHEN_config_resolution_requested_THEN_parameters_should_be_interpolated()
            throws Exception {
        // GIVEN
        PackageIdentifier rootPackageIdentifier =
                new PackageIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2", Semver.SemverType.NPM));
        List<PackageIdentifier> packagesToDeploy = Arrays.asList(rootPackageIdentifier);

        PackageRecipe rootPackageRecipe = getPackage(TEST_INPUT_PACKAGE_A, "1.2", Collections.emptyMap(),
                getSimpleParameterMap(TEST_INPUT_PACKAGE_A), TEST_INPUT_PACKAGE_A);

        DeploymentPackageConfiguration rootPackageDeploymentConfig =
                new DeploymentPackageConfiguration(TEST_INPUT_PACKAGE_A, true, "1.2", new HashMap<String, Object>() {{
                    put("PackageA_Param_1", "PackageA_Param_1_value");
                }});
        DeploymentDocument document = DeploymentDocument.builder().rootPackages(Arrays.asList(TEST_INPUT_PACKAGE_A))
                .deploymentPackageConfigurationList(Arrays.asList(rootPackageDeploymentConfig)).build();

        when(packageStore.getPackageRecipe(rootPackageIdentifier)).thenReturn(rootPackageRecipe);
        when(packageStore.resolveAndSetupArtifactsUnpackDirectory(rootPackageIdentifier))
                .thenReturn(Paths.get("/dummyUnpackDir"));
        when(kernel.getMain()).thenReturn(mainService);
        when(kernel.getRootPath()).thenReturn(Paths.get("/dummyroot"));
        when(kernel.locate(any())).thenThrow(new ServiceLoadException("Service not found"));
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies()).thenReturn(Collections.emptyMap());

        // WHEN
        KernelConfigResolver kernelConfigResolver = new KernelConfigResolver(packageStore, kernel);
        Map<Object, Object> resolvedConfig =
                kernelConfigResolver.resolve(packagesToDeploy, document, Arrays.asList(TEST_INPUT_PACKAGE_A));

        // THEN
        // service config
        Map<Object, Object> servicesConfig = (Map<Object, Object>) resolvedConfig.get(SERVICES_NAMESPACE_TOPIC);
        assertThat("Must contain main service", servicesConfig, hasKey("main"));
        assertThat("Must contain top level package service", servicesConfig, hasKey(TEST_INPUT_PACKAGE_A));

        // parameter interpolation
        Map<String, String> serviceInstallCommand =
                (Map<String, String>) getServiceInstallCommand(TEST_INPUT_PACKAGE_A, servicesConfig);

        // Parameter value set in deployment will be used for lifecycle install section
        assertThat("If parameter value was set in deployment, it should be used",
                serviceInstallCommand.get(LIFECYCLE_SCRIPT_KEY),
                equalTo("echo installing service in Package PackageA with param PackageA_Param_1_value," +
                        " kernel rootPath as /dummyroot and unpack dir as /dummyUnpackDir"));

        // Parameter value was not set in deployment, so default will be used for lifecycle run section
        assertThat("If no parameter value was set in deployment, the default value should be used",
                getServiceRunCommand(TEST_INPUT_PACKAGE_A, servicesConfig),
                equalTo("echo running service in Package " + "PackageA with param " +
                        "PackageA_Param_2_default_value"));
    }

    @Test
    void GIVEN_deployment_with_parameters_set_WHEN_config_resolution_requested_THEN_cross_component_parameters_should_be_interpolated()
            throws Exception {
        // GIVEN
        PackageIdentifier rootPackageIdentifier =
                new PackageIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2", Semver.SemverType.NPM));
        PackageIdentifier package2 =
                new PackageIdentifier(TEST_INPUT_PACKAGE_B, new Semver("1.5", Semver.SemverType.NPM));
        PackageIdentifier package3 =
                new PackageIdentifier(TEST_INPUT_PACKAGE_C, new Semver("1.5", Semver.SemverType.NPM));
        List<PackageIdentifier> packagesToDeploy = Arrays.asList(rootPackageIdentifier, package2, package3);

        PackageRecipe rootPackageRecipe = getPackage(TEST_INPUT_PACKAGE_A, "1.2", Collections.emptyMap(),
                getSimpleParameterMap(TEST_INPUT_PACKAGE_A), TEST_INPUT_PACKAGE_A);
        PackageRecipe package2Recipe = getPackage(TEST_INPUT_PACKAGE_B, "1.5", Utils.immutableMap(TEST_INPUT_PACKAGE_A,
                new RecipeDependencyProperties("=1.2", DependencyType.HARD.toString())),
                getSimpleParameterMap(TEST_INPUT_PACKAGE_B), TEST_INPUT_PACKAGE_A);
        PackageRecipe package3Recipe = getPackage(TEST_INPUT_PACKAGE_C, "1.5", Collections.emptyMap(),
                getSimpleParameterMap(TEST_INPUT_PACKAGE_C), TEST_INPUT_PACKAGE_A);

        DeploymentPackageConfiguration rootPackageDeploymentConfig =
                new DeploymentPackageConfiguration(TEST_INPUT_PACKAGE_A, true, "1.2", new HashMap<String, Object>() {{
                    put("PackageA_Param_1", "PackageA_Param_1_value");
                }});
        DeploymentPackageConfiguration package2DeploymentConfig =
                new DeploymentPackageConfiguration(TEST_INPUT_PACKAGE_B, true, "1.2", new HashMap<String, Object>() {{
                    put("PackageB_Param_1", "PackageB_Param_1_value");
                }});
        DeploymentPackageConfiguration package3DeploymentConfig =
                new DeploymentPackageConfiguration(TEST_INPUT_PACKAGE_C, true, "1.2", Collections.emptyMap());
        DeploymentDocument document = DeploymentDocument.builder()
                .rootPackages(Arrays.asList(TEST_INPUT_PACKAGE_A, TEST_INPUT_PACKAGE_B, TEST_INPUT_PACKAGE_C))
                .deploymentPackageConfigurationList(
                        Arrays.asList(rootPackageDeploymentConfig, package2DeploymentConfig, package3DeploymentConfig))
                .build();

        when(packageStore.getPackageRecipe(rootPackageIdentifier)).thenReturn(rootPackageRecipe);
        when(packageStore.getPackageRecipe(package2)).thenReturn(package2Recipe);
        when(packageStore.getPackageRecipe(package3)).thenReturn(package3Recipe);
        when(packageStore.resolveAndSetupArtifactsUnpackDirectory(any()))
                .thenReturn(Paths.get("/dummyUnpackDir"));
        when(kernel.getMain()).thenReturn(mainService);
        when(kernel.getRootPath()).thenReturn(Paths.get("/dummyroot"));
        when(kernel.locate(any())).thenThrow(new ServiceLoadException("Service not found"));
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies()).thenReturn(Collections.emptyMap());

        // WHEN
        KernelConfigResolver kernelConfigResolver = new KernelConfigResolver(packageStore, kernel);
        Map<Object, Object> resolvedConfig =
                kernelConfigResolver.resolve(packagesToDeploy, document, Arrays.asList(TEST_INPUT_PACKAGE_A));

        // THEN
        // service config
        Map<Object, Object> servicesConfig = (Map<Object, Object>) resolvedConfig.get(SERVICES_NAMESPACE_TOPIC);

        // parameter interpolation
        String serviceTestCommand =
                (String) getValueForLifecycleKey(TEST_NAMESPACE, TEST_INPUT_PACKAGE_B, servicesConfig);

        assertThat(serviceTestCommand,
                equalTo("Package PackageB with param PackageA_Param_1_value " + path.toAbsolutePath().toString()));

        // Since package C didn't have a dependency on A, it should not be allowed to read from A's parameters
        // this results in the parameters not being filled in
        serviceTestCommand =
                (String) getValueForLifecycleKey(TEST_NAMESPACE, TEST_INPUT_PACKAGE_C, servicesConfig);
        assertThat(serviceTestCommand,
                equalTo("Package PackageC with param {{PackageA:params:PackageA_Param_1.value}} {{PackageA:artifacts:path}}"));
    }

    @Test
    void GIVEN_deployment_with_params_not_set_WHEN_previous_deployment_had_params_THEN_use_params_from_previous_deployment()
            throws Exception {
        // GIVEN
        PackageIdentifier rootPackageIdentifier =
                new PackageIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2", Semver.SemverType.NPM));
        List<PackageIdentifier> packagesToDeploy = Arrays.asList(rootPackageIdentifier);

        PackageRecipe rootPackageRecipe = getPackage(TEST_INPUT_PACKAGE_A, "1.2", Collections.emptyMap(),
                getSimpleParameterMap(TEST_INPUT_PACKAGE_A), TEST_INPUT_PACKAGE_A);

        DeploymentPackageConfiguration rootPackageDeploymentConfig =
                new DeploymentPackageConfiguration(TEST_INPUT_PACKAGE_A, true, "1.2", Collections.emptyMap());
        DeploymentDocument document = DeploymentDocument.builder().rootPackages(Arrays.asList(TEST_INPUT_PACKAGE_A))
                .deploymentPackageConfigurationList(Arrays.asList(rootPackageDeploymentConfig)).build();

        when(packageStore.getPackageRecipe(rootPackageIdentifier)).thenReturn(rootPackageRecipe);
        when(packageStore.resolveAndSetupArtifactsUnpackDirectory(rootPackageIdentifier))
                .thenReturn(Paths.get("/dummyUnpackDir"));
        when(kernel.getMain()).thenReturn(mainService);
        when(kernel.getRootPath()).thenReturn(Paths.get("/dummyroot"));
        when(kernel.locate(TEST_INPUT_PACKAGE_A)).thenReturn(alreadyRunningService);
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies())
                .thenReturn(Collections.singletonMap(alreadyRunningService, DependencyType.HARD));
        when(alreadyRunningService.getName()).thenReturn(TEST_INPUT_PACKAGE_A);
        when(alreadyRunningService.getServiceConfig()).thenReturn(alreadyRunningServiceConfig);
        when(alreadyRunningServiceConfig.find(KernelConfigResolver.PARAMETERS_CONFIG_KEY, "PackageA_Param_1"))
                .thenReturn(alreadyRunningServiceParameterConfig);
        when(alreadyRunningServiceParameterConfig.getOnce()).thenReturn("PackageA_Param_1_value");
        when(alreadyRunningServiceConfig.find(KernelConfigResolver.PARAMETERS_CONFIG_KEY, "PackageA_Param_2"))
                .thenReturn(null);
        when(alreadyRunningService.isAutostart()).thenReturn(true);

        // WHEN
        KernelConfigResolver kernelConfigResolver = new KernelConfigResolver(packageStore, kernel);
        Map<Object, Object> resolvedConfig =
                kernelConfigResolver.resolve(packagesToDeploy, document, Arrays.asList(TEST_INPUT_PACKAGE_A));

        // THEN
        // service config
        Map<Object, Object> servicesConfig = (Map<Object, Object>) resolvedConfig.get(SERVICES_NAMESPACE_TOPIC);
        assertThat("Must contain main service", servicesConfig, hasKey("main"));
        assertThat("Must contain top level package service", servicesConfig, hasKey(TEST_INPUT_PACKAGE_A));

        // parameter interpolation
        Map<String, String> serviceInstallCommand =
                (Map<String, String>) getServiceInstallCommand(TEST_INPUT_PACKAGE_A, servicesConfig);

        assertThat("If parameter value was set in previous deployment but not in current deployment, previously "
                        + "used values should be used", serviceInstallCommand.get(LIFECYCLE_SCRIPT_KEY),
                equalTo("echo installing service in Package " + "PackageA with param PackageA_Param_1_value,"+
                        " kernel rootPath as /dummyroot and unpack dir as /dummyUnpackDir"));

        assertThat("If no parameter value was set in current/previous deployment, the default value should be used",
                getServiceRunCommand(TEST_INPUT_PACKAGE_A, servicesConfig),
                equalTo("echo running service in Package PackageA with param PackageA_Param_2_default_value"));
    }

    @Test
    void GIVEN_deployment_with_artifact_WHEN_config_resolution_requested_THEN_artifact_path_should_be_interpolated()
            throws Exception {
        // GIVEN
        PackageIdentifier rootPackageIdentifier =
                new PackageIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2", Semver.SemverType.NPM));
        List<PackageIdentifier> packagesToDeploy = Arrays.asList(rootPackageIdentifier);

        PackageRecipe rootPackageRecipe = new PackageRecipe(RecipeTemplateVersion.JAN_25_2020, TEST_INPUT_PACKAGE_A,
                rootPackageIdentifier.getVersion(), "", "", Collections.emptySet(), Collections.emptyList(),
                new HashMap<String, Object>() {{
                    put(LIFECYCLE_RUN_KEY, "java -jar {{artifacts:path}}/test.jar -x arg");
                }}, Collections.emptyMap(), Collections.emptyMap(), null);

        DeploymentPackageConfiguration rootPackageDeploymentConfig =
                new DeploymentPackageConfiguration(TEST_INPUT_PACKAGE_A, true, "1.2", Collections.emptyMap());
        DeploymentDocument document = DeploymentDocument.builder().rootPackages(Arrays.asList(TEST_INPUT_PACKAGE_A))
                .deploymentPackageConfigurationList(Arrays.asList(rootPackageDeploymentConfig)).build();

        when(packageStore.getPackageRecipe(rootPackageIdentifier)).thenReturn(rootPackageRecipe);
        when(packageStore.resolveArtifactDirectoryPath(rootPackageIdentifier))
                .thenReturn(Paths.get("/packages/artifacts"));
        when(kernel.getMain()).thenReturn(mainService);
        when(kernel.locate(any())).thenThrow(new ServiceLoadException("Service not found"));
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies()).thenReturn(Collections.emptyMap());

        // WHEN
        KernelConfigResolver kernelConfigResolver = new KernelConfigResolver(packageStore, kernel);
        Map<Object, Object> resolvedConfig =
                kernelConfigResolver.resolve(packagesToDeploy, document, Arrays.asList(TEST_INPUT_PACKAGE_A));

        // THEN
        Map<Object, Object> servicesConfig = (Map<Object, Object>) resolvedConfig.get(SERVICES_NAMESPACE_TOPIC);

        Path jarPath = Paths.get("/packages/artifacts").toAbsolutePath();
        assertThat("{{artifacts:path}} should be replace by the package's artifact path",
                getServiceRunCommand(TEST_INPUT_PACKAGE_A, servicesConfig),
                equalTo("java -jar " + jarPath + "/test.jar -x arg"));
    }

    // utilities for mocking input
    private PackageRecipe getPackage(String packageName, String packageVersion,
                                     Map<String, RecipeDependencyProperties> dependencies,
                                     Map<String, String> packageParamsWithDefaultsRaw, String crossComponentName)
            throws PackageLoadingException {

        Set<PackageParameter> parameters = packageParamsWithDefaultsRaw.entrySet().stream()
                .map(entry -> new PackageParameter(entry.getKey(), entry.getValue(), "STRING"))
                .collect(Collectors.toSet());

        Semver version = new Semver(packageVersion, Semver.SemverType.NPM);
        return new PackageRecipe(RecipeTemplateVersion.JAN_25_2020, packageName, version, "Test package", "Publisher",
                parameters, Collections.emptyList(), getSimplePackageLifecycle(packageName, crossComponentName),
                Collections.emptyMap(), dependencies, null);
    }

    private Map<String, String> getSimpleParameterMap(String packageName) {
        Map<String, String> simpleParameterMap = new HashMap<>();
        simpleParameterMap
                .put(String.format("%s_Param_1", packageName), String.format("%s_Param_1_default_value", packageName));
        simpleParameterMap
                .put(String.format("%s_Param_2", packageName), String.format("%s_Param_2_default_value", packageName));
        return simpleParameterMap;
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

    // utilities for verification
    private Object getServiceRunCommand(String serviceName, Map<Object, Object> config) {
        return getValueForLifecycleKey(LIFECYCLE_RUN_KEY, serviceName, config);
    }

    private Object getServiceInstallCommand(String serviceName, Map<Object, Object> config) {
        return getValueForLifecycleKey(LIFECYCLE_INSTALL_KEY, serviceName, config);
    }

    private boolean dependencyListContains(String serviceName, String dependencyName, Map<Object, Object> config) {
        Iterable<String> dependencyList =
                (Iterable<String>) getServiceConfig(serviceName, config).get(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC);
        return StreamSupport.stream(dependencyList.spliterator(), false).anyMatch(itr -> itr.equals(dependencyName));
    }

    private Object getValueForLifecycleKey(String key, String serviceName, Map<Object, Object> config) {
        Map<Object, Object> serviceConfig = getServiceConfig(serviceName, config);
        return ((Map<Object, Object>) serviceConfig.get(EvergreenService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC)).get(key);
    }

    private Map<Object, Object> getServiceConfig(String serviceName, Map<Object, Object> config) {
        return (Map<Object, Object>) config.get(serviceName);
    }
}
