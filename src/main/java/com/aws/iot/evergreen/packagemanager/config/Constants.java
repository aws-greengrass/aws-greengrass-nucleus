package com.aws.iot.evergreen.packagemanager.config;

public class Constants {
    public static final String RECIPE_FILE_NAME = "recipe.yaml";

    public static final String UNSUPPORTED_TEMPLATE_EXCEPTION_MSG_FMT =
            "Found template version %s which is not supported by this version of Evergreen.";

    public static final String DEFAULT_CONFIG_NOT_FOUND_EXCEPTION_MSG =
            "Default platform config was not found when parsing recipe for package";

    public static final String UNABLE_TO_PARSE_RECIPE_EXCEPTION_MSG = "Failed to parse recipe";

}
