/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.util;

import com.aws.iot.evergreen.util.Exec;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class NetworkUtilsLinux extends NetworkUtils {
    private static final String enableOption = "--insert";
    private static final String disableOption = "--delete";
    private static final String commandFormat= "sudo iptables %s OUTPUT -p tcp -dport %s -j REJECT && " +
            "sudo iptables %s INPUT -p tcp -sport %s -j REJECT";

    @Override
    public void disconnect() throws InterruptedException {
        logger.info("Simulate connection loss");
        modifyPolicy(enableOption);
    }

    @Override
    public void recover() throws InterruptedException {
        logger.info("Recover from connection loss");
        modifyPolicy(disableOption);
    }

    private void modifyPolicy(String option) throws InterruptedException {
        for (String port : MQTT_PORTS) {
            logger.info(Exec.sh(String.format(commandFormat, option, port, option, port)));
        }
    }
}
