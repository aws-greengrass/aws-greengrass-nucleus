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

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ComponentsLogConfiguration extends CommonLogsConfiguration {
    @JsonProperty(value = "ComponentName", required = true)
    private String componentName;
    @JsonProperty(value = "LogFileRegex", required = true)
    private String logFileRegex;
    @JsonProperty(value = "LogFileDirectoryPath", required = true)
    private String logFileDirectoryPath;
    @JsonProperty(value = "MultiLineStartPattern", defaultValue = "\"^[^\\\\s]+(\\\\s+[^\\\\s]+)*$\"")
    private String multiLineStartPattern;
}
