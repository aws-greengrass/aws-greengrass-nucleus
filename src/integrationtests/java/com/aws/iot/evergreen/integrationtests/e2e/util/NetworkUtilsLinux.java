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
    private static final String commandFormat= "sudo iptables %s OUTPUT -p tcp --dport %s -j REJECT && " +
            "sudo iptables %s INPUT -p tcp --sport %s -j REJECT";

    @Override
    public void disconnectMqtt() throws InterruptedException {
        modifyPolicy(enableOption, "connection-loss", MQTT_PORTS);
    }

    @Override
    public void recoverMqtt() throws InterruptedException {
        modifyPolicy(disableOption, "connection-recover", MQTT_PORTS);
    }

    private void modifyPolicy(String option, String eventName, String... ports) throws InterruptedException {
        for (String port : ports) {
            logger.atWarn(eventName).kv("port", port).log(Exec.sh(String.format(commandFormat, option, port, option,
                    port)));
        }
    }
}
