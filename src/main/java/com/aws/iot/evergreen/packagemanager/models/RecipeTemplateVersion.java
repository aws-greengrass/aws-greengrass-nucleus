package com.aws.iot.evergreen.packagemanager.models;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum RecipeTemplateVersion {
    @JsonProperty("2020-01-25") JAN_25_2020("2020-01-25");

    private String recipeTemplateVersion;

    RecipeTemplateVersion(final String recipeTemplateVersion) {
        this.recipeTemplateVersion = recipeTemplateVersion;
    }

    public String getRecipeTemplateVersion() {
        return recipeTemplateVersion;
    }
}
