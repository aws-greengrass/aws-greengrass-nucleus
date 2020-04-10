/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.models.PackageParameter;
import com.aws.iot.evergreen.packagemanager.models.RecipeTemplateVersion;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsMapContaining.hasKey;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class KernelConfigResolverTest {
    private static final String LIFECYCLE_CONFIG_ROOT_KEY = "lifecycle";
    private static final String LIFECYCLE_INSTALL_KEY = "install";
    private static final String LIFECYCLE_RUN_KEY = "run";
    private static final String LIFECYCLE_SCRIPT_KEY = "script";
    private static final String KERNEL_CONFIG_SERVICE_DEPENDENCIES_KEY = "dependencies";
    private static final String LIFECYCLE_MOCK_INSTALL_COMMAND_FORMAT =
            "echo installing service in Package %s with param {{params:%s_Param_1.value}}";
    private static final String LIFECYCLE_MOCK_RUN_COMMAND_FORMAT =
            "echo running service in Package %s with param {{params:%s_Param_2.value}}";
    private static final String TEST_INPUT_PACKAGE_A = "PackageA";
    private static final String TEST_INPUT_PACKAGE_B = "PackageB";
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

    @Test
    public void GIVEN_deployment_for_package_WHEN_config_resolution_requested_THEN_add_service_and_dependency_service()
            throws Exception {
        // GIVEN
        PackageIdentifier rootPackageIdentifier =
                new PackageIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2", Semver.SemverType.NPM));
        PackageIdentifier dependencyPackageIdentifier =
                new PackageIdentifier(TEST_INPUT_PACKAGE_B, new Semver("2.3", Semver.SemverType.NPM));
        List<PackageIdentifier> packagesToDeploy = Arrays.asList(rootPackageIdentifier, dependencyPackageIdentifier);

        Package rootPackage =
                getPackage(TEST_INPUT_PACKAGE_A, "1.2", Collections.singletonMap(TEST_INPUT_PACKAGE_B, "2.3"),
                        Collections.emptyMap());
        Package dependencyPackage =
                getPackage(TEST_INPUT_PACKAGE_B, "2.3", Collections.emptyMap(), Collections.emptyMap());

        DeploymentPackageConfiguration rootPackageDeploymentConfig =
                new DeploymentPackageConfiguration(TEST_INPUT_PACKAGE_A, "1.2", ">1.0", Collections.emptySet(),
                        Arrays.asList(dependencyPackageIdentifier));
        DeploymentPackageConfiguration dependencyPackageDeploymentConfig =
                new DeploymentPackageConfiguration(TEST_INPUT_PACKAGE_B, "2.3", ">2.0", Collections.emptySet(),
                        Collections.emptyList());
        DeploymentDocument document = DeploymentDocument.builder().rootPackages(Arrays.asList(TEST_INPUT_PACKAGE_A))
                .deploymentPackageConfigurationList(
                        Arrays.asList(rootPackageDeploymentConfig, dependencyPackageDeploymentConfig)).build();

        when(packageStore.getPackageRecipe(rootPackageIdentifier)).thenReturn(rootPackage);
        when(packageStore.getPackageRecipe(dependencyPackageIdentifier)).thenReturn(dependencyPackage);
        when(kernel.getMain()).thenReturn(mainService);
        when(kernel.locate(any())).thenThrow(new ServiceLoadException("Service not found"));
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies()).thenReturn(Collections.singletonMap(alreadyRunningService, State.RUNNING));
        when(alreadyRunningService.getName()).thenReturn("IpcService");

        // WHEN
        KernelConfigResolver kernelConfigResolver = new KernelConfigResolver(packageStore, kernel);
        Map<Object, Object> resolvedConfig =
                kernelConfigResolver.resolve(packagesToDeploy, document, Arrays.asList(TEST_INPUT_PACKAGE_A));

        // THEN
        // service config
        Map<Object, Object> servicesConfig = (Map<Object, Object>) resolvedConfig.get("services");
        assertThat("Must contain main service", servicesConfig, hasKey("main"));
        assertThat("Must contain top level package service", servicesConfig, hasKey(TEST_INPUT_PACKAGE_A));
        assertThat("Must contain dependency service", servicesConfig, hasKey(TEST_INPUT_PACKAGE_B));

        // dependencies
        assertThat("Main service must depend on new service",
                dependencyListContains("main", TEST_INPUT_PACKAGE_A, servicesConfig));
        assertThat("Main service must depend on existing service",
                dependencyListContains("main", "IpcService", servicesConfig));
        assertThat("New service must depend on dependency service",
                dependencyListContains(TEST_INPUT_PACKAGE_A, TEST_INPUT_PACKAGE_B, servicesConfig));

    }

    @Test
    public void GIVEN_deployment_for_existing_package_WHEN_config_resolution_requested_THEN_update_service()
            throws Exception {
        // GIVEN
        PackageIdentifier rootPackageIdentifier =
                new PackageIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2", Semver.SemverType.NPM));
        List<PackageIdentifier> packagesToDeploy = Arrays.asList(rootPackageIdentifier);

        Package rootPackage = getPackage(TEST_INPUT_PACKAGE_A, "1.2", Collections.emptyMap(), Collections.emptyMap());

        DeploymentPackageConfiguration rootPackageDeploymentConfig =
                new DeploymentPackageConfiguration(TEST_INPUT_PACKAGE_A, "1.2", ">1.0", Collections.emptySet(),
                        Collections.emptyList());
        DeploymentDocument document = DeploymentDocument.builder().rootPackages(Arrays.asList(TEST_INPUT_PACKAGE_A))
                .deploymentPackageConfigurationList(Arrays.asList(rootPackageDeploymentConfig)).build();

        when(packageStore.getPackageRecipe(rootPackageIdentifier)).thenReturn(rootPackage);
        when(kernel.getMain()).thenReturn(mainService);
        when(kernel.locate(TEST_INPUT_PACKAGE_A)).thenReturn(alreadyRunningService);
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies()).thenReturn(Collections.singletonMap(alreadyRunningService, State.RUNNING));
        when(alreadyRunningService.getName()).thenReturn(TEST_INPUT_PACKAGE_A);

        // WHEN
        KernelConfigResolver kernelConfigResolver = new KernelConfigResolver(packageStore, kernel);
        Map<Object, Object> resolvedConfig =
                kernelConfigResolver.resolve(packagesToDeploy, document, Arrays.asList(TEST_INPUT_PACKAGE_A));

        // THEN
        // service config
        Map<Object, Object> servicesConfig = (Map<Object, Object>) resolvedConfig.get("services");
        assertThat("Must contain main service", servicesConfig, hasKey("main"));
        assertThat("Must contain updated service", servicesConfig, hasKey(TEST_INPUT_PACKAGE_A));

        // dependencies
        assertThat("Main service must depend on updated service",
                dependencyListContains("main", TEST_INPUT_PACKAGE_A, servicesConfig));
    }

    @Test
    public void GIVEN_deployment_with_parameters_set_WHEN_config_resolution_requested_THEN_parameters_should_be_interpolated()
            throws Exception {
        // GIVEN
        PackageIdentifier rootPackageIdentifier =
                new PackageIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2", Semver.SemverType.NPM));
        List<PackageIdentifier> packagesToDeploy = Arrays.asList(rootPackageIdentifier);

        Package rootPackage = getPackage(TEST_INPUT_PACKAGE_A, "1.2", Collections.emptyMap(),
                getSimpleParameterMap(TEST_INPUT_PACKAGE_A));

        DeploymentPackageConfiguration rootPackageDeploymentConfig =
                new DeploymentPackageConfiguration(TEST_INPUT_PACKAGE_A, "1.2", ">1.0", new HashSet<>(
                        Arrays.asList(new PackageParameter("PackageA_Param_1", "PackageA_Param_1_value", "STRING"))),
                        Collections.emptyList());
        DeploymentDocument document = DeploymentDocument.builder().rootPackages(Arrays.asList(TEST_INPUT_PACKAGE_A))
                .deploymentPackageConfigurationList(Arrays.asList(rootPackageDeploymentConfig)).build();

        when(packageStore.getPackageRecipe(rootPackageIdentifier)).thenReturn(rootPackage);
        when(kernel.getMain()).thenReturn(mainService);
        when(kernel.locate(any())).thenThrow(new ServiceLoadException("Service not found"));
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies()).thenReturn(Collections.emptyMap());

        // WHEN
        KernelConfigResolver kernelConfigResolver = new KernelConfigResolver(packageStore, kernel);
        Map<Object, Object> resolvedConfig =
                kernelConfigResolver.resolve(packagesToDeploy, document, Arrays.asList(TEST_INPUT_PACKAGE_A));

        // THEN
        // service config
        Map<Object, Object> servicesConfig = (Map<Object, Object>) resolvedConfig.get("services");
        assertThat("Must contain main service", servicesConfig, hasKey("main"));
        assertThat("Must contain top level package service", servicesConfig, hasKey(TEST_INPUT_PACKAGE_A));

        // parameter interpolation
        Map<String, String> serviceInstallCommand =
                (Map<String, String>) getServiceInstallCommand(TEST_INPUT_PACKAGE_A, servicesConfig);

        assertThat("If parameter value was set in deployment, it should be used",
                serviceInstallCommand.get(LIFECYCLE_SCRIPT_KEY),
                equalTo("echo installing service in Package PackageA " + "with param PackageA_Param_1_value"));

        assertThat("If no parameter value was set in deployment, the default value should be used",
                getServiceRunCommand(TEST_INPUT_PACKAGE_A, servicesConfig),
                equalTo("echo running service in Package " + "PackageA with param PackageA_Param_2_default_value"));

    }

    @Test
    public void GIVEN_deployment_with_params_not_set_WHEN_previous_deployment_had_params_THEN_use_params_from_previous_deployment()
            throws Exception {
        // GIVEN
        PackageIdentifier rootPackageIdentifier =
                new PackageIdentifier(TEST_INPUT_PACKAGE_A, new Semver("1.2", Semver.SemverType.NPM));
        List<PackageIdentifier> packagesToDeploy = Arrays.asList(rootPackageIdentifier);

        Package rootPackage = getPackage(TEST_INPUT_PACKAGE_A, "1.2", Collections.emptyMap(),
                getSimpleParameterMap(TEST_INPUT_PACKAGE_A));

        DeploymentPackageConfiguration rootPackageDeploymentConfig =
                new DeploymentPackageConfiguration(TEST_INPUT_PACKAGE_A, "1.2", ">1.0", Collections.emptySet(),
                        Collections.emptyList());
        DeploymentDocument document = DeploymentDocument.builder().rootPackages(Arrays.asList(TEST_INPUT_PACKAGE_A))
                .deploymentPackageConfigurationList(Arrays.asList(rootPackageDeploymentConfig)).build();

        when(packageStore.getPackageRecipe(rootPackageIdentifier)).thenReturn(rootPackage);
        when(kernel.getMain()).thenReturn(mainService);
        when(kernel.locate(TEST_INPUT_PACKAGE_A)).thenReturn(alreadyRunningService);
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies()).thenReturn(Collections.singletonMap(alreadyRunningService, State.RUNNING));
        when(alreadyRunningService.getName()).thenReturn(TEST_INPUT_PACKAGE_A);
        when(alreadyRunningService.getServiceConfig()).thenReturn(alreadyRunningServiceConfig);
        when(alreadyRunningServiceConfig.find(KernelConfigResolver.PARAMETERS_CONFIG_KEY, "PackageA_Param_1"))
                .thenReturn(alreadyRunningServiceParameterConfig);
        when(alreadyRunningServiceParameterConfig.getOnce()).thenReturn("PackageA_Param_1_value");
        when(alreadyRunningServiceConfig.find(KernelConfigResolver.PARAMETERS_CONFIG_KEY, "PackageA_Param_2"))
                .thenReturn(null);

        // WHEN
        KernelConfigResolver kernelConfigResolver = new KernelConfigResolver(packageStore, kernel);
        Map<Object, Object> resolvedConfig =
                kernelConfigResolver.resolve(packagesToDeploy, document, Arrays.asList(TEST_INPUT_PACKAGE_A));

        // THEN
        // service config
        Map<Object, Object> servicesConfig = (Map<Object, Object>) resolvedConfig.get("services");
        assertThat("Must contain main service", servicesConfig, hasKey("main"));
        assertThat("Must contain top level package service", servicesConfig, hasKey(TEST_INPUT_PACKAGE_A));

        // parameter interpolation
        Map<String, String> serviceInstallCommand =
                (Map<String, String>) getServiceInstallCommand(TEST_INPUT_PACKAGE_A, servicesConfig);

        assertThat("If parameter value was set in previous deployment but not in current deployment, previously "
                        + "used values should be used", serviceInstallCommand.get(LIFECYCLE_SCRIPT_KEY),
                equalTo("echo installing service in Package " + "PackageA with param PackageA_Param_1_value"));

        assertThat("If no parameter value was set in current/previous deployment, the default value should be used",
                getServiceRunCommand(TEST_INPUT_PACKAGE_A, servicesConfig),
                equalTo("echo running service in Package PackageA with param PackageA_Param_2_default_value"));

    }

    // utilities for mocking input
    private Package getPackage(String packageName, String packageVersion, Map<String, String> dependencies,
                               Map<String, String> packageParamsWithDefaultsRaw) {

        Set<PackageParameter> parameters = packageParamsWithDefaultsRaw.entrySet().stream()
                .map(entry -> new PackageParameter(entry.getKey(), entry.getValue(), "STRING"))
                .collect(Collectors.toSet());

        Semver version = new Semver(packageVersion, Semver.SemverType.NPM);
        return new Package(RecipeTemplateVersion.JAN_25_2020, packageName, version, "Test package", "Publisher",
                parameters, getSimplePackageLifecycle(packageName), Collections.emptyList(), dependencies,
                Collections.emptyList());
    }

    private Map<String, String> getSimpleParameterMap(String packageName) {
        Map<String, String> simpleParameterMap = new HashMap<>();
        simpleParameterMap
                .put(String.format("%s_Param_1", packageName), String.format("%s_Param_1_default_value", packageName));
        simpleParameterMap
                .put(String.format("%s_Param_2", packageName), String.format("%s_Param_2_default_value", packageName));
        return simpleParameterMap;
    }

    private Map<String, Object> getSimplePackageLifecycle(String packageName) {
        Map<String, Object> lifecycle = new HashMap<>();
        Map<String, Object> installCommands = new HashMap<>();
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
                (Iterable<String>) getLifecycleConfig(serviceName, config).get(KERNEL_CONFIG_SERVICE_DEPENDENCIES_KEY);
        return StreamSupport.stream(dependencyList.spliterator(), false).anyMatch(itr -> itr.equals(dependencyName));
    }

    private Object getValueForLifecycleKey(String key, String serviceName, Map<Object, Object> config) {
        Map<Object, Object> map = getLifecycleConfig(serviceName, config);
        return ((Map<Object, Object>) map.get(LIFECYCLE_CONFIG_ROOT_KEY)).get(key);
    }

    private Map<Object, Object> getLifecycleConfig(String serviceName, Map<Object, Object> config) {
        return (Map<Object, Object>) config.get(serviceName);
    }
}
