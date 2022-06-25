/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import java.util.List;

public interface Chunkable<T> {
    void setVariablePayload(List<T> variablePayload);

    void setChunkInfo(int id, int totalChunks);

}
