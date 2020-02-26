/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.deployment.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Getter;

import java.util.List;

@Getter
@JsonSerialize
public class AwsIotJobsMqttMessage {
    private Long timestamp;
    private Jobs jobs;

    @JsonSerialize
    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Jobs {
        @JsonProperty("QUEUED")
        private List<JobDetails> queued;

        @JsonProperty("IN_PROGRESS")
        private List<JobDetails> inProgress;
    }

    @JsonSerialize
    @Getter
    public static class JobDetails {
        @SuppressFBWarnings(value = "UWF_UNWRITTEN_FIELD", justification = "Might use it in future iterations")
        private String jobId;
    }
}

