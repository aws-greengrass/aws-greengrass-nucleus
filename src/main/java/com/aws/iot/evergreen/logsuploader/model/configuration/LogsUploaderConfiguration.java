/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.logsuploader.model.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LogsUploaderConfiguration {
    @JsonProperty(value = "ComponentLogInformation",required = false)
    private List<ComponentsLogConfiguration> componentLogInformation;
    @JsonProperty(value = "SystemLogsConfiguration", required = false)
    private SystemLogsConfiguration systemLogConfiguration;
}
