/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.spool;

import java.io.IOException;

public interface CloudMessageSpool {

    SpoolMessage getMessageById(long id);

    void removeMessageById(long id);

    void add(long id, SpoolMessage message) throws IOException;

    Iterable<Long> getAllMessageIds() throws IOException;

    void initializeSpooler() throws IOException;
}
