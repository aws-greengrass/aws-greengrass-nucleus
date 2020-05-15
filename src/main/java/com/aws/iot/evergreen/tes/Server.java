/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.tes;

import java.io.IOException;

public interface Server {

    void start() throws IOException;

    void stop();
}
