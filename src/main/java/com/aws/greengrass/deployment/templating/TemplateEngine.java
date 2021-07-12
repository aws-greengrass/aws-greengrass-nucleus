/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.amazon.aws.iot.greengrass.component.common.DependencyProperties;
import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.deployment.templating.exceptions.IllegalTemplateDependencyException;
import com.aws.greengrass.deployment.templating.exceptions.MultipleTemplateDependencyException;
import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer;
import static com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializerJson;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.deployment.templating.RecipeTransformer.TEMPLATE_DEFAULT_PARAMETER_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;

public class TemplateEngine {
    public static final String PARSER_JAR = "transformer.jar";

    private final Path recipeDirectoryPath;
    private final Path artifactsDirectoryPath;
    private final ComponentStore componentStore;
    private final Map<String, ComponentIdentifier> resolvedVersions;
    private final Map<String, Object> configMap;

    private final Map<ComponentIdentifier, ComponentRecipe> recipes = new HashMap<>();
    private final Map<String, List<ComponentIdentifier>> needsToBeBuilt = new HashMap<>();

    /**
     * Constructor.
     * @param recipeDirectoryPath       the directory in which to expand and clean up templates.
     * @param artifactsDirectoryPath    the directory in which to prepare artifacts.
     * @param desiredPackages           the resolved list of packages requested on the device.
     * @param configMap                 a copy of the map representing the resolved config.
     * @param componentStore            ComponentStore instance.
     */
    public TemplateEngine(Path recipeDirectoryPath, Path artifactsDirectoryPath,
                          List<ComponentIdentifier> desiredPackages, Map<String, Object> configMap,
                          ComponentStore componentStore) {
        this.recipeDirectoryPath = recipeDirectoryPath;
        this.artifactsDirectoryPath = artifactsDirectoryPath;
        resolvedVersions = new HashMap<>();
        desiredPackages.forEach(identifier -> resolvedVersions.put(identifier.getName(), identifier));
        this.configMap = configMap;
        this.componentStore = componentStore;
    }

    /**
     * Call to do templating. This call assumes we have already resolved component versions and fetched dependencies.
     * @throws TemplateExecutionException           if pre-processing throws an error.
     * @throws IOException                          for most things.
     * @throws PackageLoadingException              if we can't load a dependency.
     * @throws RecipeTransformerException           if individual templating runs into an issue.
     */
    public void process() throws TemplateExecutionException, IOException,
            PackageLoadingException, RecipeTransformerException {
        loadComponents();
        // TODO: resolve versioning, download dependencies if necessary
        expandAll();
        removeTemplatesFromStore();
    }

    /**
     * Read the parameter files and templates from store. Note which parameters files need to be expanded by which
     * template.
     * @throws MultipleTemplateDependencyException  if a parameter file declares a dependency on more than one template.
     * @throws IllegalTemplateDependencyException   if a template declares a dependency on another template.
     * @throws IOException                          if something funky happens with I/O or de/serialization.
     */
    @SuppressWarnings({"PMD.CognitiveComplexity", "PMD.AvoidDeeplyNestedIfStmts", "PMD.AvoidDuplicateLiterals"})
    void loadComponents() throws TemplateExecutionException, IOException {
        try (Stream<Path> files = Files.walk(recipeDirectoryPath)) {
            for (Path r : files.collect(Collectors.toList())) {
                if (!r.toFile().isDirectory()) {
                    ComponentRecipe recipe = parseFile(r);
                    ComponentIdentifier identifier = new ComponentIdentifier(recipe.getComponentName(),
                            recipe.getComponentVersion());
                    recipes.put(identifier, recipe);
                    Map<String, DependencyProperties> deps = recipe.getComponentDependencies();
                    if (deps == null) {
                        continue;
                    }
                    boolean paramFileAlreadyHasDependency = false;
                    for (Map.Entry<String, DependencyProperties> me : deps.entrySet()) {
                        if (me.getKey().endsWith("Template")) { // TODO: same as above
                            if (identifier.getName().endsWith("Template")) { // TODO: here too
                                throw new IllegalTemplateDependencyException("Illegal dependency for template "
                                        + identifier.getName() + ". Templates cannot depend on other templates");
                            }
                            if (paramFileAlreadyHasDependency) {
                                throw new MultipleTemplateDependencyException("Parameter file " + identifier.getName()
                                        + " has multiple template dependencies");
                            }
                            paramFileAlreadyHasDependency = true;
                            needsToBeBuilt.putIfAbsent(me.getKey(), new ArrayList<>());
                            needsToBeBuilt.get(me.getKey()).add(identifier);
                        }
                    }
                }
            }
        }
    }

    // process all templates and parameter files
    void expandAll() throws PackageLoadingException, RecipeTransformerException,
            IOException {
        for (Map.Entry<String, List<ComponentIdentifier>> entry : needsToBeBuilt.entrySet()) {
//            // TODO: get resolved component from existing map
//            ComponentIdentifier template = null;
//            for (ComponentIdentifier potentialTemplate : templates) {
//                if (potentialTemplate.getName().equals(entry.getKey())) {
//                    template = potentialTemplate; // find first lol
//                    break;
//                }
//            }
//            if (template == null) {
//                throw new PackageLoadingException("Could not find template: " + entry.getKey());
//            }
//            // END TODO
            ComponentIdentifier template = resolvedVersions.get(entry.getKey());
            expandAllForTemplate(template, entry.getValue());
        }
    }

    void expandAllForTemplate(ComponentIdentifier template, List<ComponentIdentifier> paramFiles)
            throws IOException, PackageLoadingException, RecipeTransformerException {
        Path templateExecutablePath =
                artifactsDirectoryPath.resolve(template.getName()).resolve(template.getVersion().toString()).resolve(
                        PARSER_JAR);
        JsonNode serviceConfigMap = getRecipeSerializer().readTree(
                getRecipeSerializer().writeValueAsString(configMap.get(SERVICES_NAMESPACE_TOPIC)));

        JsonNode templateConfig = serviceConfigMap.get(template.getName()).get(CONFIGURATION_CONFIG_KEY)
                .get(TEMPLATE_DEFAULT_PARAMETER_KEY);
        TransformerWrapper wrapper;
        try {
            wrapper = new TransformerWrapper(templateExecutablePath,
                    "com.aws.greengrass.deployment.templating.transformers.EchoTransformer",
                    recipes.get(template), templateConfig);
        } catch (ClassNotFoundException | IllegalTransformerException | NoSuchMethodException
                | InvocationTargetException | InstantiationException | IllegalAccessException e) {
            throw new RecipeTransformerException("Could not instantiate the transformer for template " + template.getName(), e);
        }
        for (ComponentIdentifier paramFile : paramFiles) {
            JsonNode componentConfig = serviceConfigMap.get(paramFile.getName()).get(CONFIGURATION_CONFIG_KEY);
            Pair<ComponentRecipe, List<Path>> rt =
                    wrapper.expandOne(new TemplateParameterBundle(recipes.get(paramFile), componentConfig));
            componentStore.savePackageRecipe(paramFile, getRecipeSerializer().writeValueAsString(rt.getLeft()));
            Path componentArtifactsDirectory = componentStore.resolveArtifactDirectoryPath(paramFile);
            for (Path artifactPath : rt.getRight()) {
                copyArtifactToStoreIfMissing(artifactPath, componentArtifactsDirectory);
            }
        }
    }

    // copies the artifact to the artifacts directory, if one with the same name does not already exist
    void copyArtifactToStoreIfMissing(Path artifactPath, Path componentArtifactsDirectory) throws IOException {
        Path newArtifact = componentArtifactsDirectory.resolve(artifactPath.getFileName());
        if (Files.exists(newArtifact)) {
            return;
        }
        Files.copy(artifactPath, newArtifact);
    }

    @SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts")
    void removeTemplatesFromStore() throws IOException {
        try (Stream<Path> files = Files.walk(recipeDirectoryPath)) {
            for (Path r : files.collect(Collectors.toList())) {
                if (!r.toFile().isDirectory()) {
                    ComponentRecipe recipe = parseFile(r);
                    if (recipe.getComponentName().endsWith("Template")) { // TODO: remove templates by component type
                        if (!r.toFile().delete()) {
                            throw new IOException("Could not delete template file " + recipe.getComponentName());
                        }
                        removeCorrespondingArtifactsFromStore(recipe.getComponentName());
                    }
                }
            }
        }
    }

    void removeCorrespondingArtifactsFromStore(String templateName) throws IOException {
        try (Stream<Path> files = Files.walk(artifactsDirectoryPath)) {
            for (Path r : files.collect(Collectors.toList())) {
                if (r.toFile().isDirectory() && r.toFile().getName().equals(templateName)) {
                    Files.walk(r).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                }
            }
        }
    }

    // copied from DeploymentService.copyRecipeFileToComponentStore()
    ComponentRecipe parseFile(Path recipePath) throws IOException {
        String ext = Utils.extension(recipePath.toString());
        ComponentRecipe recipe = null;
        try {
            if (recipePath.toFile().length() > 0) {
                switch (ext.toLowerCase()) {
                    case "yaml":
                    case "yml":
                        recipe = getRecipeSerializer().readValue(recipePath.toFile(), ComponentRecipe.class);
                        break;
                    case "json":
                        recipe = getRecipeSerializerJson().readValue(recipePath.toFile(), ComponentRecipe.class);
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            // Throw on error so that the user will receive this message and we will stop the deployment.
            // This is to fail fast while providing actionable feedback.
            throw new IOException(
                    String.format("Unable to parse %s as a recipe due to: %s", recipePath.toString(), e.getMessage()),
                    e);
        }
        if (recipe == null) {
            // logger.atError().log("Skipping file {} because it was not recognized as a recipe", recipePath);
            return null;
        }

        return recipe;
    }
}
