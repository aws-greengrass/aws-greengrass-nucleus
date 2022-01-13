// FIXME: android: add implementation for Android tag: fastclasspathscanner
//  see https://klika-tech.atlassian.net/browse/GGSA-62
package com.aws.greengrass.dependency.android;


//@FunctionalInterface
public interface ImplementingClassMatchProcessor<T> {

    public void processMatch(Class<? extends T> implementingClass);
}
