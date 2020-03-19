/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.util;

import lombok.AllArgsConstructor;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.AttachPolicyRequest;
import software.amazon.awssdk.services.iot.model.AttachThingPrincipalRequest;
import software.amazon.awssdk.services.iot.model.CancelJobRequest;
import software.amazon.awssdk.services.iot.model.CertificateStatus;
import software.amazon.awssdk.services.iot.model.CreateJobRequest;
import software.amazon.awssdk.services.iot.model.CreateKeysAndCertificateRequest;
import software.amazon.awssdk.services.iot.model.CreateKeysAndCertificateResponse;
import software.amazon.awssdk.services.iot.model.CreatePolicyRequest;
import software.amazon.awssdk.services.iot.model.CreateThingRequest;
import software.amazon.awssdk.services.iot.model.DeleteCertificateRequest;
import software.amazon.awssdk.services.iot.model.DeleteJobRequest;
import software.amazon.awssdk.services.iot.model.DeleteThingRequest;
import software.amazon.awssdk.services.iot.model.DescribeEndpointRequest;
import software.amazon.awssdk.services.iot.model.DescribeJobRequest;
import software.amazon.awssdk.services.iot.model.DetachThingPrincipalRequest;
import software.amazon.awssdk.services.iot.model.GetPolicyRequest;
import software.amazon.awssdk.services.iot.model.InvalidRequestException;
import software.amazon.awssdk.services.iot.model.JobStatus;
import software.amazon.awssdk.services.iot.model.KeyPair;
import software.amazon.awssdk.services.iot.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iot.model.TargetSelection;
import software.amazon.awssdk.services.iot.model.TimeoutConfig;
import software.amazon.awssdk.services.iot.model.UpdateCertificateRequest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeoutException;

public class Utils {
    public static final IotClient iotClient = IotClient.builder().build();
    private static final String FULL_ACCESS_POLICY_NAME = "E2ETestFullAccess";
    private static final Set<ThingInfo> createdThings = new CopyOnWriteArraySet<>();
    private static final Set<String> createdJobs = new CopyOnWriteArraySet<>();
    private static final String ROOT_CA_URL = "https://www.amazontrust.com/repository/AmazonRootCA1.pem";

    public static String createJob(String document, String[] targets) {
        String jobId = UUID.randomUUID().toString();

        iotClient.createJob(
                CreateJobRequest.builder().jobId(jobId).targets(targets).targetSelection(TargetSelection.SNAPSHOT)
                        .document(document).description("E2E Test: " + new Date())
                        .timeoutConfig(TimeoutConfig.builder().inProgressTimeoutInMinutes(10L).build()).build());
        createdJobs.add(jobId);
        return jobId;
    }

    public static void waitForJobToComplete(String jobId, Duration timeout) throws TimeoutException {
        Instant start = Instant.now();

        while (start.plusMillis(timeout.toMillis()).isAfter(Instant.now())) {
            JobStatus status = iotClient.describeJob(DescribeJobRequest.builder().jobId(jobId).build()).job().status();
            if (status.ordinal() > JobStatus.IN_PROGRESS.ordinal()) {
                return;
            }
        }
        throw new TimeoutException();
    }

    public static ThingInfo createThing() {
        // Find or create IoT policy
        try {
            iotClient.getPolicy(GetPolicyRequest.builder().policyName(FULL_ACCESS_POLICY_NAME).build());
        } catch (ResourceNotFoundException e) {
            iotClient.createPolicy(CreatePolicyRequest.builder().policyName(FULL_ACCESS_POLICY_NAME).policyDocument(
                    "{\n  \"Version\": \"2012-10-17\",\n  \"Statement\": [\n    {\n"
                            + "      \"Effect\": \"Allow\",\n      \"Action\": \"iot:*\",\n"
                            + "      \"Resource\": \"*\"\n    }\n  ]\n}").build());
        }

        // Create cert
        CreateKeysAndCertificateResponse keyResponse =
                iotClient.createKeysAndCertificate(CreateKeysAndCertificateRequest.builder().setAsActive(true).build());

        // Attach policy to cert
        iotClient.attachPolicy(
                AttachPolicyRequest.builder().policyName(FULL_ACCESS_POLICY_NAME).target(keyResponse.certificateArn())
                        .build());

        // Create the thing and attach the cert to it
        String thingName = "e2etest-" + UUID.randomUUID().toString();
        String thingArn = iotClient.createThing(CreateThingRequest.builder().thingName(thingName).build()).thingArn();
        iotClient.attachThingPrincipal(
                AttachThingPrincipalRequest.builder().thingName(thingName).principal(keyResponse.certificateArn())
                        .build());

        ThingInfo info = new ThingInfo(thingArn, thingName, keyResponse.certificateArn(), keyResponse.certificateId(),
                keyResponse.certificatePem(), keyResponse.keyPair(),
                iotClient.describeEndpoint(DescribeEndpointRequest.builder().endpointType("iot:Data-ATS").build())
                        .endpointAddress());
        createdThings.add(info);
        return info;
    }

    public static void cleanAllCreatedThings() {
        createdThings.forEach(Utils::cleanThing);
    }

    public static void cleanAllCreatedJobs() {
        createdJobs.forEach(Utils::cleanJob);
    }

    public static void cleanThing(ThingInfo thing) {
        iotClient.detachThingPrincipal(
                DetachThingPrincipalRequest.builder().thingName(thing.thingName).principal(thing.certificateArn)
                        .build());
        iotClient.deleteThing(DeleteThingRequest.builder().thingName(thing.thingName).build());
        iotClient.updateCertificate(UpdateCertificateRequest.builder().certificateId(thing.certificateId)
                .newStatus(CertificateStatus.INACTIVE).build());
        iotClient.deleteCertificate(
                DeleteCertificateRequest.builder().certificateId(thing.certificateId).forceDelete(true).build());
        createdThings.remove(thing);
    }

    public static void cleanJob(String jobId) {
        try {
            iotClient.cancelJob(CancelJobRequest.builder().jobId(jobId).force(true).build());
        } catch (InvalidRequestException e) {
            // Ignore can't cancel due to job already completed
            if (!e.getMessage().contains("in status COMPLETED cannot")) {
                throw e;
            }
        }
        iotClient.deleteJob(DeleteJobRequest.builder().jobId(jobId).force(true).build());
        createdJobs.remove(jobId);
    }

    public static void downloadRootCAToFile(File f) throws IOException {
        downloadFileFromURL(ROOT_CA_URL, f);
    }

    public static void downloadFileFromURL(String url, File f) throws IOException {
        try (ReadableByteChannel readableByteChannel = Channels.newChannel(new URL(url).openStream());
             FileOutputStream fileOutputStream = new FileOutputStream(f)) {
            fileOutputStream.getChannel().transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
        }
    }

    @AllArgsConstructor
    public static class ThingInfo {
        public String thingArn;
        public String thingName;
        public String certificateArn;
        public String certificateId;
        public String certificatePem;
        public KeyPair keyPair;
        public String endpoint;
    }
}
