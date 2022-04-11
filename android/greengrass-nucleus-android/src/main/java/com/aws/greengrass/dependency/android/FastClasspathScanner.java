/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

// FIXME: android: add implementation for Android; tag: fastclasspathscanner
//  see https://klika-tech.atlassian.net/browse/GGSA-62

package com.aws.greengrass.dependency.android;

import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.provisioning.DeviceIdentityInterface;
import com.aws.greengrass.util.Coerce;

import java.util.concurrent.ExecutorService;

public class FastClasspathScanner {
    private static final Logger logger = LogManager.getLogger(FastClasspathScanner.class);
    private final String[] deviceIdentityInterfaceImplementors = {};    // no one

    /*
    Found Plugin: DockerApplicationManagerService. {}
    Found Plugin: DeploymentService. {}
    Found Plugin: UpdateSystemPolicyService. {}
    Found Plugin: FleetStatusService. {}
    Found Plugin: TelemetryAgent. {}
    Found Plugin: TokenExchangeService. {}
    */

    private final String[] implementsServiceAnnotated = {
            "com.aws.greengrass.componentmanager.plugins.docker.DockerApplicationManagerService",
            "com.aws.greengrass.deployment.DeploymentService",
            "com.aws.greengrass.lifecyclemanager.UpdateSystemPolicyService",
            "com.aws.greengrass.status.FleetStatusService",
            "com.aws.greengrass.telemetry.TelemetryAgent",
            "com.aws.greengrass.tes.TokenExchangeService"
    };


    public FastClasspathScanner() {
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    public FastClasspathScanner(String pkg) {
    }

    public void verbose() {
    }

    public void strictWhitelist() {
    }

    public void addClassLoader(ClassLoader cl) {
    }

    public void ignoreParentClassLoaders() {
    }

    public void scan(final ExecutorService executorService, final int numParallelTasks) {
        // FIXME: android: what to do ?
    }

    /**
     * Find classes which implements that interface. Fake implementation.
     *
     * @param <T> This is the type parameter
     * @param implementedInterface interface to implement
     * @param interfaceMatchProcessor matching processor
     * @return this
     */
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    public <T> FastClasspathScanner matchClassesImplementing(final Class<T> implementedInterface, 
        final ImplementingClassMatchProcessor<T> interfaceMatchProcessor) {
        if (implementedInterface != null && interfaceMatchProcessor != null) {
            if (implementedInterface == DeviceIdentityInterface.class) {
                for (final String className : deviceIdentityInterfaceImplementors) {
                    try {
                        final Class<? extends T> cls = loadClass(className);
                        interfaceMatchProcessor.processMatch(cls);
                    } catch (Throwable t) {
                        logger.atError("classpathscanner-implementing-error", t)
                                .kv("className", Coerce.toString(className)).log();
                    }
                }
            }
        }
        return this;
    }

    /**
     * Find classes which annotated with that annotation. Fake implementation.
     *
     * @param annotation  annotation
     * @param classAnnotationMatchProcessor  class match processor
     * @return this
     */
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    public FastClasspathScanner matchClassesWithAnnotation(final Class<?> annotation, 
        final ClassAnnotationMatchProcessor classAnnotationMatchProcessor) {
        if (annotation == ImplementsService.class) {
            for (final String className: implementsServiceAnnotated) {
                try {
                    final Class<?> cls = loadClass(className);
                    classAnnotationMatchProcessor.processMatch(cls);
                } catch (Throwable t) {
                    logger.atError("classpathscanner-implementing-error", t)
                        .kv("className", Coerce.toString(className)).log();
                }
            }
        }
        return this;
    }

    /**
     * Call the classloader using Class.forName(className). Re-throws classloading exceptions as RuntimeException.
     */
    @SuppressWarnings("PMD.SignatureDeclareThrowsException")
    private <T> Class<? extends T> loadClass(final String className) throws Exception {
        @SuppressWarnings("unchecked")
        final Class<? extends T> cls = (Class<? extends T>) Class.forName(className);
        return cls;
    }
}
