/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A ConcurrentHashMap with default values when using {@code get()}.
 * Similar to DefaultDict in Python.
 */
@SuppressFBWarnings("EQ_DOESNT_OVERRIDE_EQUALS")
public class DefaultConcurrentHashMap<K, V> extends ConcurrentHashMap<K, V> {
    private static final long serialVersionUID = 7249069246763182397L;
    private final transient Supplier<V> defaultSup;

    public DefaultConcurrentHashMap(Supplier<V> defaultValueSupplier) {
        super();
        defaultSup = defaultValueSupplier;
    }

    @Override
    public V get(Object key) {
        return super.computeIfAbsent((K) key, (k) -> defaultSup.get());
    }

    @Override
    public boolean containsKey(Object key) {
        return super.get(key) != null;
    }
}
