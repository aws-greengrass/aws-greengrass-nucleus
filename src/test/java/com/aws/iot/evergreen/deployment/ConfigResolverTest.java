/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.models.Package;
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ConfigResolverTest {
    @Mock
    private Kernel kernel;

    @Mock
    private EvergreenService mainService;

    @Mock
    private EvergreenService alreadyRunningService;

    private static final String LIFECYCLE_INSTALL_KEY = "install";
    private static final String LIFECYCLE_RUN_KEY = "run";
    private static final String LIFECYCLE_DEPENDENCIES_KEY = "dependencies";
    private static final String LIFECYCLE_MOCK_INSTALL_COMMAND_FORMAT =
            "echo installing service in Package %s with param {{params:%s_Param_1.value}}";
    private static final String LIFECYCLE_MOCK_RUN_COMMAND_FORMAT =
            "echo running service in Package %s with param {{params:%s_Param_2.value}}";

    private static final String TEST_INPUT_PACKAGE_A = "PackageA";
    private static final String TEST_INPUT_PACKAGE_B = "PackageB";

    @Test
    public void GIVEN_deployment_for_package_WHEN_config_resolution_requested_THEN_add_service_and_dependency_service() {
        // GIVEN
        Package topLevelPackage =
                getPackage(TEST_INPUT_PACKAGE_A, "1.2", Collections.singletonMap(TEST_INPUT_PACKAGE_B, "2.3"),
                        Collections.emptyMap());
        Package dependencyPackage =
                getPackage(TEST_INPUT_PACKAGE_B, "2.3", Collections.emptyMap(), Collections.emptyMap());
        topLevelPackage.getDependencyPackages().add(dependencyPackage);
        Set<Package> packagesToDeploy = new HashSet<>(Arrays.asList(topLevelPackage));

        Set<String> removedTopLevelPackages = Collections.emptySet();

        ConfigResolver configResolver = new ConfigResolver(kernel, packagesToDeploy, removedTopLevelPackages);

        when(kernel.getMain()).thenReturn(mainService);
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies()).thenReturn(Collections.singletonMap(alreadyRunningService, State.RUNNING));
        when(alreadyRunningService.getName()).thenReturn("IpcService");

        // WHEN
        Map<Object, Object> resolvedConfig = configResolver.resolveConfig();

        // THEN
        assertThat("Must contain services keyword", resolvedConfig.containsKey(EvergreenService.SERVICES_NAMESPACE_TOPIC));
        resolvedConfig = (Map<Object, Object>) resolvedConfig.get(EvergreenService.SERVICES_NAMESPACE_TOPIC);
        // service config
        assertThat("Must contain main service", resolvedConfig.containsKey("main"));
        assertThat("Must contain top level package service", resolvedConfig.containsKey(TEST_INPUT_PACKAGE_A));
        assertThat("Must contain dependency service", resolvedConfig.containsKey(TEST_INPUT_PACKAGE_B));

        // dependencies
        assertThat("Main service must depend on new service",
                getServiceDependencies("main", resolvedConfig).contains(TEST_INPUT_PACKAGE_A));
        assertThat("Main service must depend on existing service",
                getServiceDependencies("main", resolvedConfig).contains("IpcService"));
        assertThat("New service must depend on dependency service",
                getServiceDependencies(TEST_INPUT_PACKAGE_A, resolvedConfig).contains(TEST_INPUT_PACKAGE_B));

    }

    @Test
    public void GIVEN_deployment_for_existing_package_WHEN_config_resolution_requested_THEN_update_service() {
        // GIVEN
        Package topLevelPackage =
                getPackage(TEST_INPUT_PACKAGE_A, "1.2", Collections.emptyMap(), Collections.emptyMap());
        Set<Package> packagesToDeploy = new HashSet<>(Arrays.asList(topLevelPackage));

        Set<String> removedTopLevelPackages = Collections.emptySet();

        ConfigResolver configResolver = new ConfigResolver(kernel, packagesToDeploy, removedTopLevelPackages);

        when(kernel.getMain()).thenReturn(mainService);
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies()).thenReturn(Collections.singletonMap(alreadyRunningService, State.RUNNING));
        when(alreadyRunningService.getName()).thenReturn(TEST_INPUT_PACKAGE_A);

        // WHEN
        Map<Object, Object> resolvedConfig = configResolver.resolveConfig();

        // THEN
        assertThat("Must contain services keyword", resolvedConfig.containsKey(EvergreenService.SERVICES_NAMESPACE_TOPIC));
        resolvedConfig = (Map<Object, Object>) resolvedConfig.get(EvergreenService.SERVICES_NAMESPACE_TOPIC);
        // service config
        assertThat("Must contain main service", resolvedConfig.containsKey("main"));
        assertThat("Must contain updated service", resolvedConfig.containsKey(TEST_INPUT_PACKAGE_A));

        // dependencies
        assertThat("Main service must depend on updated service",
                getServiceDependencies("main", resolvedConfig).contains(TEST_INPUT_PACKAGE_A));
    }

    @Test
    public void GIVEN_deployment_removes_root_package_WHEN_config_resolution_requested_THEN_remove_service() {
        // GIVEN
        Package topLevelPackage =
                getPackage(TEST_INPUT_PACKAGE_A, "1.2", Collections.emptyMap(), Collections.emptyMap());
        Set<Package> packagesToDeploy = new HashSet<>(Arrays.asList(topLevelPackage));

        Set<String> removedTopLevelPackages = new HashSet<>(Arrays.asList("RemovedService"));

        ConfigResolver configResolver = new ConfigResolver(kernel, packagesToDeploy, removedTopLevelPackages);

        when(kernel.getMain()).thenReturn(mainService);
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies()).thenReturn(Collections.singletonMap(alreadyRunningService, State.RUNNING));
        when(alreadyRunningService.getName()).thenReturn("RemovedService");

        // WHEN
        Map<Object, Object> resolvedConfig = configResolver.resolveConfig();

        // THEN
        assertThat("Must contain services keyword", resolvedConfig.containsKey(EvergreenService.SERVICES_NAMESPACE_TOPIC));
        resolvedConfig = (Map<Object, Object>) resolvedConfig.get(EvergreenService.SERVICES_NAMESPACE_TOPIC);
        // service config
        assertThat("Must contain main service", resolvedConfig.containsKey("main"));
        assertThat("Must contain top level package service", resolvedConfig.containsKey(TEST_INPUT_PACKAGE_A));

        // dependencies
        assertThat("Main service must depend on updated service",
                getServiceDependencies("main", resolvedConfig).contains(TEST_INPUT_PACKAGE_A));
        assertThat("Main service must not depend on removed service",
                !getServiceDependencies("main", resolvedConfig).contains("RemovedService"));

    }

    @Test
    public void GIVEN_deployment_with_parameters_set_WHEN_config_resolution_requested_THEN_parameters_should_be_interpolated() {
        // GIVEN
        Package topLevelPackage = getPackage(TEST_INPUT_PACKAGE_A, "1.2", Collections.emptyMap(),
                getSimpleParameterMap(TEST_INPUT_PACKAGE_A));
        Set<Package> packagesToDeploy = new HashSet<>(Arrays.asList(topLevelPackage));

        Set<String> removedTopLevelPackages = Collections.emptySet();

        ConfigResolver configResolver = new ConfigResolver(kernel, packagesToDeploy, removedTopLevelPackages);

        when(kernel.getMain()).thenReturn(mainService);
        when(mainService.getName()).thenReturn("main");
        when(mainService.getDependencies()).thenReturn(Collections.emptyMap());

        // WHEN
        Map<Object, Object> resolvedConfig = configResolver.resolveConfig();

        // THEN
        assertThat("Must contain services keyword", resolvedConfig.containsKey(EvergreenService.SERVICES_NAMESPACE_TOPIC));
        resolvedConfig = (Map<Object, Object>) resolvedConfig.get(EvergreenService.SERVICES_NAMESPACE_TOPIC);
        // service config
        assertThat("Must contain main service", resolvedConfig.containsKey("main"));
        assertThat("Must contain top level package service", resolvedConfig.containsKey(TEST_INPUT_PACKAGE_A));

        // parameter interpolation
        assertThat("If parameter value was set in deployment, it should be used",
                getServiceInstallCommand(TEST_INPUT_PACKAGE_A, resolvedConfig)
                        .equals("echo installing service in Package PackageA with param PackageA_Param_1_value"));
        assertThat("If not parameter value was set in deployment, the default value should be used",
                getServiceRunCommand(TEST_INPUT_PACKAGE_A, resolvedConfig)
                        .equals("echo running service in Package PackageA with param PackageA_Param_2_value"));

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
                .put(String.format("%s_Param_1", packageName), String.format("%s_Param_1_value", packageName));
        simpleParameterMap
                .put(String.format("%s_Param_2", packageName), String.format("%s_Param_2_value", packageName));
        return simpleParameterMap;
    }

    private Map<String, Object> getSimplePackageLifecycle(String packageName) {
        Map<String, Object> lifecycle = new HashMap<>();
        lifecycle.put(LIFECYCLE_INSTALL_KEY,
                String.format(LIFECYCLE_MOCK_INSTALL_COMMAND_FORMAT, packageName, packageName));
        lifecycle.put(LIFECYCLE_RUN_KEY, String.format(LIFECYCLE_MOCK_RUN_COMMAND_FORMAT, packageName, packageName));
        return lifecycle;
    }

    // utilities for verification
    private String getServiceRunCommand(String serviceName, Map<Object, Object> config) {
        return getValueForLifecycleKey(LIFECYCLE_RUN_KEY, serviceName, config);
    }

    private String getServiceInstallCommand(String serviceName, Map<Object, Object> config) {
        return getValueForLifecycleKey(LIFECYCLE_INSTALL_KEY, serviceName, config);
    }

    private Set<String> getServiceDependencies(String serviceName, Map<Object, Object> config) {
        return new HashSet<>((List<String>)getLifecycleConfig(serviceName, config).get(LIFECYCLE_DEPENDENCIES_KEY));
    }

    private String getValueForLifecycleKey(String key, String serviceName, Map<Object, Object> config) {
        return (String) getLifecycleConfig(serviceName, config).get(key);
    }

    private Map<Object, Object> getLifecycleConfig(String serviceName, Map<Object, Object> config) {
        return (Map<Object, Object>) config.get(serviceName);
    }
}

