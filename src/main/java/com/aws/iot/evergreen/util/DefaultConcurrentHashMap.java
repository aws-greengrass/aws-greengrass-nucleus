/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A ConcurrentHashMap with default values when using {@code get()}.
 * Similar to DefaultDict in Python.
 */
public class DefaultConcurrentHashMap<K, V> extends ConcurrentHashMap<K, V> {
    private final Supplier<V> defaultSup;

    public DefaultConcurrentHashMap(Supplier<V> defaultValueSupplier) {
        defaultSup = defaultValueSupplier;
    }

    @Override
    public V get(Object key) {
        return super.computeIfAbsent((K) key, (k) -> defaultSup.get());
    }
}
