/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.logsuploader.model;

import lombok.Builder;
import lombok.Getter;
import lombok.Value;

import java.io.File;
import java.util.Objects;

@Builder
@Value
@Getter
public class LogFileInformation {
    private File file;
    private long startPosition;

    @Override
    public int hashCode() {
        return Objects.hashCode(file.getAbsolutePath());
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }

        // Check if o is an instance of LogFileInformation or not "null instanceof [type]" also returns false
        if (!(o instanceof LogFileInformation)) {
            return false;
        }

        LogFileInformation logFileInformation = (LogFileInformation) o;
        return logFileInformation.file.getAbsolutePath().equals(file.getAbsolutePath());
    }
}
