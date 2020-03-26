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
import software.amazon.awssdk.services.iot.model.AddThingToThingGroupRequest;
import software.amazon.awssdk.services.iot.model.AttachPolicyRequest;
import software.amazon.awssdk.services.iot.model.AttachThingPrincipalRequest;
import software.amazon.awssdk.services.iot.model.CancelJobRequest;
import software.amazon.awssdk.services.iot.model.CertificateStatus;
import software.amazon.awssdk.services.iot.model.CreateJobRequest;
import software.amazon.awssdk.services.iot.model.CreateKeysAndCertificateRequest;
import software.amazon.awssdk.services.iot.model.CreateKeysAndCertificateResponse;
import software.amazon.awssdk.services.iot.model.CreatePolicyRequest;
import software.amazon.awssdk.services.iot.model.CreateThingGroupRequest;
import software.amazon.awssdk.services.iot.model.CreateThingGroupResponse;
import software.amazon.awssdk.services.iot.model.CreateThingRequest;
import software.amazon.awssdk.services.iot.model.DeleteCertificateRequest;
import software.amazon.awssdk.services.iot.model.DeleteJobRequest;
import software.amazon.awssdk.services.iot.model.DeleteThingGroupRequest;
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
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import static com.aws.iot.evergreen.deployment.DeviceConfigurationHelper.DEVICE_PARAM_CERTIFICATE_FILE_PATH;
import static com.aws.iot.evergreen.deployment.DeviceConfigurationHelper.DEVICE_PARAM_MQTT_CLIENT_ENDPOINT;
import static com.aws.iot.evergreen.deployment.DeviceConfigurationHelper.DEVICE_PARAM_PRIVATE_KEY_PATH;
import static com.aws.iot.evergreen.deployment.DeviceConfigurationHelper.DEVICE_PARAM_ROOT_CA_PATH;
import static com.aws.iot.evergreen.deployment.DeviceConfigurationHelper.DEVICE_PARAM_THING_NAME;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;

public class Utils {
    public static IotClient iotClient = IotClient.builder().build();

    private static final String FULL_ACCESS_POLICY_NAME = "E2ETestFullAccess";
    private static final Map<IotClient, Set<ThingInfo>> createdThingsMap = new ConcurrentHashMap<>();
    private static final Map<IotClient, Set<String>> createdThingGroupsMap = new ConcurrentHashMap<>();
    private static final Map<IotClient, Set<String>> createdJobsMap = new ConcurrentHashMap<>();
    private static final String ROOT_CA_URL = "https://www.amazontrust.com/repository/AmazonRootCA1.pem";
    private static final int DEFAULT_RETRIES = 5;
    private static final int DEFAULT_INITIAL_BACKOFF_MS = 100;
    private static final Set<Class<? extends Throwable>> retryableIoTExceptions = new HashSet<>(Arrays.asList(
            ThrottlingException.class, InternalException.class, InternalFailureException.class,
            LimitExceededException.class));

    private Utils() {
    }

    public static String createJob(String document, String... targets) {
        String jobId = UUID.randomUUID().toString();
        createJobWithId(iotClient, document, jobId, targets);
        return jobId;
    }

    public static void createJobWithId(String document, String jobId, String... targets) {
        createJobWithId(iotClient, document, jobId, targets);
    }

    public static void createJobWithId(IotClient client, String document, String jobId, String... targets) {
        retryIot(() -> iotClient.createJob(
                CreateJobRequest.builder().jobId(jobId).targets(targets).targetSelection(TargetSelection.SNAPSHOT)
                        .document(document).description("E2E Test: " + new Date())
                        .timeoutConfig(TimeoutConfig.builder().inProgressTimeoutInMinutes(10L).build()).build()));

        createdJobsMap.compute(client, (k, existingJobs) -> {
            if (existingJobs == null) {
                return new CopyOnWriteArraySet<>(Collections.singleton(jobId));
            } else {
                existingJobs.add(jobId);
                return existingJobs;
            }
        });
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

    public static void waitForJobExecutionStatusToSatisfy(String jobId, String thingName, Duration timeout,
                                                          Predicate<JobExecutionStatus> condition)
            throws TimeoutException {
        waitForJobExecutionStatusToSatisfy(iotClient, jobId, thingName, timeout, condition);
    }

    public static void waitForJobExecutionStatusToSatisfy(IotClient client, String jobId, String thingName,
                                                          Duration timeout, Predicate<JobExecutionStatus> condition)
            throws TimeoutException {
        Instant start = Instant.now();
        Set<Class<? extends Throwable>> retryableExceptions = new HashSet<>(Arrays.asList(ResourceNotFoundException.class));
        retryableExceptions.addAll(retryableIoTExceptions);

        while (start.plusMillis(timeout.toMillis()).isAfter(Instant.now())) {
            JobExecutionStatus status = retry(DEFAULT_RETRIES, 1000, () -> client.describeJobExecution(
                    DescribeJobExecutionRequest.builder().jobId(jobId).thingName(thingName).build()),
                    retryableExceptions).execution().status();
            if (condition.test(status)) {
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

    public static String createThingGroupAndAddThing(ThingInfo thingInfo) {
        return createThingGroupAndAddThing(iotClient, thingInfo);
    }

    public static String createThingGroupAndAddThing(IotClient client, ThingInfo thingInfo) {
        String thingGroupName = "e2etestgroup-" + UUID.randomUUID().toString();
        CreateThingGroupResponse response = client.createThingGroup(CreateThingGroupRequest.builder()
                .thingGroupName(thingGroupName)
                .build());

        client.addThingToThingGroup(AddThingToThingGroupRequest.builder()
                .thingArn(thingInfo.thingArn)
                .thingGroupArn(response.thingGroupArn()).build());

        createdThingGroupsMap.compute(client, (key, existingThingGroups) -> {
            if (existingThingGroups == null) {
                return new CopyOnWriteArraySet<>(Collections.singletonList(response.thingGroupName()));
            } else {
                existingThingGroups.add(response.thingGroupName());
                return existingThingGroups;
            }
        });

        return response.thingGroupArn();
    }

    public static ThingInfo createThing() {
        return createThing(iotClient);
    }

    public static ThingInfo createThing(IotClient client) {
        // Find or create IoT policy
        try {
            retryIot(() -> client.getPolicy(GetPolicyRequest.builder().policyName(FULL_ACCESS_POLICY_NAME).build()));
        } catch (ResourceNotFoundException e) {
            retryIot(() -> client.createPolicy(CreatePolicyRequest.builder().policyName(FULL_ACCESS_POLICY_NAME)
                    .policyDocument("{\n  \"Version\": \"2012-10-17\",\n  \"Statement\": [\n    {\n"
                            + "      \"Effect\": \"Allow\",\n      \"Action\": [\n"
                            + "                \"iot:Connect\",\n                \"iot:Publish\",\n"
                            + "                \"iot:Subscribe\",\n                \"iot:Receive\"\n],\n"
                            + "      \"Resource\": \"*\"\n    }\n  ]\n}").build()));
        }

        // Create cert
        CreateKeysAndCertificateResponse keyResponse = retryIot(() -> client
                .createKeysAndCertificate(CreateKeysAndCertificateRequest.builder().setAsActive(true).build()));

        // Attach policy to cert
        retryIot(() -> client.attachPolicy(
                AttachPolicyRequest.builder().policyName(FULL_ACCESS_POLICY_NAME).target(keyResponse.certificateArn())
                        .build()));

        // Create the thing and attach the cert to it
        String thingName = "e2etest-" + UUID.randomUUID().toString();
        String thingArn =
                retryIot(() -> client.createThing(CreateThingRequest.builder().thingName(thingName).build()))
                        .thingArn();
        retryIot(() -> client.attachThingPrincipal(
                AttachThingPrincipalRequest.builder().thingName(thingName).principal(keyResponse.certificateArn())
                        .build()));

        ThingInfo info = new ThingInfo(thingArn, thingName, keyResponse.certificateArn(), keyResponse.certificateId(),
                keyResponse.certificatePem(), keyResponse.keyPair(), retryIot(() -> client
                .describeEndpoint(DescribeEndpointRequest.builder().endpointType("iot:Data-ATS").build()))
                .endpointAddress());
        createdThingsMap.compute(client, (k, existingThings) -> {
            if (existingThings == null) {
                return new CopyOnWriteArraySet<>(Collections.singleton(info));
            } else {
                existingThings.add(info);
                return existingThings;
            }
        });
        return info;
    }

    public static void cleanAllCreatedThings() {
        cleanAllCreatedThings(iotClient);
    }

    public static void cleanAllCreatedThings(IotClient client) {
        Set<ThingInfo> createdThingsInAccount = createdThingsMap.get(client);
        if (createdThingsInAccount == null) {
            return;
        }
        createdThingsInAccount.forEach(info -> cleanThing(client, info));
    }

    public static void cleanAllCreatedJobs() {
        cleanAllCreatedJobs(iotClient);
    }

    public static void cleanAllCreatedJobs(IotClient client) {
        Set<String> createdJobs = createdJobsMap.get(client);
        if (createdJobs != null) {
            createdJobs.forEach(jobId -> cleanJob(client, jobId));
        }
    }

    public static void cleanAllCreatedThingGroups() {
        cleanAllCreatedThingGroups(iotClient);
    }

    public static void cleanAllCreatedThingGroups(IotClient client) {
        Set<String> createdThingGroups = createdThingGroupsMap.get(client);
        if (createdThingGroups == null) {
            return;
        }
        for (String thingGroupName: createdThingGroups) {
            retryIot(() -> client.deleteThingGroup(DeleteThingGroupRequest.builder()
                .thingGroupName(thingGroupName).build()));
        }
    }

    public static void cleanThing(ThingInfo thing) {
        cleanThing(iotClient, thing);
    }

    public static void cleanThing(IotClient client, ThingInfo thing) {
        retryIot(() -> client.detachThingPrincipal(
                DetachThingPrincipalRequest.builder().thingName(thing.thingName).principal(thing.certificateArn)
                        .build()));
        retryIot(() -> client.deleteThing(DeleteThingRequest.builder().thingName(thing.thingName).build()));
        retryIot(() -> client.updateCertificate(UpdateCertificateRequest.builder().certificateId(thing.certificateId)
                .newStatus(CertificateStatus.INACTIVE).build()));
        retryIot(() -> client.deleteCertificate(
                DeleteCertificateRequest.builder().certificateId(thing.certificateId).forceDelete(true).build()));
        createdThingsMap.getOrDefault(client, new HashSet<>()).remove(thing);
    }

    public static void cleanJob(IotClient client, String jobId) {
        try {
            retryIot(() -> client.cancelJob(CancelJobRequest.builder().jobId(jobId).force(true).build()));
        } catch (InvalidRequestException e) {
            // Ignore can't cancel due to job already completed
            if (!e.getMessage().contains("in status COMPLETED cannot")) {
                throw e;
            }
        }
        retryIot(() -> client.deleteJob(DeleteJobRequest.builder().jobId(jobId).force(true).build()));
        createdJobsMap.getOrDefault(client, new HashSet<>()).remove(jobId);
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
        return retry(DEFAULT_RETRIES, DEFAULT_INITIAL_BACKOFF_MS, func, retryableIoTExceptions);
    }

    @SuppressWarnings({"PMD.AssignmentInOperand", "PMD.AvoidCatchingThrowable"})
    public static <T, E extends Throwable> T retry(int tries, int initialBackoffMillis, CrashableSupplier<T, E> func,
                                                   Iterable<Class<? extends Throwable>> retryableExceptions) throws E {
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

    // Update the kernel config with iot thing info, in specific CA, private Key and cert path.
    public static void updateKernelConfigWithIotConfiguration(Kernel kernel, Utils.ThingInfo thing) throws IOException {
        Path rootDir = kernel.getRootPath();
        String caFilePath = rootDir.resolve("rootCA.pem").toString();
        String privKeyFilePath = rootDir.resolve("privKey.key").toString();
        String certFilePath = rootDir.resolve("thingCert.crt").toString();

        Utils.downloadRootCAToFile(new File(caFilePath));
        try (CommitableFile cf = CommitableFile.of(new File(privKeyFilePath).toPath(), true)) {
            cf.write(thing.keyPair.privateKey().getBytes(StandardCharsets.UTF_8));
        }
        try (CommitableFile cf = CommitableFile.of(new File(certFilePath).toPath(), true)) {
            cf.write(thing.certificatePem.getBytes(StandardCharsets.UTF_8));
        }

        Topics deploymentServiceTopics = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, "DeploymentService");
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_THING_NAME).withValue(thing.thingName);
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_MQTT_CLIENT_ENDPOINT).withValue(thing.endpoint);
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_PRIVATE_KEY_PATH).withValue(privKeyFilePath);
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_CERTIFICATE_FILE_PATH).withValue(certFilePath);
        deploymentServiceTopics.createLeafChild(DEVICE_PARAM_ROOT_CA_PATH).withValue(caFilePath);
    }
}
