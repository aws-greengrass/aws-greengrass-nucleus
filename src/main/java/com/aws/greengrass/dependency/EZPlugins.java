/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.dependency;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Utils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

// FIXME: android: replace that temporary stub solution
import com.aws.greengrass.dependency.android.FastClasspathScanner;
import com.aws.greengrass.dependency.android.ClassAnnotationMatchProcessor;
import com.aws.greengrass.dependency.android.ImplementingClassMatchProcessor;
//import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
//import io.github.lukehutch.fastclasspathscanner.matchprocessor.ClassAnnotationMatchProcessor;
//import io.github.lukehutch.fastclasspathscanner.matchprocessor.ImplementingClassMatchProcessor;
import lombok.Getter;

import java.io.Closeable;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.inject.Inject;


@SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", justification = "Spotbugs false positive")
public class EZPlugins implements Closeable {
    private static final Logger logger = LogManager.getLogger(EZPlugins.class);
    public static final String JAR_FILE_EXTENSION = ".jar";
    private final List<Consumer<FastClasspathScanner>> matchers = new ArrayList<>();
    private Path cacheDirectory;
    @Getter
    private Path trustedCacheDirectory;
    private Path untrustedCacheDirectory;
    private volatile ClassLoader root = this.getClass().getClassLoader();
    private final List<URLClassLoader> classLoaders = new ArrayList<>();
    private boolean doneFirstLoad;
    private final ExecutorService executorService;

    @Inject
    public EZPlugins(ExecutorService executorService) {
        this.executorService = executorService;
    }

    public EZPlugins(ExecutorService executorService, Path d) throws IOException {
        this.executorService = executorService;
        withCacheDirectory(d);
    }

    private static void walk(Path p, Consumer<Path> action) throws IOException {
        if (Files.exists(p)) {
            try (Stream<Path> s = Files.walk(p)) {
                s.forEach(action);
            }
        }
    }

    /**
     * Set the plugin root directory.
     *
     * @param d plugin root directory
     * @return this
     * @throws IOException if can't create directories
     */
    public final EZPlugins withCacheDirectory(Path d) throws IOException {
        cacheDirectory = d;
        trustedCacheDirectory = cacheDirectory.resolve("trusted");
        untrustedCacheDirectory = cacheDirectory.resolve("untrusted");
        Files.createDirectories(trustedCacheDirectory);
        Files.createDirectories(untrustedCacheDirectory);
        return this;
    }

    private synchronized void loadPlugins(boolean trusted, ClassLoader cls) {
        doneFirstLoad = true;
        FastClasspathScanner sc = new FastClasspathScanner("com.aws.greengrass");
        sc.strictWhitelist();
        sc.addClassLoader(cls);
        matchers.forEach(m -> m.accept(sc));
        sc.scan(executorService, 1);
        if (trusted) {
            root = cls;
        }
    }

    @SuppressWarnings("PMD.CloseResource")
    // Class loader must stay open, otherwise we won't be able to load all classes from the jar
    private void loadPlugins(boolean trusted, Path p) throws IOException {
        URLClassLoader cl = new URLClassLoader(new URL[]{p.toUri().toURL()});
        classLoaders.add(cl);
        loadPlugins(trusted, cl);
    }

    /**
     * Load a single plugin with the classpath scanner.
     *
     * @param p       path to jar file
     * @param matcher matcher to use
     * @throws IOException if loading the class fails
     */
    // Class loader must stay open, otherwise we won't be able to load all classes from the jar
    @SuppressWarnings("PMD.CloseResource")
    public synchronized ClassLoader loadPlugin(Path p, Consumer<FastClasspathScanner> matcher) throws IOException {
        URL[] urls = {p.toUri().toURL()};
        return AccessController.doPrivileged((PrivilegedAction<ClassLoader>) () -> {
            URLClassLoader cl = new URLClassLoader(urls, root);
            classLoaders.add(cl);
            root = cl;
            FastClasspathScanner sc = new FastClasspathScanner();
            sc.ignoreParentClassLoaders();
            sc.addClassLoader(cl);
            matcher.accept(sc);
            sc.scan(executorService, 1);
            return cl;
        });
    }

    /**
     * Don't call loadCache until after all of the implementing/annotated matchers have been registered.
     *
     * @throws IOException if loading the cache fails
     */
    public synchronized EZPlugins loadCache() throws IOException {
        AtomicReference<IOException> e1 = new AtomicReference<>(null);
        ArrayList<URL> trustedFiles = new ArrayList<>();
        walk(trustedCacheDirectory, p -> {
            if (p.toString().endsWith(JAR_FILE_EXTENSION)) {
                try {
                    trustedFiles.add(p.toUri().toURL());
                } catch (MalformedURLException ex) {
                    e1.compareAndSet(null, new IOException("Error loading trusted plugin " + p, ex));
                }
            }
        });
        loadPlugins(true, this.getClass().getClassLoader());
        if (!trustedFiles.isEmpty()) {
            AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
                URLClassLoader trusted = new URLClassLoader(trustedFiles.toArray(new URL[0]), root);
                classLoaders.add(trusted);
                root = trusted;
                loadPlugins(true, trusted);
                return null;
            });
        }
        walk(untrustedCacheDirectory, p -> {
            if (p.toString().endsWith(JAR_FILE_EXTENSION)) {
                try {
                    loadPlugins(false, p);
                } catch (IOException ex) {
                    e1.compareAndSet(null, new IOException("Error loading untrusted plugin " + p, ex));
                    logger.atError().log("Unable to load untrusted plugin from {}", p, ex);
                }
            }
        });
        if (e1.get() != null) {
            // throw first error
            throw e1.get();
        }
        return this;
    }

    /**
     * List the available plugin filenames.
     *
     * @param trusted set to true if it should list trusted plugins, false for untrusted plugins
     * @return string array of plugin filenames
     */
    public String[] list(boolean trusted) {
        ArrayList<String> s = new ArrayList<>();
        try {
            walk(trusted ? trustedCacheDirectory : untrustedCacheDirectory, p -> {
                String ps = p.getFileName().toString();
                if (ps.endsWith(JAR_FILE_EXTENSION)) {
                    s.add(ps);
                }
            });
        } catch (IOException ex) {
            s.add(ex.toString());
        }
        return s.toArray(new String[0]);
    }

    /**
     * Delete all plugins.
     *
     * @return this
     * @throws IOException if deletion fails
     */
    public EZPlugins clearCache() throws IOException {
        IOException ioe = new IOException("One or more file deletion failed");
        walk(cacheDirectory, p -> {
            if (p.toString().endsWith(JAR_FILE_EXTENSION)) {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    ioe.addSuppressed(e);
                }
            }
        });
        if (ioe.getSuppressed().length > 0) {
            throw ioe;
        }
        return this;
    }

    /**
     * Load a jar from a URL into the plugin cache.
     *
     * @param trusted true if the plugin should be set as trusted
     * @param u       URL to load the jar from
     * @return this
     * @throws IOException if loading fails
     */
    public EZPlugins loadToCache(boolean trusted, URL u) throws IOException {
        String nm = Utils.namePart(u.getPath());
        if (!nm.endsWith(JAR_FILE_EXTENSION)) {
            throw new IOException("Only .jar files can be cached: " + u);
        }
        Path d = (trusted ? trustedCacheDirectory : untrustedCacheDirectory).resolve(nm);
        Files.copy(u.openStream(), d, StandardCopyOption.REPLACE_EXISTING);
        loadPlugins(trusted, d);
        return this;
    }

    /**
     * Move a jar from the path into the plugin cache.
     *
     * @param trusted true if it should be moved into the trusted plugin cache
     * @param u       path to the jar to move
     * @return this
     * @throws IOException if moving fails
     */
    public EZPlugins moveToCache(boolean trusted, Path u) throws IOException {
        Path p = u.getFileName();
        if (p == null) {
            throw new IOException("Filename was null");
        }
        String nm = p.toString();
        if (!nm.endsWith(JAR_FILE_EXTENSION)) {
            throw new IOException("Only .jar files can be cached: " + u);
        }
        Path d = (trusted ? trustedCacheDirectory : untrustedCacheDirectory).resolve(nm);
        if (!d.equals(u)) {
            Files.copy(u, d, StandardCopyOption.REPLACE_EXISTING);
        }
        loadPlugins(trusted, d);
        return this;
    }

    /**
     * Find plugins implementing the given class.
     *
     * @param c   Class that the plugin should implement
     * @param m   Callback to do something if a matching plugin is found
     * @param <T> the class type to lookup
     * @return this
     * @throws IllegalStateException if plugins are not yet loaded
     */
    public <T> EZPlugins implementing(Class<T> c, ImplementingClassMatchProcessor<T> m) {
        if (doneFirstLoad) {
            throw new IllegalStateException("EZPlugins: all matchers must be specified before the first class load");
        }
        matchers.add(fcs -> fcs.matchClassesImplementing(c, m));
        return this;
    }

    /**
     * Find plugin annotated with a given class.
     *
     * @param c   Annotation to search for
     * @param m   Callback if a match is found
     * @param <T> the class type to lookup
     * @return this
     * @throws IllegalStateException if plugins are not yet loaded
     */
    public <T extends Annotation> EZPlugins annotated(Class<T> c, ClassAnnotationMatchProcessor m) {
        if (doneFirstLoad) {
            throw new IllegalStateException("EZPlugins: all matchers must be specified before the first class load");
        }
        matchers.add(fcs -> fcs.matchClassesWithAnnotation(c, m));
        return this;
    }

    /**
     * Load a class from the root classloader.
     *
     * @param name name of the class
     * @return the class
     * @throws ClassNotFoundException if the class isn't found in the classloaders
     */
    public synchronized Class<?> forName(String name) throws ClassNotFoundException {
        return root.loadClass(name);
    }

    @SuppressWarnings("PMD.CloseResource")
    @Override
    public void close() throws IOException {
        for (URLClassLoader classLoader : classLoaders) {
            classLoader.close();
        }
    }
}
