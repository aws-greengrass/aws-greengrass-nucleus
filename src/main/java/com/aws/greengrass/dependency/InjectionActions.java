/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.dependency;


public interface InjectionActions {
    /**
     * Called after the constructor, but before dependency injection.
     * It is critical that you remember to call super.preInject() when you override this
     * method.
     */
    default void preInject() {
    }

    /**
     * Called after dependency injection, but before dependencies are all
     * RUNNING.  It is critical that you remember to call super.postInject() when you
     * override this method.
     */
    default void postInject() {
    }
}
