/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.amazon.aws.iot.greengrass.component.common.ComponentType;
import com.aws.greengrass.config.CaseInsensitiveString;
import com.aws.greengrass.config.ChildChanged;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.Validator;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.exceptions.ComponentConfigurationValidationException;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.deployment.model.S3EndpointType;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.KernelCommandLine;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.config.LogFormat;
import com.aws.greengrass.logging.impl.config.LogStore;
import com.aws.greengrass.logging.impl.config.model.LogConfigUpdate;
import com.aws.greengrass.security.SecurityService;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.LockFactory;
import com.aws.greengrass.util.LockScope;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.RootCAUtils;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.exceptions.TLSAuthException;
import com.aws.greengrass.util.platforms.Platform;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Setter;
import org.slf4j.event.Level;
import software.amazon.awssdk.core.SdkSystemSetting;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import javax.inject.Inject;
import javax.net.ssl.KeyManager;

import static com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SETENV_CONFIG_NAMESPACE;
import static com.aws.greengrass.lifecyclemanager.Kernel.SERVICE_TYPE_TOPIC_KEY;
import static com.aws.greengrass.lifecyclemanager.KernelAlternatives.locateCurrentKernelUnpackDir;
import static com.aws.greengrass.lifecyclemanager.KernelCommandLine.MAIN_SERVICE_NAME;

/**
 * Class for providing device configuration information.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.ExcessivePublicCount"})
@SuppressFBWarnings("IS2_INCONSISTENT_SYNC")
public class DeviceConfiguration {
    public static final String DEFAULT_NUCLEUS_COMPONENT_NAME = "aws.greengrass.Nucleus";

    public static final String DEVICE_PARAM_THING_NAME = "thingName";
    public static final String DEVICE_PARAM_JVM_OPTIONS = "jvmOptions";
    public static final String JVM_OPTION_ROOT_PATH = "-Droot=";
    public static final String DEVICE_PARAM_GG_DATA_ENDPOINT = "greengrassDataPlaneEndpoint";
    public static final String DEVICE_PARAM_IOT_DATA_ENDPOINT = "iotDataEndpoint";
    public static final String DEVICE_PARAM_IOT_CRED_ENDPOINT = "iotCredEndpoint";
    public static final String DEVICE_PARAM_PRIVATE_KEY_PATH = "privateKeyPath";
    public static final String DEVICE_PARAM_CERTIFICATE_FILE_PATH = "certificateFilePath";
    public static final String DEVICE_PARAM_ROOT_CA_PATH = "rootCaPath";
    public static final String DEVICE_PARAM_INTERPOLATE_COMPONENT_CONFIGURATION = "interpolateComponentConfiguration";
    public static final String DEVICE_PARAM_IPC_SOCKET_PATH = "ipcSocketPath";
    public static final String SYSTEM_NAMESPACE_KEY = "system";
    public static final String PLATFORM_OVERRIDE_TOPIC = "platformOverride";
    public static final String DEVICE_PARAM_AWS_REGION = "awsRegion";
    public static final String DEVICE_PARAM_FIPS_MODE = "fipsMode";
    public static final String DEVICE_MQTT_NAMESPACE = "mqtt";
    public static final String DEVICE_SPOOLER_NAMESPACE = "spooler";
    public static final String RUN_WITH_TOPIC = "runWithDefault";
    public static final String RUN_WITH_DEFAULT_POSIX_USER = "posixUser";
    public static final String RUN_WITH_DEFAULT_WINDOWS_USER = "windowsUser";
    public static final String RUN_WITH_DEFAULT_POSIX_SHELL = "posixShell";
    public static final String RUN_WITH_DEFAULT_POSIX_SHELL_VALUE = "sh";
    public static final String FLEET_STATUS_CONFIG_TOPICS = "fleetStatus";

    public static final String IOT_ROLE_ALIAS_TOPIC = "iotRoleAlias";
    public static final String COMPONENT_STORE_MAX_SIZE_BYTES = "componentStoreMaxSizeBytes";
    public static final String DEPLOYMENT_POLLING_FREQUENCY_SECONDS = "deploymentPollingFrequencySeconds";
    public static final String NUCLEUS_CONFIG_LOGGING_TOPICS = "logging";
    public static final String TELEMETRY_CONFIG_LOGGING_TOPICS = "telemetry";

    public static final String S3_ENDPOINT_TYPE = "s3EndpointType";
    public static final String S3_ENDPOINT_PROP_NAME = SdkSystemSetting.AWS_S3_US_EAST_1_REGIONAL_ENDPOINT.property();
    public static final String DEVICE_NETWORK_PROXY_NAMESPACE = "networkProxy";
    public static final String DEVICE_PROXY_NAMESPACE = "proxy";
    public static final String DEVICE_PARAM_NO_PROXY_ADDRESSES = "noProxyAddresses";
    public static final String DEVICE_PARAM_PROXY_URL = "url";
    public static final String DEVICE_PARAM_PROXY_USERNAME = "username";
    public static final String DEVICE_PARAM_PROXY_PASSWORD = "password";
    public static final long COMPONENT_STORE_MAX_SIZE_DEFAULT_BYTES = 10_000_000_000L;
    public static final long DEPLOYMENT_POLLING_FREQUENCY_DEFAULT_SECONDS = 15L;
    public static final String DEVICE_PARAM_GG_DATA_PLANE_PORT = "greengrassDataPlanePort";
    private static final int GG_DATA_PLANE_PORT_DEFAULT = 8443;

    private static final String DEVICE_PARAM_ENV_STAGE = "envStage";
    private static final String DEFAULT_ENV_STAGE = "prod";
    private static final String CANNOT_BE_EMPTY = " cannot be empty";
    private static final Logger logger = LogManager.getLogger(DeviceConfiguration.class);
    public static final String AWS_IOT_THING_NAME_ENV = "AWS_IOT_THING_NAME";
    public static final String NUCLEUS_BUILD_METADATA_DIRECTORY = "conf";
    public static final String NUCLEUS_RECIPE_FILENAME = "recipe.yaml";
    public static final String FALLBACK_DEFAULT_REGION = "us-east-1";
    public static final String AMAZON_DOMAIN_SEQUENCE = ".amazonaws.";
    public static final String FALLBACK_VERSION = "0.0.0";
    private final Configuration config;
    private final KernelCommandLine kernelCommandLine;
    private final Validator deTildeValidator;
    private final Validator regionValidator;
    private final AtomicBoolean rootCA3Downloaded = new AtomicBoolean(false);
    private final AtomicReference<Boolean> deviceConfigValidateCachedResult = new AtomicReference<>();

    private Topics loggingTopics;
    private LogConfigUpdate currentConfiguration;
    private String nucleusComponentNameCache;
    private final Lock lock = LockFactory.newReentrantLock(this);

    // Needed for getDeviceIdentityKeyManagers due to shadow manager plugin dependency
    @Setter
    private SecurityService securityService;

    /**
     * Constructor used to read device configuration from the config store.
     *
     * @param config Device configuration
     * @param kernelCommandLine deTilde
     */
    @Inject
    public DeviceConfiguration(Configuration config, KernelCommandLine kernelCommandLine) {
        this.config = config;
        this.kernelCommandLine = kernelCommandLine;
        deTildeValidator = getDeTildeValidator();
        regionValidator = getRegionValidator();
        handleLoggingConfig();
        getComponentStoreMaxSizeBytes().dflt(COMPONENT_STORE_MAX_SIZE_DEFAULT_BYTES);
        getDeploymentPollingFrequencySeconds().dflt(DEPLOYMENT_POLLING_FREQUENCY_DEFAULT_SECONDS);
        handleExistingSystemProperty();
        // reset the cache when device configuration changes
        onAnyChange((what, node) -> deviceConfigValidateCachedResult.set(null));
    }

    /**
     * Constructor to use when setting the device configuration to kernel config.
     *
     * @param config              Device configuration
     * @param kernelCommandLine   deTilde
     * @param thingName           IoT thing name
     * @param iotDataEndpoint     IoT data endpoint
     * @param iotCredEndpoint     IoT cert endpoint
     * @param privateKeyPath      private key location on device
     * @param certificateFilePath certificate location on device
     * @param rootCaFilePath      downloaded RootCA location on device
     * @param awsRegion           aws region for the device
     * @param tesRoleAliasName    aws region for the device
     * @throws DeviceConfigurationException when the configuration parameters are not valid
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public DeviceConfiguration(Configuration config, KernelCommandLine kernelCommandLine, String thingName,
                               String iotDataEndpoint, String iotCredEndpoint, String privateKeyPath,
                               String certificateFilePath, String rootCaFilePath, String awsRegion,
                               String tesRoleAliasName) throws DeviceConfigurationException {
        this(config, kernelCommandLine);
        getThingName().withValue(thingName);
        getIotDataEndpoint().withValue(iotDataEndpoint);
        getIotCredentialEndpoint().withValue(iotCredEndpoint);
        getPrivateKeyFilePath().withValue(privateKeyPath);
        getCertificateFilePath().withValue(certificateFilePath);
        getRootCAFilePath().withValue(rootCaFilePath);
        getAWSRegion().withValue(awsRegion);
        getIotRoleAlias().withValue(tesRoleAliasName);
        validate();
    }

    /**
     * Get the Nucleus component's name.
     *
     * @return Nucleus component name
     */
    public String getNucleusComponentName() {
        try (LockScope ls = LockScope.lock(lock)) {
            // Check to see if the nucleus is still present in the config. If it isn't present, then
            // recalculate the component's name
            if (nucleusComponentNameCache == null
                    || config.findTopics(SERVICES_NAMESPACE_TOPIC, nucleusComponentNameCache) == null) {
                nucleusComponentNameCache = initNucleusComponentName();
            }
            return nucleusComponentNameCache;
        }
    }

    /**
     * Get the logging configuration.
     *
     * @return Configuration for logger.
     */
    public Topics getLoggingConfigurationTopics() {
        return getTopics(NUCLEUS_CONFIG_LOGGING_TOPICS);
    }

    /**
     * Get the telemetry configuration.
     *
     * @return Configuration for telemetry agent.
     */
    public Topics getTelemetryConfigurationTopics() {
        return getTopics(TELEMETRY_CONFIG_LOGGING_TOPICS);
    }

    /**
     * Get the fleet status configuration.
     *
     * @return Configuration for fleet status service.
     */
    public Topics getStatusConfigurationTopics() {
        return getTopics(FLEET_STATUS_CONFIG_TOPICS);
    }

    /**
     * Get the Nucleus component name to lookup the configuration in the right place. If no component of type Nucleus
     * exists, create service config for the default Nucleus component.
     */
    private String initNucleusComponentName() {
        Optional<CaseInsensitiveString> nucleusComponent =
                config.lookupTopics(SERVICES_NAMESPACE_TOPIC).children.keySet().stream()
                        .filter(s -> ComponentType.NUCLEUS.name().equals(getComponentType(s.toString())))
                        .findAny();
        String nucleusComponentName = nucleusComponent.isPresent() ? nucleusComponent.get().toString() :
                DEFAULT_NUCLEUS_COMPONENT_NAME;
        // Initialize default/inferred required config if it doesn't exist
        initializeNucleusComponentConfig(nucleusComponentName);
        return nucleusComponentName;
    }

    private void initializeNucleusComponentConfig(String nucleusComponentName) {
        config.lookup(SERVICES_NAMESPACE_TOPIC, nucleusComponentName, SERVICE_TYPE_TOPIC_KEY)
                .dflt(ComponentType.NUCLEUS.name());

        ArrayList<String> mainDependencies = (ArrayList) config.getRoot()
                .findOrDefault(new ArrayList<>(), SERVICES_NAMESPACE_TOPIC, MAIN_SERVICE_NAME,
                        SERVICE_DEPENDENCIES_NAMESPACE_TOPIC);
        mainDependencies.add(nucleusComponentName);
        config.lookup(SERVICES_NAMESPACE_TOPIC, MAIN_SERVICE_NAME, SERVICE_DEPENDENCIES_NAMESPACE_TOPIC)
                .dflt(mainDependencies);
    }

    /**
     * Handles subscribing and reconfiguring logger based on the correct topic.
     */
    private void handleLoggingConfig() {
        loggingTopics = getLoggingConfigurationTopics();
        loggingTopics.subscribe(this::handleLoggingConfigurationChanges);
    }

    /**
     * Handle logging configuration changes.
     *
     * @param what What changed.
     * @param node which logging topic changed.
     */
    @SuppressWarnings("PMD.UselessParentheses")
    public void handleLoggingConfigurationChanges(WhatHappened what, Node node) {
        try (LockScope ls = LockScope.lock(lock)) {
            logger.atDebug().kv("logging-change-what", what).kv("logging-change-node", node).log();
            switch (what) {
                case initialized:
                    // fallthrough
                case childChanged:
                    LogConfigUpdate logConfigUpdate;
                    try {
                        logConfigUpdate = fromPojo(loggingTopics.toPOJO());
                    } catch (IllegalArgumentException e) {
                        logger.atError().kv("logging-config", loggingTopics).cause(e)
                                .log("Unable to parse logging config.");
                        return;
                    }
                    if (currentConfiguration == null || !currentConfiguration.equals(logConfigUpdate)) {
                        reconfigureLogging(logConfigUpdate);
                    }
                    break;
                case childRemoved:
                    LogManager.resetAllLoggers(node.getName());
                    break;
                case removed:
                    LogManager.resetAllLoggers(null);
                    break;
                default:
                    // do nothing
                    break;
            }
        }
    }

    private void reconfigureLogging(LogConfigUpdate logConfigUpdate) {
        if (logConfigUpdate.getOutputDirectory() != null && (currentConfiguration == null || !Objects
                .equals(currentConfiguration.getOutputDirectory(), logConfigUpdate.getOutputDirectory()))) {
            try {
                NucleusPaths.setLoggerPath(Paths.get(logConfigUpdate.getOutputDirectory()));
            } catch (IOException e) {
                logger.atError().cause(e).log("Unable to initialize logger output directory path");
            }
        }
        currentConfiguration = logConfigUpdate;
        LogManager.reconfigureAllLoggers(logConfigUpdate);
    }

    private String getComponentType(String serviceName) {
        return Coerce.toString(config.find(SERVICES_NAMESPACE_TOPIC, serviceName, SERVICE_TYPE_TOPIC_KEY));
    }

    private Validator getDeTildeValidator() {
        return (newV, old) -> kernelCommandLine.deTilde(Coerce.toString(newV));
    }

    @SuppressWarnings("PMD.NullAssignment")
    private Validator getRegionValidator() {
        return (newV, old) -> {
            String region = null;
            if (newV == null || newV instanceof String) {
                region = (String) newV;
            }

            // If the region value is empty/null, then try to get the region from the SDK lookup path
            if (!(newV instanceof String) || Utils.isEmpty(region)) {
                try {
                    region = DefaultAwsRegionProviderChain.builder().build().getRegion().toString();
                } catch (SdkClientException ex) {
                    region = null;
                    logger.atWarn().log("Error looking up AWS region", ex);
                }
            }
            // Snow* devices have a null region
            if (Utils.isEmpty(region) || !Region.regions().contains(Region.of(region))) {
                logger.atWarn().log("No valid AWS region found, falling back to default: {}", FALLBACK_DEFAULT_REGION);
                region = FALLBACK_DEFAULT_REGION;
            }

            config.lookup(SETENV_CONFIG_NAMESPACE, "AWS_DEFAULT_REGION").withValue(region);
            config.lookup(SETENV_CONFIG_NAMESPACE, SdkSystemSetting.AWS_REGION.environmentVariable())
                    .withValue(region);

            // Get the current FIPS mode for the AWS SDK. Default will be false (no FIPS).
            String useFipsMode = Boolean.toString(Coerce.toBoolean(getFipsMode()));
            //Download CA3 to support iotDataEndpoint
            if (Coerce.toBoolean(getFipsMode()) && !rootCA3Downloaded.get()) {
                rootCA3Downloaded.set(RootCAUtils.downloadRootCAsWithPath(Coerce.toString(getRootCAFilePath()),
                        RootCAUtils.AMAZON_ROOT_CA_3_URL));
            }
            // Set the FIPS property so our SDK clients will use this FIPS mode by default.
            // This won't change any client that exists already.
            System.setProperty(SdkSystemSetting.AWS_USE_FIPS_ENDPOINT.property(), useFipsMode);
            // Pass down the FIPS to components.
            config.lookup(SETENV_CONFIG_NAMESPACE, SdkSystemSetting.AWS_USE_FIPS_ENDPOINT.environmentVariable())
                    .withValue(useFipsMode);
            // Read by stream manager
            config.lookup(SETENV_CONFIG_NAMESPACE, "AWS_GG_FIPS_MODE").withValue(useFipsMode);
            return region;
        };
    }

    public Topics getRunWithTopic() {
        return getTopics(RUN_WITH_TOPIC);
    }

    public Topic getRunWithDefaultPosixUser() {
        return getRunWithTopic().lookup(RUN_WITH_DEFAULT_POSIX_USER);
    }

    public Topic getRunWithDefaultPosixShell() {
        return getRunWithTopic().lookup(RUN_WITH_DEFAULT_POSIX_SHELL).dflt(RUN_WITH_DEFAULT_POSIX_SHELL_VALUE);
    }

    public Topic getRunWithDefaultWindowsUser() {
        return getRunWithTopic().lookup(RUN_WITH_DEFAULT_WINDOWS_USER);
    }

    /**
     * Find the RunWithDefault.SystemResourceLimits topics.
     * @return topics
     */
    public Topics findRunWithDefaultSystemResourceLimits() {
        return config.findTopics(SERVICES_NAMESPACE_TOPIC, getNucleusComponentName(), CONFIGURATION_CONFIG_KEY,
                RUN_WITH_TOPIC, GreengrassService.SYSTEM_RESOURCE_LIMITS_TOPICS);
    }

    /**
     * Topic containing a set of key/value describing a platform. If provided, overrides (part of) the detected
     * platform.
     *
     * @return Platform override topic
     */
    public Topics getPlatformOverrideTopic() {
        return getTopics(PLATFORM_OVERRIDE_TOPIC);
    }

    /**
     * Get thing name configuration. Also adds the thing name to the env vars if it has changed.
     *
     * @return Thing name config topic.
     */
    public Topic getThingName() {
        Topic thingNameTopic = config.lookup(SYSTEM_NAMESPACE_KEY, DEVICE_PARAM_THING_NAME).dflt("");
        config.lookup(SETENV_CONFIG_NAMESPACE, AWS_IOT_THING_NAME_ENV)
                .withValue(Coerce.toString(thingNameTopic));
        return thingNameTopic;
    }

    public Topic getCertificateFilePath() {
        return config.lookup(SYSTEM_NAMESPACE_KEY, DEVICE_PARAM_CERTIFICATE_FILE_PATH).dflt("")
                .addValidator(deTildeValidator);
    }

    public Topic getPrivateKeyFilePath() {
        return config.lookup(SYSTEM_NAMESPACE_KEY, DEVICE_PARAM_PRIVATE_KEY_PATH).dflt("")
                .addValidator(deTildeValidator);
    }

    public Topic getRootCAFilePath() {
        return config.lookup(SYSTEM_NAMESPACE_KEY, DEVICE_PARAM_ROOT_CA_PATH).dflt("")
                .addValidator(deTildeValidator);
    }

    public Topic getIpcSocketPath() {
        return config.find(SYSTEM_NAMESPACE_KEY, DEVICE_PARAM_IPC_SOCKET_PATH);
    }

    public Topic getInterpolateComponentConfiguration() {
        return getTopic(DEVICE_PARAM_INTERPOLATE_COMPONENT_CONFIGURATION).dflt(false);
    }

    public Topic getGGDataEndpoint() {
        return getTopic(DEVICE_PARAM_GG_DATA_ENDPOINT).dflt("");
    }

    public Topic getIotDataEndpoint() {
        return getTopic(DEVICE_PARAM_IOT_DATA_ENDPOINT).dflt("");
    }

    public Topic getIotCredentialEndpoint() {
        return getTopic(DEVICE_PARAM_IOT_CRED_ENDPOINT).dflt("");
    }

    public Topic getAWSRegion() {
        return getTopic(DEVICE_PARAM_AWS_REGION).dflt("").addValidator(regionValidator);
    }

    public Topic getFipsMode() {
        return getTopic(DEVICE_PARAM_FIPS_MODE).dflt("false");
    }

    public Topic getGreengrassDataPlanePort() {
        return getTopic(DEVICE_PARAM_GG_DATA_PLANE_PORT).dflt(GG_DATA_PLANE_PORT_DEFAULT);
    }

    // Why have this method as well as the one above? The reason is that the validator
    // is called immediately, so the initial call will have a null region which will make
    // the validator use the default region provider chain to do a lookup which isn't necessary.
    @SuppressFBWarnings("NM_CONFUSING") // confusing with setAwsRegion in ProvisionConfiguration
    public void setAWSRegion(String region) {
        getTopic(DEVICE_PARAM_AWS_REGION).withValue(region).addValidator(regionValidator);
    }

    public Topic getEnvironmentStage() {
        return getTopic(DEVICE_PARAM_ENV_STAGE).withNewerValue(1, DEFAULT_ENV_STAGE);
    }

    public Topics getMQTTNamespace() {
        return getTopics(DEVICE_MQTT_NAMESPACE);
    }

    public Topics getSpoolerNamespace() {
        return getMQTTNamespace().lookupTopics(DEVICE_SPOOLER_NAMESPACE);
    }

    public Topics getNetworkProxyNamespace() {
        return getTopics(DEVICE_NETWORK_PROXY_NAMESPACE);
    }

    public Topics getProxyNamespace() {
        return getNetworkProxyNamespace().lookupTopics(DEVICE_PROXY_NAMESPACE);
    }

    public String getNoProxyAddresses() {
        return Coerce.toString(getNetworkProxyNamespace().findOrDefault("", DEVICE_PARAM_NO_PROXY_ADDRESSES));
    }

    public String getProxyUrl() {
        return Coerce.toString(getProxyNamespace().findOrDefault("", DEVICE_PARAM_PROXY_URL));
    }

    public String getProxyUsername() {
        return Coerce.toString(getProxyNamespace().findOrDefault("", DEVICE_PARAM_PROXY_USERNAME));
    }

    public String getProxyPassword() {
        return Coerce.toString(getProxyNamespace().findOrDefault("", DEVICE_PARAM_PROXY_PASSWORD));
    }

    public Topic getIotRoleAlias() {
        return getTopic(IOT_ROLE_ALIAS_TOPIC).dflt("");
    }

    public Topic getComponentStoreMaxSizeBytes() {
        return getTopic(COMPONENT_STORE_MAX_SIZE_BYTES);
    }

    public Topic getDeploymentPollingFrequencySeconds() {
        return getTopic(DEPLOYMENT_POLLING_FREQUENCY_SECONDS);
    }

    /**
     * Get s3 endpoint topic.
     *
     * @return s3 endpoint topic
     */
    public Topic gets3EndpointType() {
        return getTopic(S3_ENDPOINT_TYPE).dflt(S3EndpointType.GLOBAL.name());
    }

    /**
     * Subscribe to all device configuration change.
     *
     * @param cc Subscribe handler
     */
    public void onAnyChange(ChildChanged cc) {
        config.lookupTopics(SERVICES_NAMESPACE_TOPIC, getNucleusComponentName(), CONFIGURATION_CONFIG_KEY)
                .subscribe(cc);
        config.lookupTopics(SYSTEM_NAMESPACE_KEY).subscribe(cc);
    }

    /**
     * Validates the device configuration parameters.
     *
     * @throws DeviceConfigurationException when configuration parameters are not valid
     */
    public void validate() throws DeviceConfigurationException {
        validate(false);
    }

    /**
     * Validates the device configuration parameters.
     *
     * @param cloudOnly true to only check cloud related settings
     * @throws DeviceConfigurationException when configuration parameters are not valid
     */
    public void validate(boolean cloudOnly) throws DeviceConfigurationException {
        String thingName = Coerce.toString(getThingName());
        String certificateFilePath = Coerce.toString(getCertificateFilePath());
        String privateKeyPath = Coerce.toString(getPrivateKeyFilePath());
        String rootCAPath = Coerce.toString(getRootCAFilePath());
        String iotDataEndpoint = Coerce.toString(getIotDataEndpoint());
        String iotCredEndpoint = Coerce.toString(getIotCredentialEndpoint());
        String awsRegion = Coerce.toString(getAWSRegion());

        validateDeviceConfiguration(thingName, certificateFilePath, privateKeyPath, rootCAPath, iotDataEndpoint,
                iotCredEndpoint, awsRegion, cloudOnly);
    }

    /**
     * Check if device is configured to talk to cloud.
     *
     * @return true is device configuration is valid
     */
    public boolean isDeviceConfiguredToTalkToCloud() {
        Boolean cachedValue = deviceConfigValidateCachedResult.get();
        if (cachedValue != null) {
            return cachedValue;
        }
        try {
            validate(true);
            deviceConfigValidateCachedResult.set(true);
            return true;
        } catch (DeviceConfigurationException e) {
            deviceConfigValidateCachedResult.set(false);
            return false;
        }
    }

    /**
     * Reports if device provisioning values have changed.
     *
     * @param node what may have changed during device provisioning
     * @param checkThingNameOnly has initial setup has been done for a given service
     * @return true if any device provisioning values have changed before initial service setup
     *         or if the thing name has changed after
     */
    public static boolean provisionInfoNodeChanged(Node node, Boolean checkThingNameOnly) {
        if (checkThingNameOnly) {
            return node.childOf(DEVICE_PARAM_THING_NAME);
        } else {
            // List of configuration nodes that may change during device provisioning
            return node.childOf(DEVICE_PARAM_THING_NAME) || node.childOf(DEVICE_PARAM_IOT_DATA_ENDPOINT)
                    || node.childOf(DEVICE_PARAM_PRIVATE_KEY_PATH)
                    || node.childOf(DEVICE_PARAM_CERTIFICATE_FILE_PATH) || node.childOf(DEVICE_PARAM_ROOT_CA_PATH)
                    || node.childOf(DEVICE_PARAM_AWS_REGION);
        }
    }

    private Topic getTopic(String parameterName) {
        return config
                .lookup(SERVICES_NAMESPACE_TOPIC, getNucleusComponentName(), CONFIGURATION_CONFIG_KEY, parameterName);
    }

    private Topics getTopics(String parameterName) {
        return config
                .lookupTopics(SERVICES_NAMESPACE_TOPIC, getNucleusComponentName(),
                        CONFIGURATION_CONFIG_KEY, parameterName);
    }

    /**
     * Get the nucleus version from the running configuration or nucleus zip.
     *
     * @return nucleus version
     */
    public String getNucleusVersion() {
        String version = null;
        // Prefer to get the version from the active config
        Topics componentTopic = config.findTopics(SERVICES_NAMESPACE_TOPIC, getNucleusComponentName());
        if (componentTopic != null && componentTopic.find(VERSION_CONFIG_KEY) != null) {
            version = Coerce.toString(componentTopic.find(VERSION_CONFIG_KEY));
        }
        if (version == null) {
            return FALLBACK_VERSION;
        } else {
            return version;
        }
    }

    /**
     * Get the Nucleus version from the ZIP file.
     * Get the Nucleus version from the ZIP file
     *
     * @return version from the zip file, or a default if the version can't be determined
     */
    public static String getVersionFromBuildRecipeFile() {
        try {
            com.amazon.aws.iot.greengrass.component.common.ComponentRecipe recipe = getRecipeSerializer()
                    .readValue(locateCurrentKernelUnpackDir().resolve(NUCLEUS_BUILD_METADATA_DIRECTORY)
                                    .resolve(NUCLEUS_RECIPE_FILENAME).toFile(),
                            com.amazon.aws.iot.greengrass.component.common.ComponentRecipe.class);
            if (recipe != null) {
               return recipe.getComponentVersion().toString();
            }
        } catch (IOException | URISyntaxException e) {
            logger.atError().log("Unable to determine Greengrass version", e);
        }
        logger.atError().log("Unable to determine Greengrass version from build recipe file. "
                + "Build file not found, or version not found in file. Falling back to {}", FALLBACK_VERSION);
        return FALLBACK_VERSION;
    }

    private void validateDeviceConfiguration(String thingName, String certificateFilePath, String privateKeyPath,
                                             String rootCAPath, String iotDataEndpoint, String iotCredEndpoint,
                                             String awsRegion, boolean cloudOnly)
            throws DeviceConfigurationException {
        List<String> errors = new ArrayList<>();
        if (Utils.isEmpty(thingName)) {
            errors.add(DEVICE_PARAM_THING_NAME + CANNOT_BE_EMPTY);
        }
        if (Utils.isEmpty(certificateFilePath)) {
            errors.add(DEVICE_PARAM_CERTIFICATE_FILE_PATH + CANNOT_BE_EMPTY);
        }
        if (Utils.isEmpty(privateKeyPath)) {
            errors.add(DEVICE_PARAM_PRIVATE_KEY_PATH + CANNOT_BE_EMPTY);
        }
        if (Utils.isEmpty(rootCAPath)) {
            errors.add(DEVICE_PARAM_ROOT_CA_PATH + CANNOT_BE_EMPTY);
        }
        if (Utils.isEmpty(iotDataEndpoint)) {
            errors.add(DEVICE_PARAM_IOT_DATA_ENDPOINT + CANNOT_BE_EMPTY);
        }
        if (Utils.isEmpty(iotCredEndpoint)) {
            errors.add(DEVICE_PARAM_IOT_CRED_ENDPOINT + CANNOT_BE_EMPTY);
        }
        if (Utils.isEmpty(awsRegion)) {
            errors.add(DEVICE_PARAM_AWS_REGION + CANNOT_BE_EMPTY);
        }

        try {
            validateEndpoints(awsRegion, iotCredEndpoint, iotDataEndpoint);
            if (!cloudOnly) {
                Platform.getInstance().getRunWithGenerator().validateDefaultConfiguration(this);
            }
        } catch (DeviceConfigurationException | ComponentConfigurationValidationException e) {
            errors.add(e.getMessage());
        }
        if (!errors.isEmpty()) {
            throw new DeviceConfigurationException(errors.toString());
        }
    }

    /**
     * Validate the IoT credential and data endpoint with the provided AWS region. Currently it checks that the if the
     * endpoints are provided, then the AWS region should be a part of the URL.
     *
     * @param awsRegion       the provided AWS region.
     * @param iotCredEndpoint the provided IoT credentials endpoint
     * @param iotDataEndpoint the providedIoT data endpoint
     * @throws ComponentConfigurationValidationException if the region is not valid or if the IoT endpoints do not have
     *                                                   the AWS region as a part of its URL.
     */
    public void validateEndpoints(String awsRegion, String iotCredEndpoint, String iotDataEndpoint)
            throws ComponentConfigurationValidationException {
        if (Utils.isNotEmpty(awsRegion) && !Region.regions().contains(Region.of(awsRegion))) {
            logger.atWarn().log("Error looking up AWS region {}", awsRegion);
            throw new ComponentConfigurationValidationException(
                    String.format("Error looking up AWS region %s", awsRegion), DeploymentErrorCode.UNSUPPORTED_REGION);
        }
        if (Utils.isNotEmpty(iotCredEndpoint) && iotCredEndpoint.contains(AMAZON_DOMAIN_SEQUENCE)
                && !iotCredEndpoint.contains(awsRegion)) {
            throw new ComponentConfigurationValidationException(
                    String.format("IoT credential endpoint region %s does not match the AWS region %s of the device",
                            iotCredEndpoint, awsRegion), DeploymentErrorCode.IOT_CRED_ENDPOINT_FORMAT_NOT_VALID);
        }
        if (Utils.isNotEmpty(iotDataEndpoint) && iotDataEndpoint.contains(AMAZON_DOMAIN_SEQUENCE)
                && !iotDataEndpoint.contains(awsRegion)) {
            throw new ComponentConfigurationValidationException(
                    String.format("IoT data endpoint region %s does not match the AWS region %s of the device",
                            iotDataEndpoint, awsRegion), DeploymentErrorCode.IOT_DATA_ENDPOINT_FORMAT_NOT_VALID);
        }
    }

    /**
     * Get the logger configuration from POJO.
     *
     * @param pojoMap The map containing logger configuration.
     * @return the logger configuration.
     * @throws IllegalArgumentException if the POJO map has an invalid argument.
     */
    private LogConfigUpdate fromPojo(Map<String, Object> pojoMap) {
        LogConfigUpdate.LogConfigUpdateBuilder configUpdate = LogConfigUpdate.builder();
        pojoMap.forEach((s, o) -> {
            switch (s) {
                case "level":
                    configUpdate.level(Level.valueOf(Coerce.toString(o)));
                    break;
                case "fileSizeKB":
                    configUpdate.fileSizeKB(Coerce.toLong(o));
                    break;
                case "totalLogsSizeKB":
                    configUpdate.totalLogsSizeKB(Coerce.toLong(o));
                    break;
                case "format":
                    configUpdate.format(LogFormat.valueOf(Coerce.toString(o)));
                    break;
                case "outputDirectory":
                    configUpdate.outputDirectory(Coerce.toString(o));
                    break;
                case "outputType":
                    configUpdate.outputType(LogStore.valueOf(Coerce.toString(o)));
                    break;
                default:
                    throw new IllegalArgumentException("Unexpected value: " + s);
            }
        });
        return configUpdate.build();
    }

    /*
     * shadow manager plugin depends on this directly
     * shadow manager, cda, and ggdcm depends on getConfiguredClientBuilder in ClientConfigurationUtils calls this
     */
    public KeyManager[] getDeviceIdentityKeyManagers() throws TLSAuthException {
        return securityService.getDeviceIdentityKeyManagers();
    }

    public Topics getHttpClientOptions() {
        return getTopics("httpClient");
    }

    /**
     * Set device config based on existing System property.
     */
    private void handleExistingSystemProperty() {
        //handle s3 endpoint type
        if (System.getProperty(S3_ENDPOINT_PROP_NAME) != null
                && System.getProperty(S3_ENDPOINT_PROP_NAME).equalsIgnoreCase(S3EndpointType.REGIONAL.name())) {
            gets3EndpointType().withValue(S3EndpointType.REGIONAL.name());
        }
        //handle fips mode
        String useFipsMode = System.getProperty(SdkSystemSetting.AWS_USE_FIPS_ENDPOINT.property());
        if (Coerce.toBoolean(useFipsMode)) {
            getFipsMode().withValue(useFipsMode);
        }
    }
}
