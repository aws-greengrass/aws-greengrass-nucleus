package com.aws.iot.evergreen.packagemanager.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// This is being added as an attempt to get around a bug in covertura:
// https://github.com/5monkeys/cobertura-action/issues/7

public class ConstantsTests {

    @Test
    public void GIVEN_recipe_file_name_WHEN_compare_to_constant_THEN_equal() {
        Constants constantsObj = new Constants();
        assertEquals("recipe.yaml", Constants.RECIPE_FILE_NAME);
    }
}
