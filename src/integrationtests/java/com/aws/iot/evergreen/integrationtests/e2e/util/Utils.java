/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.util;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.util.CommitableFile;
import com.aws.iot.evergreen.util.CrashableSupplier;
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
import software.amazon.awssdk.services.iot.model.DescribeJobExecutionRequest;
import software.amazon.awssdk.services.iot.model.DescribeJobRequest;
import software.amazon.awssdk.services.iot.model.DetachThingPrincipalRequest;
import software.amazon.awssdk.services.iot.model.GetPolicyRequest;
import software.amazon.awssdk.services.iot.model.InternalException;
import software.amazon.awssdk.services.iot.model.InternalFailureException;
import software.amazon.awssdk.services.iot.model.InvalidRequestException;
import software.amazon.awssdk.services.iot.model.IotException;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;
import software.amazon.awssdk.services.iot.model.JobStatus;
import software.amazon.awssdk.services.iot.model.KeyPair;
import software.amazon.awssdk.services.iot.model.LimitExceededException;
import software.amazon.awssdk.services.iot.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iot.model.TargetSelection;
import software.amazon.awssdk.services.iot.model.ThrottlingException;
import software.amazon.awssdk.services.iot.model.TimeoutConfig;
import software.amazon.awssdk.services.iot.model.UpdateCertificateRequest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeoutException;

import static com.aws.iot.evergreen.deployment.DeviceConfigurationHelper.DEVICE_PARAM_CERTIFICATE_FILE_PATH;
import static com.aws.iot.evergreen.deployment.DeviceConfigurationHelper.DEVICE_PARAM_MQTT_CLIENT_ENDPOINT;
import static com.aws.iot.evergreen.deployment.DeviceConfigurationHelper.DEVICE_PARAM_PRIVATE_KEY_PATH;
import static com.aws.iot.evergreen.deployment.DeviceConfigurationHelper.DEVICE_PARAM_ROOT_CA_PATH;
import static com.aws.iot.evergreen.deployment.DeviceConfigurationHelper.DEVICE_PARAM_THING_NAME;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;

public class Utils {
    public static final IotClient iotClient = IotClient.builder().build();
    private static final String FULL_ACCESS_POLICY_NAME = "E2ETestFullAccess";
    private static final Set<ThingInfo> createdThings = new CopyOnWriteArraySet<>();
    private static final Set<String> createdJobs = new CopyOnWriteArraySet<>();
    private static final String ROOT_CA_URL = "https://www.amazontrust.com/repository/AmazonRootCA1.pem";
    private static final int DEFAULT_RETRIES = 5;
    private static final int DEFAULT_INITIAL_BACKOFF_MS = 100;

    private Utils() {
    }

    public static String createJob(String document, String... targets) {
        String jobId = UUID.randomUUID().toString();
        createJobWithId(document, targets, jobId);
        return jobId;
    }

    public static void createJobWithId(String document, String[] targets, String jobId) {
        retryIot(() -> iotClient.createJob(
                CreateJobRequest.builder().jobId(jobId).targets(targets).targetSelection(TargetSelection.SNAPSHOT)
                        .document(document).description("E2E Test: " + new Date())
                        .timeoutConfig(TimeoutConfig.builder().inProgressTimeoutInMinutes(10L).build()).build()));
        createdJobs.add(jobId);
    }

    public static void waitForJobToComplete(String jobId, Duration timeout) throws TimeoutException {
        Instant start = Instant.now();

        while (start.plusMillis(timeout.toMillis()).isAfter(Instant.now())) {
            JobStatus status =
                    retryIot(() -> iotClient.describeJob(DescribeJobRequest.builder().jobId(jobId).build())).job()
                            .status();
            if (status.ordinal() > JobStatus.IN_PROGRESS.ordinal()) {
                return;
            }
            // Wait a little bit before checking again
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
        }
        throw new TimeoutException();
    }

    public static void waitForJobToReachExecutionStatus(String jobId, String thingName, Duration timeout,
                                                        JobExecutionStatus targetStatus) throws TimeoutException {
        Instant start = Instant.now();

        while (start.plusMillis(timeout.toMillis()).isAfter(Instant.now())) {
            JobExecutionStatus status = retryIot(() -> iotClient.describeJobExecution(
                    DescribeJobExecutionRequest.builder().jobId(jobId).thingName(thingName).build()))
                    .execution().status();
            if (status.ordinal() >= targetStatus.ordinal()) {
                return;
            }
            // Wait a little bit before checking again
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
        }
        throw new TimeoutException();
    }

    public static ThingInfo createThing() {
        // Find or create IoT policy
        try {
            retryIot(() -> iotClient.getPolicy(GetPolicyRequest.builder().policyName(FULL_ACCESS_POLICY_NAME).build()));
        } catch (ResourceNotFoundException e) {
            retryIot(() -> iotClient.createPolicy(CreatePolicyRequest.builder().policyName(FULL_ACCESS_POLICY_NAME)
                    .policyDocument("{\n  \"Version\": \"2012-10-17\",\n  \"Statement\": [\n    {\n"
                            + "      \"Effect\": \"Allow\",\n      \"Action\": [\n"
                            + "                \"iot:Connect\",\n                \"iot:Publish\",\n"
                            + "                \"iot:Subscribe\",\n                \"iot:Receive\"\n],\n"
                            + "      \"Resource\": \"*\"\n    }\n  ]\n}").build()));
        }

        // Create cert
        CreateKeysAndCertificateResponse keyResponse = retryIot(() -> iotClient
                .createKeysAndCertificate(CreateKeysAndCertificateRequest.builder().setAsActive(true).build()));

        // Attach policy to cert
        retryIot(() -> iotClient.attachPolicy(
                AttachPolicyRequest.builder().policyName(FULL_ACCESS_POLICY_NAME).target(keyResponse.certificateArn())
                        .build()));

        // Create the thing and attach the cert to it
        String thingName = "e2etest-" + UUID.randomUUID().toString();
        String thingArn =
                retryIot(() -> iotClient.createThing(CreateThingRequest.builder().thingName(thingName).build()))
                        .thingArn();
        retryIot(() -> iotClient.attachThingPrincipal(
                AttachThingPrincipalRequest.builder().thingName(thingName).principal(keyResponse.certificateArn())
                        .build()));

        ThingInfo info = new ThingInfo(thingArn, thingName, keyResponse.certificateArn(), keyResponse.certificateId(),
                keyResponse.certificatePem(), keyResponse.keyPair(), retryIot(() -> iotClient
                .describeEndpoint(DescribeEndpointRequest.builder().endpointType("iot:Data-ATS").build()))
                .endpointAddress());
        createdThings.add(info);
        return info;
    }

    public static ThingInfo setupIotResourcesAndInjectIntoKernel(Kernel kernel, Path tempRootDir) throws IOException {
        String rootCaFilePath = tempRootDir.resolve("rootCA.pem").toString();
        String privateKeyFilePath = tempRootDir.resolve("privKey.key").toString();
        String certificateFilePath = tempRootDir.resolve("thingCert.crt").toString();

        downloadRootCAToFile(new File(rootCaFilePath));
        ThingInfo thing = createThing();
        try (CommitableFile cf = CommitableFile.of(new File(privateKeyFilePath).toPath(), true)) {
            cf.write(thing.keyPair.privateKey().getBytes(StandardCharsets.UTF_8));
        }
        try (CommitableFile cf = CommitableFile.of(new File(certificateFilePath).toPath(), true)) {
            cf.write(thing.certificatePem.getBytes(StandardCharsets.UTF_8));
        }

        Topics deploymentServiceTopics = kernel.lookupTopics(SERVICES_NAMESPACE_TOPIC, "DeploymentService");
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_THING_NAME).withValue(thing.thingName);
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_MQTT_CLIENT_ENDPOINT).withValue(thing.endpoint);
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_PRIVATE_KEY_PATH).withValue(privateKeyFilePath);
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_CERTIFICATE_FILE_PATH).withValue(certificateFilePath);
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_ROOT_CA_PATH).withValue(rootCaFilePath);
        return thing;
    }

    public static void cleanAllCreatedThings() {
        createdThings.forEach(Utils::cleanThing);
    }

    public static void cleanAllCreatedJobs() {
        createdJobs.forEach(Utils::cleanJob);
    }

    public static void cleanThing(ThingInfo thing) {
        retryIot(() -> iotClient.detachThingPrincipal(
                DetachThingPrincipalRequest.builder().thingName(thing.thingName).principal(thing.certificateArn)
                        .build()));
        retryIot(() -> iotClient.deleteThing(DeleteThingRequest.builder().thingName(thing.thingName).build()));
        retryIot(() -> iotClient.updateCertificate(UpdateCertificateRequest.builder().certificateId(thing.certificateId)
                .newStatus(CertificateStatus.INACTIVE).build()));
        retryIot(() -> iotClient.deleteCertificate(
                DeleteCertificateRequest.builder().certificateId(thing.certificateId).forceDelete(true).build()));
        createdThings.remove(thing);
    }

    public static void cleanJob(String jobId) {
        try {
            retryIot(() -> iotClient.cancelJob(CancelJobRequest.builder().jobId(jobId).force(true).build()));
        } catch (InvalidRequestException e) {
            // Ignore can't cancel due to job already completed
            if (!e.getMessage().contains("in status COMPLETED cannot")) {
                throw e;
            }
        }
        retryIot(() -> iotClient.deleteJob(DeleteJobRequest.builder().jobId(jobId).force(true).build()));
        createdJobs.remove(jobId);
    }

    public static void downloadRootCAToFile(File f) throws IOException {
        downloadFileFromURL(ROOT_CA_URL, f);
    }

    @SuppressWarnings("PMD.AvoidFileStream")
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

    public static <T, E extends IotException> T retryIot(CrashableSupplier<T, E> func) {
        return retry(DEFAULT_RETRIES, DEFAULT_INITIAL_BACKOFF_MS, func, ThrottlingException.class,
                InternalException.class, InternalFailureException.class, LimitExceededException.class,
                ResourceNotFoundException.class);
    }

    @SuppressWarnings({"PMD.AssignmentInOperand", "PMD.AvoidCatchingThrowable"})
    @SafeVarargs
    public static <T, E extends Throwable> T retry(int tries, int initialBackoffMillis, CrashableSupplier<T, E> func,
                                                   Class<? extends Throwable>... retryableExceptions) throws E {
        E lastException = null;
        int tryCount = 0;
        while (tryCount++ < tries) {
            try {
                return func.apply();
            } catch (Throwable e) {
                boolean retryable = false;
                lastException = (E) e;

                for (Class<?> t : retryableExceptions) {
                    if (t.isAssignableFrom(e.getClass())) {
                        retryable = true;
                        break;
                    }
                }

                // If not retryable, immediately throw it
                if (!retryable) {
                    throw lastException;
                }

                // Sleep with backoff
                try {
                    Thread.sleep(initialBackoffMillis * tryCount);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        throw lastException;
    }
}
