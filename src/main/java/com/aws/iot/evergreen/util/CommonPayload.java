/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import java.util.List;

public interface CommonPayload<T> {
    void setVariablePayload(List<T> variablePayload);
}
