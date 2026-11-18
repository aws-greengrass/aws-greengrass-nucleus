/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.DependencyResolver;
import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.componentmanager.models.ComponentRequirementIdentifier;
import com.aws.greengrass.config.ChildChanged;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.converter.DeploymentDocumentConverter;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.GreengrassServiceClientFactory;
import com.aws.greengrass.util.exceptions.TLSAuthException;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.vdurmont.semver4j.Requirement;
import org.apache.commons.codec.binary.Base64;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.greengrassv2data.GreengrassV2DataClient;
import software.amazon.awssdk.utils.ImmutableMap;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for the fleet provisioning race. A device config change to the root CA, certificate or key path
 * closes the HTTP client of {@link GreengrassServiceClientFactory}. Fleet provisioning makes these changes while a
 * deployment may already be calling ListThingGroupsForCoreDevice on that client.
 */
@ExtendWith({GGExtension.class, MockitoExtension.class})
class ThingGroupFetchDuringCertChangeTest {
    private static final char[] PASSWORD = "password".toCharArray();
    private static final String THING_NAME = "testThing";
    private static final String CLOUD_GROUP = "thinggroup/fromCloud";
    private static final String SAVED_GROUP = "thinggroup/saved";

    @TempDir
    Path tempDir;
    @Mock
    private DeviceConfiguration deviceConfiguration;
    @Mock
    private DependencyResolver dependencyResolver;
    @Mock
    private ComponentManager componentManager;
    @Mock
    private KernelConfigResolver kernelConfigResolver;
    @Mock
    private DeploymentConfigMerger deploymentConfigMerger;
    @Mock
    private DeploymentDocumentDownloader deploymentDocumentDownloader;

    private Context context;
    private HttpsServer server;
    private final AtomicInteger thingGroupRequests = new AtomicInteger();
    private final AtomicReference<Runnable> onFirstThingGroupRequest = new AtomicReference<>(() -> {
    });
    private CountingClientFactory clientFactory;
    private Runnable certChange;
    private ExecutorService executor;
    private Topics deploymentServiceConfig;
    private ThingGroupHelper thingGroupHelper;

    @BeforeEach
    void setup() throws Exception {
        context = new Context();
        executor = Executors.newSingleThreadExecutor();
        Path rootCa = startServer();

        lenient().when(deviceConfiguration.getEnvironmentStage())
                .thenReturn(Topic.of(context, "environmentStage", "prod"));
        lenient().when(deviceConfiguration.getGGDataEndpoint()).thenReturn(
                Topic.of(context, "greengrassDataPlaneEndpoint", "https://localhost:" + server.getAddress().getPort()));
        lenient().when(deviceConfiguration.getAWSRegion())
                .thenReturn(Topic.of(context, "awsRegion", "us-east-1"));
        lenient().when(deviceConfiguration.getRootCAFilePath())
                .thenReturn(Topic.of(context, DEVICE_PARAM_ROOT_CA_PATH, rootCa.toString()));
        lenient().when(deviceConfiguration.getThingName())
                .thenReturn(Topic.of(context, "thingName", THING_NAME));
        when(deviceConfiguration.isDeviceConfiguredToTalkToCloud()).thenReturn(true);

        clientFactory = new CountingClientFactory(deviceConfiguration);
        ArgumentCaptor<ChildChanged> configListener = ArgumentCaptor.forClass(ChildChanged.class);
        verify(deviceConfiguration).onAnyChange(configListener.capture());
        certChange = () -> configListener.getValue()
                .childChanged(WhatHappened.childChanged, deviceConfiguration.getRootCAFilePath());
        thingGroupHelper = new ThingGroupHelper(clientFactory, deviceConfiguration);
        thingGroupHelper.setClientExceptionRetryConfig(thingGroupHelper.getClientExceptionRetryConfig().toBuilder()
                .initialRetryInterval(Duration.ZERO).maxRetryInterval(Duration.ZERO).build());

        deploymentServiceConfig = Topics.of(context, DeploymentService.DEPLOYMENT_SERVICE_TOPICS, null);
        Topics groupToRoot = deploymentServiceConfig.lookupTopics(DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS);
        groupToRoot.lookupTopics(CLOUD_GROUP, "CloudGroupComponent")
                .replaceAndWait(ImmutableMap.of(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        groupToRoot.lookupTopics(SAVED_GROUP, "SavedGroupComponent")
                .replaceAndWait(ImmutableMap.of(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));

        when(dependencyResolver.resolveDependencies(any(), any())).thenReturn(Collections.emptyList());
        when(componentManager.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        when(deploymentConfigMerger.mergeInNewConfig(any(), any(), anyLong())).thenReturn(CompletableFuture
                .completedFuture(new DeploymentResult(DeploymentResult.DeploymentStatus.SUCCESSFUL, null)));
    }

    @AfterEach
    void cleanup() throws Exception {
        server.stop(0);
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        context.close();
    }

    @Test
    void GIVEN_cloud_deployment_WHEN_cert_change_after_fetch_THEN_request_uses_new_client_without_refetch()
            throws Exception {
        clientFactory.afterFirstFetch = certChange;
        DeploymentDocument document = cloudDeployment();

        DeploymentResult result = runDeployment(document, Deployment.DeploymentType.IOT_JOBS);

        assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL, result.getDeploymentStatus());
        assertEquals(1, thingGroupRequests.get());
        assertEquals(1, clientFactory.fetches.get());
        verify(dependencyResolver).resolveDependencies(eq(document),
                eq(rootPackages(CLOUD_GROUP, "CloudGroupComponent")));
    }

    @Test
    void GIVEN_cloud_deployment_WHEN_cert_change_during_request_THEN_sdk_retry_succeeds_without_refetch()
            throws Exception {
        onFirstThingGroupRequest.set(certChange);
        DeploymentDocument document = cloudDeployment();

        DeploymentResult result = runDeployment(document, Deployment.DeploymentType.IOT_JOBS);

        assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL, result.getDeploymentStatus());
        assertEquals(2, thingGroupRequests.get());
        assertEquals(1, clientFactory.fetches.get());
        verify(dependencyResolver).resolveDependencies(eq(document),
                eq(rootPackages(CLOUD_GROUP, "CloudGroupComponent")));
    }

    @Test
    void GIVEN_local_deployment_WHEN_cert_change_after_fetch_THEN_cloud_groups_used() throws Exception {
        clientFactory.afterFirstFetch = certChange;
        DeploymentDocument document = DeploymentDocument.builder().deploymentId("localDeployment")
                .timestamp(System.currentTimeMillis())
                .groupName(DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME).build();

        DeploymentResult result = runDeployment(document, Deployment.DeploymentType.LOCAL);

        assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL, result.getDeploymentStatus());
        verify(dependencyResolver).resolveDependencies(eq(document),
                eq(rootPackages(CLOUD_GROUP, "CloudGroupComponent")));
    }

    private static DeploymentDocument cloudDeployment() {
        return DeploymentDocument.builder().deploymentId("cloudDeployment")
                .timestamp(System.currentTimeMillis()).groupName("thinggroup/target").build();
    }

    private DeploymentResult runDeployment(DeploymentDocument document, Deployment.DeploymentType type)
            throws InterruptedException {
        return new DefaultDeploymentTask(dependencyResolver, componentManager, kernelConfigResolver,
                deploymentConfigMerger, LogManager.getLogger("unit test"),
                new Deployment(document, type, "id", DEFAULT), deploymentServiceConfig, executor,
                deploymentDocumentDownloader, thingGroupHelper, deviceConfiguration).call();
    }

    private static Map<String, Set<ComponentRequirementIdentifier>> rootPackages(String group, String component) {
        Map<String, Set<ComponentRequirementIdentifier>> map = new HashMap<>();
        map.put(group, Collections.singleton(
                new ComponentRequirementIdentifier(component, Requirement.buildNPM("1.0.0"))));
        return map;
    }

    /**
     * Counts client fetches, and can run a config change right after handing out the first client, before its
     * request leases a connection. That is the order seen on a device: the deployment task got the client, a device
     * config write closed it, and the request then went out on the closed client.
     */
    private static class CountingClientFactory extends GreengrassServiceClientFactory {
        private final AtomicInteger fetches = new AtomicInteger();
        private Runnable afterFirstFetch = () -> {
        };

        CountingClientFactory(DeviceConfiguration deviceConfiguration) {
            super(deviceConfiguration);
        }

        @Override
        public GreengrassV2DataClient fetchGreengrassV2DataClient()
                throws DeviceConfigurationException, TLSAuthException {
            GreengrassV2DataClient client = super.fetchGreengrassV2DataClient();
            if (fetches.incrementAndGet() == 1) {
                afterFirstFetch.run();
            }
            return client;
        }
    }

    private Path startServer() throws Exception {
        KeyPairGenerator keygen = KeyPairGenerator.getInstance("RSA");
        keygen.initialize(2048);
        KeyPair keyPair = keygen.generateKeyPair();
        X500Name name = new X500Name("CN=localhost");
        Date now = new Date();
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(new X509v3CertificateBuilder(name,
                BigInteger.ONE, now, new Date(now.getTime() + TimeUnit.DAYS.toMillis(1)), name,
                SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded()))
                .build(new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate())));
        Path rootCa = tempDir.resolve("rootCA.pem");
        Files.write(rootCa, ("-----BEGIN CERTIFICATE-----\n" + Base64.encodeBase64String(cert.getEncoded())
                + "\n-----END CERTIFICATE-----\n").getBytes(StandardCharsets.UTF_8));

        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        keyStore.setKeyEntry("server", keyPair.getPrivate(), PASSWORD, new Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, PASSWORD);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        server.createContext("/greengrass/v2/coreDevices/" + THING_NAME + "/thingGroups", exchange -> {
            if (thingGroupRequests.incrementAndGet() == 1) {
                onFirstThingGroupRequest.get().run();
            }
            byte[] body = "{\"thingGroups\":[{\"thingGroupName\":\"fromCloud\",\"rootToParentThingGroups\":[]}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return rootCa;
    }
}
