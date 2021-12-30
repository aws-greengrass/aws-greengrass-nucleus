// FIXME: android: add implementation for Android; tag: fastclasspathscanner
package com.aws.greengrass.dependency.android;


import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.provisioning.DeviceIdentityInterface;

import java.lang.annotation.Annotation;
import java.util.concurrent.ExecutorService;

public class FastClasspathScanner {
    private final String[] DeviceIdentityInterfaceImplementors = {};    // no one

/*
Found Plugin: DockerApplicationManagerService. {}
Found Plugin: DeploymentService. {}
Found Plugin: UpdateSystemPolicyService. {}
Found Plugin: FleetStatusService. {}
Found Plugin: TelemetryAgent. {}
Found Plugin: TokenExchangeService. {}
*/

    private final String[] ImplementsServiceAnnotated = {
            "com.aws.greengrass.componentmanager.plugins.docker.DockerApplicationManagerService",

            "com.aws.greengrass.deployment.DeploymentService",
            "com.aws.greengrass.lifecyclemanager.UpdateSystemPolicyService",
            "com.aws.greengrass.status.FleetStatusService",
// FIXME: android: was disabled due to oshi-core does not support Android
//            "com.aws.greengrass.telemetry.TelemetryAgent",
            "com.aws.greengrass.tes.TokenExchangeService"
    };


    public FastClasspathScanner() {
    }

    public FastClasspathScanner(String package_) {
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
        long x = 10;
        // FIXME: android: what to do ?
    }

    public <T> FastClasspathScanner matchClassesImplementing(final Class<T> implementedInterface, final ImplementingClassMatchProcessor<T> interfaceMatchProcessor) {
        if (implementedInterface == DeviceIdentityInterface.class) {
            for (final String className: DeviceIdentityInterfaceImplementors) {
                try {
                    final Class<? extends T> cls = loadClass(className);
                    interfaceMatchProcessor.processMatch(cls);
                } catch (final Exception e) {
                }
            }
        }
        return this;
    }

    public FastClasspathScanner matchClassesWithAnnotation(final Class<?> annotation, final ClassAnnotationMatchProcessor classAnnotationMatchProcessor) {
        if (annotation == ImplementsService.class) {
            for (final String className: ImplementsServiceAnnotated) {
                try {
                    final Class<?> cls = loadClass(className);
                    classAnnotationMatchProcessor.processMatch(cls);
                } catch (final Exception e) {
                }
            }
        }
        return this;
    }

    /**
     * Call the classloader using Class.forName(className). Re-throws classloading exceptions as RuntimeException.
     */
    private <T> Class<? extends T> loadClass(final String className) throws Exception {
        @SuppressWarnings("unchecked")
        final Class<? extends T> cls = (Class<? extends T>) Class.forName(className);
        return cls;
    }
}
