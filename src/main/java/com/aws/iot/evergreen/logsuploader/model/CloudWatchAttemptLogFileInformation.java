/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.logsuploader.model;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Builder
@Data
@Getter
@Setter
public class CloudWatchAttemptLogFileInformation {
    private long startPosition;
    private long bytesRead;
}
