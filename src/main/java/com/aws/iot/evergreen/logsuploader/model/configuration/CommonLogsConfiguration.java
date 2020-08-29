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
public class CommonLogsConfiguration {
    @JsonProperty(value = "MinimumLogLevel", defaultValue = "INFO")
    private String minimumLogLevel;
    @JsonProperty("DiskSpaceLimit")
    private String diskSpaceLimit;
    @JsonProperty("DiskSpaceLimitUnit")
    private String diskSpaceLimitUnit;
    @JsonProperty(value = "DeleteLogFileAfterCloudUpload", defaultValue = "false")
    private String deleteLogFileAfterCloudUpload;
}
