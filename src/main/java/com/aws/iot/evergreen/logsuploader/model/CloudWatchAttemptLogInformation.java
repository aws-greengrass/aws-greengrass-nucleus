/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.logsuploader.model;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Builder
@Data
@Getter
@Setter
public class CloudWatchAttemptLogInformation {
    private List<InputLogEvent> logEvents;
    @Builder.Default
    private Map<String, CloudWatchAttemptLogFileInformation> attemptLogFileInformationList = new HashMap<>();
    private String componentName;
}
