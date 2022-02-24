/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.provision;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;

public interface ProvisionManager {

    @Nullable
    JsonNode parseFile(@NonNull Context context, @NonNull Uri sourceUri);

    boolean isProvisioned(@NonNull Context context);

    void clearProvision(@NonNull Context context);

    void setupSystemProperties(@NonNull JsonNode config) throws Exception;

    @NonNull
    ArrayList<String> generateArgs(@NonNull JsonNode config);
}
