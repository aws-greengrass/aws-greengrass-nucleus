/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.e2e.util;

import com.aws.greengrass.util.platforms.Platform;
import lombok.AllArgsConstructor;

import java.io.IOException;

@AllArgsConstructor
public class NetworkUtilsLinux extends NetworkUtils {
    private static final String enableOption = "--insert";
    private static final String disableOption = "--delete";
    private static final String commandFormat= "sudo iptables %s OUTPUT -p tcp --dport %s -j REJECT && " +
            "sudo iptables %s INPUT -p tcp --sport %s -j REJECT";

    @Override
    public void disconnectMqtt() throws InterruptedException, IOException {
        modifyPolicy(enableOption, "connection-loss", MQTT_PORTS);
    }

    @Override
    public void recoverMqtt() throws InterruptedException, IOException {
        modifyPolicy(disableOption, "connection-recover", MQTT_PORTS);
    }

    @Override
    public void disconnectNetwork() throws InterruptedException, IOException {
        modifyPolicy(enableOption, "connection-loss", NETWORK_PORTS);
    }

    @Override
    public void recoverNetwork() throws InterruptedException, IOException {
        modifyPolicy(disableOption, "connection-recover", NETWORK_PORTS);
    }

    private void modifyPolicy(String option, String eventName, String... ports) throws InterruptedException,
            IOException {
        for (String port : ports) {
            logger.atWarn(eventName).kv("port", port).log(Platform.getInstance().createNewProcessRunner()
                    .sh(String.format(commandFormat, option, port, option, port)));
        }
    }
}
