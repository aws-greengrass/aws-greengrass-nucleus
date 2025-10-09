/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc.common;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import software.amazon.awssdk.aws.greengrass.model.GreengrassCoreIPCError;
import software.amazon.awssdk.aws.greengrass.model.ServiceError;

import java.util.function.Supplier;

public final class ExceptionUtil {
    private static final Logger LOGGER = LogManager.getLogger(ExceptionUtil.class);

    private ExceptionUtil() {
    }

    /**
     * Run a method and then translate any runtime exceptions from it into ServiceErrors.
     *
     * @param sup method to run
     * @param <T> Return type
     * @return return if the supplier does not throw
     * @throws GreengrassCoreIPCError when an exception occurs
     * @throws ServiceError for any translated exception
     */
    @SuppressWarnings({
            "PMD.AvoidRethrowingException", "PMD.AvoidCatchingGenericException", "PMD.PreserveStackTrace"
    })
    public static <T> T translateExceptions(Supplier<T> sup) {
        try {
            return sup.get();
        } catch (GreengrassCoreIPCError e) {
            // Don't remap GreengrassCoreIPCError into ServiceError
            throw e;
        } catch (RuntimeException e) {
            LOGGER.atError().log("Unhandled exception in IPC", e);
            throw new ServiceError(e.getMessage());
        }
    }
}
