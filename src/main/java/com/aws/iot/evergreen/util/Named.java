/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
public @interface Named {
    String value();
}
