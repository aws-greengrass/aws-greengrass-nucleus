/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.util.Pair;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.EnumSerializer;
import software.amazon.awssdk.iot.Timestamp;
import software.amazon.awssdk.iot.iotjobs.IotJobsClient;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionRequest;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionResponse;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionSubscriptionRequest;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionsChangedEvent;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionsChangedSubscriptionRequest;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;
import software.amazon.awssdk.iot.iotjobs.model.RejectedError;
import software.amazon.awssdk.iot.iotjobs.model.RejectedErrorCode;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionRequest;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionResponse;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionSubscriptionRequest;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * IotJobsClient with updated MQTT Jobs topics.
 */
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@SuppressFBWarnings("NM_METHOD_NAMING_CONVENTION")
public class IotJobsClientWrapper extends IotJobsClient {
    private static final String UPDATE_JOB_TOPIC =
            "$aws/things/%s/jobs/%s/namespace-aws-gg-deployment/update";
    static final String JOB_UPDATE_ACCEPTED_TOPIC =
            "$aws/things/%s/jobs/%s/namespace-aws-gg-deployment/update/accepted";
    static final String JOB_UPDATE_REJECTED_TOPIC =
            "$aws/things/%s/jobs/%s/namespace-aws-gg-deployment/update/rejected";
    private static final String DESCRIBE_JOB_TOPIC =
            "$aws/things/%s/jobs/%s/namespace-aws-gg-deployment/get";
    private static final String JOB_DESCRIBE_ACCEPTED_TOPIC =
            "$aws/things/%s/jobs/%s/namespace-aws-gg-deployment/get/accepted";
    private static final String JOB_DESCRIBE_REJECTED_TOPIC =
            "$aws/things/%s/jobs/%s/namespace-aws-gg-deployment/get/rejected";
    private static final String JOB_EXECUTIONS_CHANGED_TOPIC =
            "$aws/things/%s/jobs/notify-namespace-aws-gg-deployment";

    private final MqttClientConnection connection;
    private final Gson gson = this.getGson();
    private final Map<Pair<Consumer<UpdateJobExecutionResponse>, Consumer<Exception>>, Consumer<MqttMessage>>
            updateJobExecutionCbs = new ConcurrentHashMap<>();
    private final Map<Pair<Consumer<RejectedError>, Consumer<Exception>>, Consumer<MqttMessage>>
            updateJobExecutionSubscriptionCbs = new ConcurrentHashMap<>();
    private final Map<Pair<Consumer<DescribeJobExecutionResponse>, Consumer<Exception>>, Consumer<MqttMessage>>
            describeJobCbs = new ConcurrentHashMap<>();
    private final Map<Pair<Consumer<RejectedError>, Consumer<Exception>>, Consumer<MqttMessage>>
            describeJobSubscriptionCbs = new ConcurrentHashMap<>();
    private final Map<Pair<Consumer<JobExecutionsChangedEvent>, Consumer<Exception>>, Consumer<MqttMessage>>
            jobExecutionCbs = new ConcurrentHashMap<>();

    public IotJobsClientWrapper(MqttClientConnection connection) {
        super(connection);
        this.connection = connection;
    }

    private Gson getGson() {
        GsonBuilder gson = new GsonBuilder();
        gson.disableHtmlEscaping();
        gson.registerTypeAdapter(Timestamp.class, new Timestamp.Serializer());
        gson.registerTypeAdapter(Timestamp.class, new Timestamp.Deserializer());
        this.addTypeAdapters(gson);
        return gson.create();
    }

    private void addTypeAdapters(GsonBuilder gson) {
        gson.registerTypeAdapter(JobStatus.class, new EnumSerializer());
        gson.registerTypeAdapter(RejectedErrorCode.class, new EnumSerializer());
    }

    @Override
    public CompletableFuture<Integer> PublishUpdateJobExecution(UpdateJobExecutionRequest request,
                                                                QualityOfService qos) {
        if (request.thingName == null || request.jobId == null) {
            CompletableFuture result = new CompletableFuture();
            result.completeExceptionally(new MqttException(
                    "UpdateJobExecutionRequest must have a non-null thingName and a non-null jobId"));
            return result;
        }
        String topic = String.format(UPDATE_JOB_TOPIC, request.thingName, request.jobId);
        String payloadJson = this.gson.toJson(request);
        MqttMessage message = new MqttMessage(topic, payloadJson.getBytes(StandardCharsets.UTF_8));
        return this.connection.publish(message, qos, false);
    }

    @Override
    public CompletableFuture<Integer> SubscribeToUpdateJobExecutionAccepted(
            UpdateJobExecutionSubscriptionRequest request, QualityOfService qos,
            Consumer<UpdateJobExecutionResponse> handler, Consumer<Exception> exceptionHandler) {
        if (request.jobId == null || request.thingName == null) {
            CompletableFuture result = new CompletableFuture();
            result.completeExceptionally(new MqttException(
                    "UpdateJobExecutionSubscriptionRequest must have a non-null jobId and a non-null thingName"));
            return result;
        }
        String topic = String.format(JOB_UPDATE_ACCEPTED_TOPIC, request.thingName, request.jobId);
        Consumer<MqttMessage> messageHandler =
                updateJobExecutionCbs.computeIfAbsent(new Pair<>(handler, exceptionHandler), (k) -> (message) -> {
                    try {
                        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                        UpdateJobExecutionResponse response =
                                this.gson.fromJson(payload, UpdateJobExecutionResponse.class);
                        handler.accept(response);
                    } catch (Exception e) {
                        if (exceptionHandler != null) {
                            exceptionHandler.accept(e);
                        }
                    }
                });
        return this.connection.subscribe(topic, qos, messageHandler);
    }

    @Override
    public CompletableFuture<Integer> SubscribeToUpdateJobExecutionRejected(
            UpdateJobExecutionSubscriptionRequest request, QualityOfService qos, Consumer<RejectedError> handler,
            Consumer<Exception> exceptionHandler) {
        if (request.jobId == null || request.thingName == null) {
            CompletableFuture result = new CompletableFuture();
            result.completeExceptionally(new MqttException(
                    "UpdateJobExecutionSubscriptionRequest must have a non-null jobId and a non-null thingName"));
            return result;
        }
        String topic = String.format(JOB_UPDATE_REJECTED_TOPIC, request.thingName, request.jobId);
        Consumer<MqttMessage> messageHandler = updateJobExecutionSubscriptionCbs
                .computeIfAbsent(new Pair<>(handler, exceptionHandler), (k) -> (message) -> {
                    try {
                        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                        RejectedError response = this.gson.fromJson(payload, RejectedError.class);
                        handler.accept(response);
                    } catch (Exception e) {
                        if (exceptionHandler != null) {
                            exceptionHandler.accept(e);
                        }
                    }
                });
        return this.connection.subscribe(topic, qos, messageHandler);
    }

    @Override
    public CompletableFuture<Integer> PublishDescribeJobExecution(DescribeJobExecutionRequest request,
                                                                  QualityOfService qos) {
        if (request.thingName == null || request.jobId == null) {
            CompletableFuture result = new CompletableFuture();
            result.completeExceptionally(new MqttException(
                    "DescribeJobExecutionRequest must have a non-null thingName and a non-null jobId"));
            return result;
        }
        String topic = String.format(DESCRIBE_JOB_TOPIC, request.thingName, request.jobId);
        String payloadJson = this.gson.toJson(request);
        MqttMessage message = new MqttMessage(topic, payloadJson.getBytes(StandardCharsets.UTF_8));
        return this.connection.publish(message, qos, false);
    }

    @Override
    public CompletableFuture<Integer> SubscribeToDescribeJobExecutionAccepted(
            DescribeJobExecutionSubscriptionRequest request, QualityOfService qos,
            Consumer<DescribeJobExecutionResponse> handler, Consumer<Exception> exceptionHandler) {

        if (request.jobId == null || request.thingName == null) {
            CompletableFuture result = new CompletableFuture();
            result.completeExceptionally(new MqttException(
                    "DescribeJobExecutionSubscriptionRequest must have a non-null jobId and a non-null thingName"));
            return result;
        }
        String topic = String.format(JOB_DESCRIBE_ACCEPTED_TOPIC, request.thingName, request.jobId);
        Consumer<MqttMessage> messageHandler =
                describeJobCbs.computeIfAbsent(new Pair<>(handler, exceptionHandler), (k) -> (message) -> {
                    try {
                        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                        DescribeJobExecutionResponse response =
                                this.gson.fromJson(payload, DescribeJobExecutionResponse.class);
                        handler.accept(response);
                    } catch (Exception e) {
                        if (exceptionHandler != null) {
                            exceptionHandler.accept(e);
                        }
                    }
                });
        return this.connection.subscribe(topic, qos, messageHandler);
    }

    @Override
    public CompletableFuture<Integer> SubscribeToDescribeJobExecutionRejected(
            DescribeJobExecutionSubscriptionRequest request, QualityOfService qos, Consumer<RejectedError> handler,
            Consumer<Exception> exceptionHandler) {

        if (request.jobId == null || request.thingName == null) {
            CompletableFuture result = new CompletableFuture();
            result.completeExceptionally(new MqttException(
                    "DescribeJobExecutionSubscriptionRequest must have a non-null jobId and a non-null thingName"));
            return result;
        }
        String topic = String.format(JOB_DESCRIBE_REJECTED_TOPIC, request.thingName, request.jobId);
        Consumer<MqttMessage> messageHandler =
                describeJobSubscriptionCbs.computeIfAbsent(new Pair<>(handler, exceptionHandler), (k) -> (message) -> {
                    try {
                        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                        RejectedError response = this.gson.fromJson(payload, RejectedError.class);
                        handler.accept(response);
                    } catch (Exception e) {
                        if (exceptionHandler != null) {
                            exceptionHandler.accept(e);
                        }
                    }
                });
        return this.connection.subscribe(topic, qos, messageHandler);
    }

    @Override
    public CompletableFuture<Integer> SubscribeToJobExecutionsChangedEvents(
            JobExecutionsChangedSubscriptionRequest request, QualityOfService qos,
            Consumer<JobExecutionsChangedEvent> handler, Consumer<Exception> exceptionHandler) {

        if (request.thingName == null) {
            CompletableFuture<Integer> result = new CompletableFuture();
            result.completeExceptionally(new MqttException(
                    "JobExecutionsChangedSubscriptionRequest must have a non-null thingName"));
            return result;
        }

        String topic = String.format(JOB_EXECUTIONS_CHANGED_TOPIC, request.thingName);
        Consumer<MqttMessage> messageHandler =
                jobExecutionCbs.computeIfAbsent(new Pair<>(handler, exceptionHandler), (k) -> (message) -> {
                    try {
                        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                        JobExecutionsChangedEvent response =
                                this.gson.fromJson(payload, JobExecutionsChangedEvent.class);
                        handler.accept(response);
                    } catch (Exception e) {
                        if (exceptionHandler != null) {
                            exceptionHandler.accept(e);
                        }
                    }
                });
        return this.connection.subscribe(topic, qos, messageHandler);
    }
}
