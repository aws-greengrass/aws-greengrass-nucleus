// FIXME: android: add implementation for Android tag: fastclasspathscanner
package com.aws.greengrass.dependency.android;


//@FunctionalInterface
public interface ImplementingClassMatchProcessor<T> {

    public void processMatch(Class<? extends T> implementingClass);
}
