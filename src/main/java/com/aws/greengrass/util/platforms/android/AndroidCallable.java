/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import lombok.NonNull;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public abstract class AndroidCallable implements Callable {

    public AndroidCallable withOut(@NonNull final Consumer<CharSequence> o) {
        return this;
    }

    public AndroidCallable withErr(@NonNull final Consumer<CharSequence> o) {
        return this;
    }

    public AndroidCallable withEnv(@NonNull final Map<String, String> environment) {
        return this;
    }
}
