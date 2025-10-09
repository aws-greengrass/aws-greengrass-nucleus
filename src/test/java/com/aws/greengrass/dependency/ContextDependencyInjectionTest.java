/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.dependency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Named;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class ContextDependencyInjectionTest {

    private Context context;

    static class Bogon {
        @Inject
        Engine engine;

    }

    static class Engine {

    }

    static class BogonWithTwoSameEngines {

        @Inject
        Engine leftEngine;

        @Inject
        Engine rightEngine;

        public BogonWithTwoSameEngines() {

        }
    }

    static class BogonWithTwoDifferentEngines {

        @Inject
        @Named("left")
        Engine leftEngine;

        @Inject
        @Named("right")
        Engine rightEngine;

        public BogonWithTwoDifferentEngines() {

        }
    }

    static class BogonWithConstructorInjection {
        Engine engine;

        @Inject
        public BogonWithConstructorInjection(Engine engine) {
            this.engine = engine;
        }
    }

    static class BogonWithNamedConstructorInjection {
        Engine leftEngine;
        Engine rightEngine;

        @Inject
        public BogonWithNamedConstructorInjection(@Named("left") Engine leftEngine,
                @Named("right") Engine rightEngine) {
            this.leftEngine = leftEngine;
            this.rightEngine = rightEngine;
        }
    }

    interface BogonI {
        int what();

        class Default implements BogonI {
            @Override
            public int what() {
                return 42;
            }
        }
    }

    @BeforeEach
    void beforeEach() {
        context = new Context();
    }

    @AfterEach
    void afterEach() throws IOException {
        context.close();
    }

    @Test
    void GIVEN_bogon_with_field_injection_WHEN_context_get_THEN_objects_are_created() {
        Bogon bogon = context.get(Bogon.class);

        assertNotNull(bogon);
        assertNotNull(bogon.engine);
    }

    @Test
    void GIVEN_bogon_with_constructor_injection_WHEN_context_get_THEN_objects_are_created() {
        BogonWithConstructorInjection bogon = context.get(BogonWithConstructorInjection.class);

        assertNotNull(bogon);
        assertNotNull(bogon.engine);
    }

    @Test
    void GIVEN_bogon_with_two_engines_field_injection_WHEN_context_get_THEN_bogon_is_created_with_singleton_engine() {
        BogonWithTwoSameEngines bogon = context.get(BogonWithTwoSameEngines.class);

        assertNotNull(bogon);
        assertNotNull(bogon.leftEngine);
        assertNotNull(bogon.rightEngine);
        assertSame(bogon.leftEngine, bogon.rightEngine);
    }

    @Test
    void GIVEN_bogon_with_named_field_injection_WHEN_context_get_THEN_bogon_is_created_with_two_different_engines() {
        BogonWithTwoDifferentEngines bogon = context.get(BogonWithTwoDifferentEngines.class);

        assertNotNull(bogon);
        assertNotNull(bogon.leftEngine);
        assertNotNull(bogon.rightEngine);
        assertNotSame(bogon.leftEngine, bogon.rightEngine);
    }

    @Test
    void GIVEN_bogon_with_named_constructor_injection_WHEN_context_get_THEN_bogon_Is_created_with_two_different_engines() {
        BogonWithNamedConstructorInjection bogon = context.get(BogonWithNamedConstructorInjection.class);

        assertNotNull(bogon);
        assertNotNull(bogon.leftEngine);
        assertNotNull(bogon.rightEngine);
        assertNotSame(bogon.leftEngine, bogon.rightEngine);
    }

    @Test
    void GIVEN_bogon_with_named_constructor_injection_WHEN_context_get_THEN_bogon_is_created_with_a_provided_engine() {
        Engine myLeftEngine = new Engine();
        context.put("left", myLeftEngine);

        BogonWithNamedConstructorInjection bogon = context.get(BogonWithNamedConstructorInjection.class);

        assertNotNull(bogon);
        assertNotNull(bogon.leftEngine);
        assertNotNull(bogon.rightEngine);
        assertNotSame(bogon.leftEngine, bogon.rightEngine);
        assertSame(myLeftEngine, bogon.leftEngine);
    }

    @Test
    void GIVEN_bogon_interface_WHEN_context_get_THEN_DEFAULT_implementation_is_binded() {
        assertEquals(42, context.get(BogonI.class).what());
    }
}
