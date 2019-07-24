/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.dependency;


public interface InjectionActions {
    /**
     * Called after the constructor, but before dependency injection
     */
    default void preInject() {}
    /**
     * Called after dependency injection, but before dependencies are all
     * Running
     */
    default void postInject() {}
}
