// FIXME: add implementation for Android tag: fastclasspathscanner
package com.aws.greengrass.dependency.android;


@FunctionalInterface
public interface ClassAnnotationMatchProcessor {

    public void processMatch(Class<?> classWithAnnotation);
}
