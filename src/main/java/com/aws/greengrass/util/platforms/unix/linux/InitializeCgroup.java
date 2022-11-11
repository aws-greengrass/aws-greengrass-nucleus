/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import java.io.IOException;

@FunctionalInterface
public interface InitializeCgroup {
    void add() throws IOException;
}
