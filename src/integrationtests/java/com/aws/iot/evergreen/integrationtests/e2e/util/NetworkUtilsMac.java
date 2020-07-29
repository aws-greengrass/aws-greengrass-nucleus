/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.util;

import com.aws.iot.evergreen.util.Exec;
import lombok.AllArgsConstructor;

import java.io.IOException;

@AllArgsConstructor
public class NetworkUtilsMac extends NetworkUtils {
    private static final String commandFormat = "sudo ifconfig en0 %s";
    private static final String downOperation = "down";
    private static final String upOperation = "up";

    @Override
    public void disconnectMqtt() throws InterruptedException, IOException {
        logger.atWarn("connection-loss").log(Exec.sh(String.format(commandFormat, downOperation)));
    }

    @Override
    public void recoverMqtt() throws InterruptedException, IOException {
        logger.atWarn("connection-recover").log(Exec.sh(String.format(commandFormat, upOperation)));
    }

    @Override
    public void disconnectNetwork() throws InterruptedException, IOException {
        logger.atWarn("connection-loss").log(Exec.sh(String.format(commandFormat, downOperation)));
    }

    @Override
    public void recoverNetwork() throws InterruptedException, IOException {
        logger.atWarn("connection-recover").log(Exec.sh(String.format(commandFormat, upOperation)));
    }
}
