/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.exceptions.ComponentConfigurationValidationException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.security.SecurityService;
import com.aws.greengrass.security.exceptions.MqttConnectionProviderException;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.EncryptionUtils;
import com.aws.greengrass.util.IotSdkClientFactory;
import com.aws.greengrass.util.ProxyUtils;
import com.aws.greengrass.util.RegionUtils;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.exceptions.InvalidEnvironmentStageException;
import com.aws.greengrass.util.exceptions.TLSAuthException;
import org.apache.http.client.utils.URIBuilder;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.crt.http.HttpProxyOptions;
import software.amazon.awssdk.crt.io.ClientTlsContext;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.crt.io.TlsContextOptions;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.greengrassv2data.GreengrassV2DataClient;
import software.amazon.awssdk.services.greengrassv2data.GreengrassV2DataClientBuilder;
import software.amazon.awssdk.services.greengrassv2data.model.GreengrassV2DataException;
import software.amazon.awssdk.services.greengrassv2data.model.ListThingGroupsForCoreDeviceRequest;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.security.auth.x500.X500Principal;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_ENV_STAGE;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_MQTT_NAMESPACE;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_NETWORK_PROXY_NAMESPACE;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_AWS_REGION;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_ENV_STAGE;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_GG_DATA_ENDPOINT;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_GG_DATA_PLANE_PORT;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_IOT_DATA_ENDPOINT;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_NO_PROXY_ADDRESSES;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_PROXY_PASSWORD;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_PROXY_URL;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_PROXY_USERNAME;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PROXY_NAMESPACE;
import static com.aws.greengrass.deployment.DeviceConfiguration.GG_DATA_PLANE_PORT_DEFAULT;
import static com.aws.greengrass.deployment.DynamicComponentConfigurationValidator.DEFAULT_TIMEOUT_SECOND;
import static com.aws.greengrass.mqttclient.MqttClient.DEFAULT_MQTT_KEEP_ALIVE_TIMEOUT;
import static com.aws.greengrass.mqttclient.MqttClient.DEFAULT_MQTT_OPERATION_TIMEOUT;
import static com.aws.greengrass.mqttclient.MqttClient.DEFAULT_MQTT_PING_TIMEOUT;
import static com.aws.greengrass.mqttclient.MqttClient.DEFAULT_MQTT_PORT;
import static com.aws.greengrass.mqttclient.MqttClient.DEFAULT_MQTT_SOCKET_TIMEOUT;
import static com.aws.greengrass.mqttclient.MqttClient.MQTT_KEEP_ALIVE_TIMEOUT_KEY;
import static com.aws.greengrass.mqttclient.MqttClient.MQTT_OPERATION_TIMEOUT_KEY;
import static com.aws.greengrass.mqttclient.MqttClient.MQTT_PING_TIMEOUT_KEY;
import static com.aws.greengrass.mqttclient.MqttClient.MQTT_PORT_KEY;
import static com.aws.greengrass.mqttclient.MqttClient.MQTT_SOCKET_TIMEOUT_KEY;

public class ConnectivityValidator implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(ConnectivityValidator.class);

    private final DeviceConfiguration deviceConfiguration;
    private final SecurityService securityService;

    private AwsIotMqttConnectionBuilder builder;
    private MqttClientConnection connection;
    private GreengrassV2DataClient greengrassV2DataClient;
    private TlsContextOptions proxyTlsOptions;
    private ClientTlsContext tlsContext;

    @Inject
    public ConnectivityValidator(DeviceConfiguration deviceConfiguration, SecurityService securityService) {
        this.deviceConfiguration = deviceConfiguration;
        this.securityService = securityService;
    }


    /**
     * Validate Mqtt and HTTP connectivity using the nucleus config.
     *
     * @param totallyCompleteFuture future deployment result
     * @param nucleusConfig         nucleus config to validate
     * @param deployment            deployment
     */
    public boolean validateConnectivity(CompletableFuture<DeploymentResult> totallyCompleteFuture,
                                        Map<String, Object> nucleusConfig, Deployment deployment) {
        Integer timeoutSec;
        timeoutSec = deployment.getDeploymentDocumentObj().getConfigurationValidationPolicy().timeoutInSeconds();
        long configTimeout = Duration.ofSeconds(DEFAULT_TIMEOUT_SECOND).toMillis();
        if (timeoutSec != null) {
            configTimeout = Duration.ofSeconds(timeoutSec).toMillis();
        }

        if (configTimeout == 0 || !deviceConfiguration.isDeviceConfiguredToTalkToCloud()) {
            logger.atDebug().log("Skipping connectivity validation");
            return true;
        }

        if (nucleusConfig != null) {
            // MQTT connectivity validation
            boolean reconnected = false;
            try {
                logger.atDebug().log("Checking MQTT client can connect");
                createMqttConnectionBuilder(nucleusConfig);
                checkMqttConnection(configTimeout);
                reconnected = true;
            } catch (MqttConnectionProviderException e) {
                logger.atError().cause(e).log("Failed to creat MQTT connection builder for connectivity validation");
                totallyCompleteFuture
                        .complete(new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, e));
            } catch (ComponentConfigurationValidationException e) {
                logger.atError().cause(e).log("MQTT connectivity validation failed");
                totallyCompleteFuture
                        .complete(new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, e));
            } catch (InterruptedException e) {
                logger.atError().cause(e).log("MQTT validation interrupted unexpectedly");
                totallyCompleteFuture
                        .complete(new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, e));
            }
            if (!reconnected) {
                return false;
            }

            // HTTP connectivity validation
            reconnected = false;
            try {
                logger.atDebug().log("Checking HTTP client can connect");
                createGGv2DataClient(nucleusConfig);
                checkHttpConnection(configTimeout);
                reconnected = true;
            } catch (ComponentConfigurationValidationException e) {
                logger.atError().cause(e).log("HTTP connectivity validation failed");
                totallyCompleteFuture
                        .complete(new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, e));
            } catch (CertificateException | IOException | KeyStoreException | NoSuchAlgorithmException
                     | TLSAuthException | InvalidEnvironmentStageException e) {
                logger.atError().cause(e).log("Failed to create GGv2 data client for HTTP connectivity validation");
                totallyCompleteFuture
                        .complete(new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, e));

            } catch (InterruptedException e) {
                logger.atError().cause(e).log("HTTP validation interrupted unexpectedly");
                totallyCompleteFuture
                        .complete(new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, e));
            }
            return reconnected;
        }

        return true;
    }

    private void checkHttpConnection(long timeout) throws InterruptedException,
            ComponentConfigurationValidationException {
        ListThingGroupsForCoreDeviceRequest request = ListThingGroupsForCoreDeviceRequest.builder()
                .coreDeviceThingName(Coerce.toString(deviceConfiguration.getThingName())).build();
        long tryUntil = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < tryUntil) {
            try {
                greengrassV2DataClient.listThingGroupsForCoreDevice(request);
                return;
            } catch (GreengrassV2DataException | SdkClientException e) {
                Thread.sleep(TimeUnit.SECONDS.toMillis(5));
            }
        }
        throw new ComponentConfigurationValidationException(
                "HTTP client failed to connect to IoT Core using new configurations",
                DeploymentErrorCode.FAILED_TO_RECONNECT);
    }

    private void checkMqttConnection(long timeout) throws InterruptedException,
            ComponentConfigurationValidationException {
        long tryUntil = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < tryUntil) {
            try {
                connection.connect().get();
                return;
            } catch (MqttException | ExecutionException e) {
                Thread.sleep(TimeUnit.SECONDS.toMillis(2));
            }
        }
        throw new ComponentConfigurationValidationException(
                "MQTT client failed to connect to IoT Core using new configurations",
                DeploymentErrorCode.FAILED_TO_RECONNECT);
    }

    private void createMqttConnectionBuilder(Map<String, Object> nucleusConfig)
            throws MqttConnectionProviderException {
        builder = securityService.getDeviceIdentityMqttConnectionBuilder();

        // get mqtt values from nucleus config to construct a MQTT client
        int pingTimeoutMs = tryGetMqttPingTimeoutFromNewConfig(nucleusConfig);
        int keepAliveMs = tryGetMqttKeepAliveMsFromNewConfig(nucleusConfig);
        if (keepAliveMs != 0 && keepAliveMs <= pingTimeoutMs) {
            throw new MqttException(String.format("%s must be greater than %s",
                    MQTT_KEEP_ALIVE_TIMEOUT_KEY, MQTT_PING_TIMEOUT_KEY));
        }
        String rootCaPath = Coerce.toString(deviceConfiguration.getRootCAFilePath());
        String clientId = Coerce.toString(deviceConfiguration.getThingName());
        String endpoint = tryGetIoTDataEndpointFromNewConfig(nucleusConfig);
        int port = tryGetMqttPortFromNewConfig(nucleusConfig);
        int operationTimeout = tryGetMqttOperationTimeoutFromNewConfig(nucleusConfig);
        int socketTimeout = tryGetMqttSocketTimeoutFromNewConfig(nucleusConfig);

        // start building mqtt connection
        builder.withCertificateAuthorityFromPath(null, rootCaPath)
                .withClientId(clientId)
                .withEndpoint(endpoint)
                .withPort(port)
                .withCleanSession(true)
                .withKeepAliveMs(keepAliveMs)
                .withProtocolOperationTimeoutMs(operationTimeout)
                .withPingTimeoutMs(pingTimeoutMs)
                .withSocketOptions(new SocketOptions()).withTimeoutMs(socketTimeout);

        // add proxy settings if configured
        String proxyUrl = tryGetProxyUrlFromNewConfig(nucleusConfig);
        if (!Utils.isEmpty(proxyUrl)) {
            HttpProxyOptions httpProxyOptions = new HttpProxyOptions();
            httpProxyOptions.setHost(ProxyUtils.getHostFromProxyUrl(proxyUrl));
            httpProxyOptions.setPort(ProxyUtils.getPortFromProxyUrl(proxyUrl));
            httpProxyOptions.setConnectionType(HttpProxyOptions.HttpProxyConnectionType.Tunneling);

            if ("https".equalsIgnoreCase(ProxyUtils.getSchemeFromProxyUrl(proxyUrl))) {
                proxyTlsOptions = MqttClient.getTlsContextOptions(rootCaPath);
                tlsContext = new ClientTlsContext(proxyTlsOptions);
                httpProxyOptions.setTlsContext(tlsContext);
            }

            String username = tryGetProxyUsernameFromNewConfig(nucleusConfig);
            String password = tryGetProxyPasswordFromNewConfig(nucleusConfig);
            String proxyUsername = ProxyUtils.getProxyUsername(proxyUrl, username);
            if (Utils.isNotEmpty(proxyUsername)) {
                httpProxyOptions.setAuthorizationType(HttpProxyOptions.HttpProxyAuthorizationType.Basic);
                httpProxyOptions.setAuthorizationUsername(proxyUsername);
                httpProxyOptions
                        .setAuthorizationPassword(ProxyUtils.getProxyPassword(proxyUrl, password));
            }

            String noProxy = tryGetNoProxyAddressFromNewConfig(nucleusConfig);
            boolean useProxy = true;
            // Only use the proxy when the endpoint we're connecting to is not in the NoProxyAddress list
            if (Utils.isNotEmpty(noProxy) && Utils.isNotEmpty(endpoint)) {
                useProxy = Arrays.stream(noProxy.split(",")).noneMatch(endpoint::matches);
            }
            if (useProxy) {
                builder.withHttpProxyOptions(httpProxyOptions);
            }
        }

        connection = builder.build();
    }

    @SuppressWarnings("PMD.ConfusingTernary")
    private void createGGv2DataClient(Map<String, Object> nucleusConfig) throws CertificateException,
            IOException, KeyStoreException, NoSuchAlgorithmException, TLSAuthException,
            InvalidEnvironmentStageException {
        String rootCAPath = Coerce.toString(deviceConfiguration.getRootCAFilePath());
        if (Utils.isEmpty(rootCAPath)) {
            throw new RuntimeException("Missing root CA");
        }
        List<X509Certificate> trustCertificates = EncryptionUtils.loadX509Certificates(Paths.get(rootCAPath));
        KeyStore tmKeyStore = KeyStore.getInstance("JKS");
        tmKeyStore.load(null, null);
        for (X509Certificate certificate : trustCertificates) {
            X500Principal principal = certificate.getSubjectX500Principal();
            String name = principal.getName("RFC2253");
            tmKeyStore.setCertificateEntry(name, certificate);
        }
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("X509");
        trustManagerFactory.init(tmKeyStore);
        TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
        KeyManager[] keyManagers = deviceConfiguration.getDeviceIdentityKeyManagers();

        ApacheHttpClient.Builder httpClient = ProxyUtils.getSdkHttpClientBuilder();
        httpClient.tlsKeyManagersProvider(() -> keyManagers).tlsTrustManagersProvider(() -> trustManagers);

        GreengrassV2DataClientBuilder clientBuilder = GreengrassV2DataClient.builder()
                // Use an empty credential provider because our requests don't need SigV4
                // signing, as they are going through IoT Core instead
                .credentialsProvider(AnonymousCredentialsProvider.create())
                .httpClient(httpClient.build())
                .overrideConfiguration(ClientOverrideConfiguration.builder().retryPolicy(RetryMode.STANDARD).build());

        String region = tryGetAwsRegionFromNewConfig(nucleusConfig);
        if (!Utils.isEmpty(region)) {
            String greengrassServiceEndpoint = getGreengrassServiceEndpoint(nucleusConfig);
            if (!Utils.isEmpty(greengrassServiceEndpoint)) {
                clientBuilder.endpointOverride(URI.create(greengrassServiceEndpoint));
                clientBuilder.region(Region.of(region));
            } else {
                clientBuilder.region(Region.of(region));
            }
        }
        greengrassV2DataClient = clientBuilder.build();
    }

    @SuppressWarnings("PMD.UseStringBufferForStringAppends")
    private String getGreengrassServiceEndpoint(Map<String, Object> nucleusConfig)
            throws InvalidEnvironmentStageException {
        IotSdkClientFactory.EnvironmentStage stage;
        stage = IotSdkClientFactory.EnvironmentStage.fromString(tryGetEnvironmentStageFromNewConfig(nucleusConfig));

        // Use customer configured GG endpoint if it is set
        String endpoint = tryGetGreengrassEndpointFromNewConfig(nucleusConfig);
        int port = tryGetGreengrassPortFromNewConfig(nucleusConfig);
        if (Utils.isEmpty(endpoint)) {
            // Fallback to global endpoint if no GG endpoint was specified
            return RegionUtils.getGreengrassDataPlaneEndpoint(tryGetAwsRegionFromNewConfig(nucleusConfig), stage, port);
        }

        // If customer specifies "iotdata" then use the iotdata endpoint, rather than needing them to specify the same
        // endpoint twice in the config.
        String iotData = tryGetIoTDataEndpointFromNewConfig(nucleusConfig);
        if ("iotdata".equalsIgnoreCase(endpoint) && Utils.isNotEmpty(iotData)) {
            // Use customer configured IoT data endpoint if it is set
            endpoint = iotData;
        }

        // This method returns a URI, not just an endpoint
        if (!endpoint.startsWith("https://")) {
            endpoint = "https://" + endpoint;
        }
        try {
            URI endpointUri = new URI(endpoint);
            // If the port is defined in the URI, then return it as-is
            if (endpointUri.getPort() != -1) {
                return endpoint;
            }
            // Modify the URI with the user's chosen port
            return new URIBuilder(endpointUri).setPort(port).toString();
        } catch (URISyntaxException e) {
            logger.atError().log("Invalid endpoint {}", endpoint, e);
            return RegionUtils.getGreengrassDataPlaneEndpoint(tryGetAwsRegionFromNewConfig(nucleusConfig), stage, port);
        }
    }

    private String tryGetAwsRegionFromNewConfig(Map<String, Object> kernelConfig) {
        String awsRegion = Coerce.toString(deviceConfiguration.getAWSRegion());
        if (kernelConfig.containsKey(DEVICE_PARAM_AWS_REGION)) {
            awsRegion = Coerce.toString(kernelConfig.get(DEVICE_PARAM_AWS_REGION));
        }
        return awsRegion;
    }

    private String tryGetIoTDataEndpointFromNewConfig(Map<String, Object> kernelConfig) {
        String iotDataEndpoint = Coerce.toString(deviceConfiguration.getIotDataEndpoint());
        if (kernelConfig.containsKey(DEVICE_PARAM_IOT_DATA_ENDPOINT)) {
            iotDataEndpoint = Coerce.toString(kernelConfig.get(DEVICE_PARAM_IOT_DATA_ENDPOINT));
        }
        return iotDataEndpoint;
    }

    private Map<String, Object> tryGetMqttFromNewConfig(Map<String, Object> kernelConfig) {
        Map<String, Object> mqtt = deviceConfiguration.getMQTTNamespace().toPOJO();
        if (kernelConfig.containsKey(DEVICE_MQTT_NAMESPACE)) {
            mqtt = (Map<String, Object>) kernelConfig.get(DEVICE_MQTT_NAMESPACE);
        }
        return mqtt;
    }

    private int tryGetMqttPingTimeoutFromNewConfig(Map<String, Object> kernelConfig) {
        Map<String, Object> mqtt = tryGetMqttFromNewConfig(kernelConfig);
        return Coerce.toInt(mqtt.getOrDefault(MQTT_PING_TIMEOUT_KEY, DEFAULT_MQTT_PING_TIMEOUT));
    }

    private int tryGetMqttKeepAliveMsFromNewConfig(Map<String, Object> kernelConfig) {
        Map<String, Object> mqtt = tryGetMqttFromNewConfig(kernelConfig);
        return Coerce.toInt(mqtt.getOrDefault(MQTT_KEEP_ALIVE_TIMEOUT_KEY, DEFAULT_MQTT_KEEP_ALIVE_TIMEOUT));
    }

    private int tryGetMqttPortFromNewConfig(Map<String, Object> kernelConfig) {
        Map<String, Object> mqtt = tryGetMqttFromNewConfig(kernelConfig);
        return Coerce.toInt(mqtt.getOrDefault(MQTT_PORT_KEY, DEFAULT_MQTT_PORT));
    }

    private int tryGetMqttOperationTimeoutFromNewConfig(Map<String, Object> kernelConfig) {
        Map<String, Object> mqtt = tryGetMqttFromNewConfig(kernelConfig);
        return Coerce.toInt(mqtt.getOrDefault(MQTT_OPERATION_TIMEOUT_KEY, DEFAULT_MQTT_OPERATION_TIMEOUT));
    }

    private int tryGetMqttSocketTimeoutFromNewConfig(Map<String, Object> kernelConfig) {
        Map<String, Object> mqtt = tryGetMqttFromNewConfig(kernelConfig);
        return Coerce.toInt(mqtt.getOrDefault(MQTT_SOCKET_TIMEOUT_KEY, DEFAULT_MQTT_SOCKET_TIMEOUT));
    }

    private Map<String, Object> tryGetNetworkProxyFromNewConfig(Map<String, Object> kernelConfig) {
        Map<String, Object> networkProxy = deviceConfiguration.getNetworkProxyNamespace().toPOJO();
        if (kernelConfig.containsKey(DEVICE_NETWORK_PROXY_NAMESPACE)) {
            networkProxy = (Map<String, Object>) kernelConfig.get(DEVICE_NETWORK_PROXY_NAMESPACE);
        }
        return networkProxy;
    }

    private Map<String, Object> tryGetProxyFromNewConfig(Map<String, Object> kernelConfig) {
        Map<String, Object> networkProxy = tryGetNetworkProxyFromNewConfig(kernelConfig);
        return (Map<String, Object>) networkProxy.get(DEVICE_PROXY_NAMESPACE);
    }

    private String tryGetProxyUrlFromNewConfig(Map<String, Object> kernelConfig) {
        Map<String, Object> proxy = tryGetProxyFromNewConfig(kernelConfig);
        return Coerce.toString(proxy.get(DEVICE_PARAM_PROXY_URL));
    }

    private String tryGetProxyUsernameFromNewConfig(Map<String, Object> kernelConfig) {
        Map<String, Object> proxy = tryGetProxyFromNewConfig(kernelConfig);
        return Coerce.toString(proxy.getOrDefault(DEVICE_PARAM_PROXY_USERNAME, ""));
    }

    private String tryGetProxyPasswordFromNewConfig(Map<String, Object> kernelConfig) {
        Map<String, Object> proxy = tryGetProxyFromNewConfig(kernelConfig);
        return Coerce.toString(proxy.getOrDefault(DEVICE_PARAM_PROXY_PASSWORD, ""));
    }

    private String tryGetNoProxyAddressFromNewConfig(Map<String, Object> kernelConfig) {
        Map<String, Object> proxy = tryGetProxyFromNewConfig(kernelConfig);
        return Coerce.toString(proxy.getOrDefault(DEVICE_PARAM_NO_PROXY_ADDRESSES, ""));
    }

    private String tryGetEnvironmentStageFromNewConfig(Map<String, Object> kernelConfig) {
        return Coerce.toString(kernelConfig.getOrDefault(DEVICE_PARAM_ENV_STAGE, DEFAULT_ENV_STAGE));
    }

    private String tryGetGreengrassEndpointFromNewConfig(Map<String, Object> kernelConfig) {
        return Coerce.toString(kernelConfig.getOrDefault(DEVICE_PARAM_GG_DATA_ENDPOINT, ""));
    }

    private int tryGetGreengrassPortFromNewConfig(Map<String, Object> kernelConfig) {
        return Coerce.toInt(kernelConfig.getOrDefault(DEVICE_PARAM_GG_DATA_PLANE_PORT, GG_DATA_PLANE_PORT_DEFAULT));
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.close();
        }
        if (greengrassV2DataClient != null) {
            greengrassV2DataClient.close();
        }
        if (builder != null) {
            builder.close();
        }
        if (proxyTlsOptions != null) {
            proxyTlsOptions.close();
        }
        if (tlsContext != null) {
            tlsContext.close();
        }
    }
}
