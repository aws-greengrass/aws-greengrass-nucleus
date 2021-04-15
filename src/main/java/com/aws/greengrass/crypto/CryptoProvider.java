/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.crypto;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;

public class CryptoProvider implements AutoCloseable {
    private final CryptoI defaultCrypto;
    private final AtomicReference<CryptoI> implementation;
    private final Set<Runnable> onChange = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * CryptoProvider constructor.
     *
     * @param defaultCrypto the default implementation to use
     */
    @Inject
    public CryptoProvider(CryptoI defaultCrypto) {
        this.defaultCrypto = defaultCrypto;
        defaultCrypto.setImplementationUpdated(this::implementationChanged);
        this.implementation = new AtomicReference<>(defaultCrypto);
    }

    /**
     * Get the current implementation of CryptoI.
     *
     * @return CryptoI implementation
     */
    public CryptoI get() {
        CryptoI prov = implementation.get();
        if (prov != null) {
            return prov;
        }

        return defaultCrypto;
    }

    public void onProviderChange(Runnable onChange) {
        this.onChange.add(onChange);
    }

    public void removeOnProviderChange(Runnable onChange) {
        this.onChange.remove(onChange);
    }

    /**
     * Change the CryptoI implementation.
     *
     * @param newImplementation the new implementation to use
     */
    public void updateImplementation(CryptoI newImplementation) {
        CryptoI oldProvider = implementation.getAndSet(newImplementation);
        if (!Objects.equals(oldProvider, get())) {
            get().setImplementationUpdated(this::implementationChanged);
            implementationChanged();
        }
    }

    private void implementationChanged() {
        for (Runnable runnable : onChange) {
            runnable.run();
        }
    }

    /**
     * Close out all providers.
     *
     * @throws Exception if closing fails
     */
    @Override
    public void close() throws Exception {
        try {
            defaultCrypto.close();
        } finally {
            if (get() != defaultCrypto) {
                get().close();
            }
        }
    }
}
