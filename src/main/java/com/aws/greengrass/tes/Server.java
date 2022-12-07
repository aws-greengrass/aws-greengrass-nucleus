/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.tes;

import java.io.IOException;

public interface Server {

    void start() throws IOException, InterruptedException;

    void stop();
}
