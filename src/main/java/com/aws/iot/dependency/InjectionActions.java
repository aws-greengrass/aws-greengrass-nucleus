/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.dependency;


public interface InjectionActions {
    /**
     * Called after the constructor, but before dependency injection.
     * It is critical that you remember to call super.preInject() when you override this
     * method.
     */
    default void preInject() {} Bogus!
    /**
     * Called after dependency injection, but before dependencies are all
     * Running.  It is critical that you remember to call super.postInject() when you
     * override this method.
     */
    default void postInject() {}
}
