/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.aws.greengrass.componentmanager.exceptions.NoAvailableComponentVersionException;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.GreengrassServiceClientFactory;
import com.aws.greengrass.util.RetryUtils;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import org.apache.commons.lang3.Validate;
import software.amazon.awssdk.services.greengrassv2data.GreengrassV2DataClient;
import software.amazon.awssdk.services.greengrassv2data.model.ComponentCandidate;
import software.amazon.awssdk.services.greengrassv2data.model.ComponentPlatform;
import software.amazon.awssdk.services.greengrassv2data.model.ResolveComponentCandidatesRequest;
import software.amazon.awssdk.services.greengrassv2data.model.ResolveComponentCandidatesResponse;
import software.amazon.awssdk.services.greengrassv2data.model.ResolvedComponentVersion;
import software.amazon.awssdk.services.greengrassv2data.model.ResourceNotFoundException;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;

public class ComponentServiceHelper {

    protected static final Logger logger = LogManager.getLogger(ComponentServiceHelper.class);
    public static final int CLIENT_RETRY_COUNT = 3;

    private final GreengrassServiceClientFactory clientFactory;
    private final PlatformResolver platformResolver;

    @Inject
    public ComponentServiceHelper(GreengrassServiceClientFactory clientFactory,
                                  PlatformResolver platformResolver) {
        this.clientFactory = clientFactory;
        this.platformResolver = platformResolver;
    }

    /**
     * Resolve a component version with greengrass cloud service. The dependency resolution algorithm goes through the
     * dependencies node by node, so one component got resolve a time.
     *
     * @param componentName             component name to be resolve
     * @param localCandidateVersion     component local candidate version if available
     * @param versionRequirements       component dependents version requirement map
     * @return resolved component version and recipe
     * @throws NoAvailableComponentVersionException if no applicable version available in cloud service
     * @throws Exception when not able to retrieve greengrasV2DataClient
     */
    @SuppressWarnings({"PMD.PreserveStackTrace", "PMD.SignatureDeclareThrowsException"})
    ResolvedComponentVersion resolveComponentVersion(String componentName, Semver localCandidateVersion,
            Map<String, Requirement> versionRequirements) throws NoAvailableComponentVersionException, Exception {

        ComponentPlatform platform = ComponentPlatform.builder()
                .attributes(platformResolver.getCurrentPlatform()).build();
        Map<String, String> versionRequirementsInString = versionRequirements.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toString()));
        ComponentCandidate candidate = ComponentCandidate.builder().componentName(componentName)
                .componentVersion(localCandidateVersion == null ? null : localCandidateVersion.getValue())
                .versionRequirements(versionRequirementsInString).build();
        ResolveComponentCandidatesRequest request = ResolveComponentCandidatesRequest.builder()
                .platform(platform)
                .componentCandidates(Collections.singletonList(candidate)).build();

        ResolveComponentCandidatesResponse result;

        RetryUtils.RetryConfig clientExceptionRetryConfig =
                RetryUtils.RetryConfig.builder().initialRetryInterval(Duration.ofSeconds(30))
                        .maxRetryInterval(Duration.ofSeconds(30)).maxAttempt(CLIENT_RETRY_COUNT)
                        .retryableExceptions(Arrays.asList(DeviceConfigurationException.class)).build();

        try (GreengrassV2DataClient greengrasV2DataClient = RetryUtils.runWithRetry(clientExceptionRetryConfig, () -> {
            GreengrassV2DataClient client = clientFactory.getGreengrassV2DataClient();
            if (client == null) {
                String errorMessage = clientFactory.getConfigValidationError().isPresent()
                        ? clientFactory.getConfigValidationError().get() : "Could not get GreengrassV2DataClient";
                throw new DeviceConfigurationException(errorMessage);
            }
            return client;
        }, "get-greengrass-v2-data-client", logger)) {

            result = greengrasV2DataClient.resolveComponentCandidates(request);

        } catch (ResourceNotFoundException e) {
            logger.atDebug().kv("componentName", componentName).kv("versionRequirements", versionRequirements)
                    .log("No applicable version found in cloud registry", e);
            throw new NoAvailableComponentVersionException("No cloud component version satisfies the requirements.",
                    componentName, versionRequirements);
        }

        Validate.isTrue(
                result.resolvedComponentVersions() != null && result.resolvedComponentVersions().size() == 1,
                "Component service returns invalid response. It should have one resolved component version");
        return result.resolvedComponentVersions().get(0);
    }
}
