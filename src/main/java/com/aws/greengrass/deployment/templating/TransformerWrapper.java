/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.aws.greengrass.util.Pair;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

public final class TransformerWrapper {

    RecipeTransformer transformer;

    /**
     * Function to execute expansion.
     * @param pathToExecutable  the jar to run.
     * @param className         the name of the parser class.
     * @param template          the template recipe file.
     * @throws ClassNotFoundException       if the jar does not contain the parser class.
     * @throws IllegalTransformerException  if the class does not implement the RecipeTransformer interface.
     * @throws InstantiationException       if constructor is not public.
     * @throws IllegalAccessException       idk...
     * @throws RecipeTransformerException   for everything else.
     */
    public TransformerWrapper(Path pathToExecutable, String className, ComponentRecipe template,
                              JsonNode templateConfig)
            throws RecipeTransformerException, ClassNotFoundException, IllegalTransformerException,
            NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
        if (!pathToExecutable.toFile().exists()) {
            throw new RecipeTransformerException("Could not find template parsing jar to execute");
        }
        System.out.println(pathToExecutable);
        URLClassLoader loader;
        try {
            loader = new URLClassLoader(new URL[] {pathToExecutable.toFile().toURI().toURL()},
                    TransformerWrapper.class.getClassLoader());
        } catch (MalformedURLException e) {
            throw new RecipeTransformerException("Could not find template parsing jar to execute", e);
        }
        Class<?> recipeTransformerClass = Class.forName(className, true, loader);

        if (!RecipeTransformer.class.isAssignableFrom(recipeTransformerClass)) {
            throw new IllegalTransformerException(className + " does not implement the RecipeTransformer interface");
        }

        transformer = (RecipeTransformer) recipeTransformerClass
                        .getConstructor(ComponentRecipe.class, JsonNode.class)
                        .newInstance(template, templateConfig);

        try {
            loader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    Pair<ComponentRecipe, List<Path>> expandOne(TemplateParameterBundle parameterBundle)
            throws RecipeTransformerException {
        return transformer.execute(parameterBundle.getLeft(), parameterBundle.getRight());
    }
}
