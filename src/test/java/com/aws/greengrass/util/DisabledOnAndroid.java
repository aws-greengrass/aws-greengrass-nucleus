package com.aws.greengrass.util;


import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@DisabledIfSystemProperty(named = "java.vm.name", matches = "(?i).*dalvik.*")
public @interface DisabledOnAndroid {
}
