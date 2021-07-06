package com.aws.greengrass.deployment.templating;

import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.util.Pair;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

public final class TransformerWrapper {
    private TransformerWrapper() {} // disallow instances

    // fetches the transformer from the jar and executes it to completion
    public static Pair<ComponentRecipe, List<Path>> execute(Path pathToExecutable, String className,
                                                                ComponentRecipe parameterFile)
            throws ClassNotFoundException, IllegalTransformerException, InstantiationException, IllegalAccessException {
        URLClassLoader loader = new URLClassLoader(new URL[] {TransformerWrapper.class.getResource(
                String.valueOf(pathToExecutable))}, TransformerWrapper.class.getClassLoader());
        Class<?> recipeTransformerClass = Class.forName(className, true, loader);
        if (!recipeTransformerClass.isInstance(RecipeTransformer.class)) {
            throw new IllegalTransformerException(className + " does not implement the RecipeTransformer interface");
        }

        RecipeTransformer transformer = (RecipeTransformer) recipeTransformerClass.newInstance();

        try {
            loader.close();
        } catch (IOException e) {
        }

        transformer.transform(parameterFile);
        return new Pair<>(transformer.getNewRecipe(), transformer.getArtifactsToCopy());
    }
}
