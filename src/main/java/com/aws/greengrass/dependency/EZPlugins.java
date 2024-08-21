/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.dependency;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.LockFactory;
import com.aws.greengrass.util.LockScope;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ClassAnnotationMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ImplementingClassMatchProcessor;
import lombok.Getter;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import javax.inject.Inject;


@SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", justification = "Spotbugs false positive")
public class EZPlugins implements Closeable {
    private static final Logger logger = LogManager.getLogger(EZPlugins.class);
    public static final String JAR_FILE_EXTENSION = ".jar";
    private final List<Consumer<FastClasspathScanner>> matchers = new ArrayList<>();
    private final List<Consumer<Class<?>>> classMatchers = new ArrayList<>();
    private Path cacheDirectory;
    @Getter
    private Path trustedCacheDirectory;
    private Path untrustedCacheDirectory;
    private volatile ClassLoader root = this.getClass().getClassLoader();
    private final List<URLClassLoader> classLoaders = new ArrayList<>();
    private final Lock lock = LockFactory.newReentrantLock(this);
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

    private void loadPlugins(boolean trusted, ClassLoader cls) {
        try (LockScope ls = LockScope.lock(lock)) {
            doneFirstLoad = true;
            if (trusted) {
                root = cls;
            }

            // Try and find the Greengrass plugin class (fast path)
            try {
                if (cls instanceof URLClassLoader) {
                    Collection<Class<?>> classes = findGreengrassPlugin((URLClassLoader) cls);
                    // Expect that we have 1 plugin per jar. If we do not, then fallback to the classpath scanner
                    // to make sure that we aren't missing loading any plugins which haven't added the GG-Plugin-Class
                    // manifest entry.
                    if (((URLClassLoader) cls).getURLs().length == classes.size()) {
                        classes.forEach(c -> classMatchers.forEach(m -> m.accept(c)));
                        return;
                    }
                }
            } catch (IOException e) {
                logger.atWarn().log("Problem looking for Greengrass plugin with the fast path."
                        + " Falling back to classpath scanner", e);
            }

            FastClasspathScanner sc = new FastClasspathScanner("com.aws.greengrass");
            sc.strictWhitelist();
            sc.addClassLoader(cls);
            matchers.forEach(m -> m.accept(sc));
            sc.scan(executorService, 1);
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
     * @param annotationClass annotation to search for
     * @param <T> annotation class type
     * @param matcher matcher to use
     * @throws IOException if loading the class fails
     */
    // Class loader must stay open, otherwise we won't be able to load all classes from the jar
    @SuppressWarnings("PMD.CloseResource")
    public <T extends Annotation> ClassLoader loadPluginAnnotatedWith(Path p, Class<T> annotationClass,
                                                           Consumer<Class<?>> matcher) throws IOException {
        try (LockScope ls = LockScope.lock(lock)) {
            URL[] urls = {p.toUri().toURL()};
            return AccessController.doPrivileged((PrivilegedAction<ClassLoader>) () -> {
                URLClassLoader cl = new URLClassLoader(urls, root);
                classLoaders.add(cl);
                root = cl;

                // Try and find the Greengrass plugin class (fast path)
                try {
                    Collection<Class<?>> classes = findGreengrassPlugin(cl);
                    if (!classes.isEmpty()) {
                        AtomicReference<ClassLoader> loaderRef = new AtomicReference<>();
                        classes.forEach((clazz) -> {
                            if (clazz.isAnnotationPresent(annotationClass)) {
                                matcher.accept(clazz);
                                loaderRef.set(cl);
                            } else {
                                logger.atWarn()
                                        .log("Class {} was found, but not annotated with {}", clazz.getSimpleName(),
                                                annotationClass.getSimpleName());
                            }
                        });
                        if (loaderRef.get() != null) {
                            return loaderRef.get();
                        }
                    }
                } catch (IOException e) {
                    logger.atWarn().log("IOException reading from {}. Falling back to classpath scanner", p, e);
                }

                FastClasspathScanner sc = new FastClasspathScanner();
                sc.ignoreParentClassLoaders();
                sc.addClassLoader(cl);
                sc.matchClassesWithAnnotation(annotationClass, matcher::accept);
                sc.scan(executorService, 1);
                return cl;
            });
        }
    }

    private Collection<Class<?>> findGreengrassPlugin(URLClassLoader cls) throws IOException {
        try (LockScope ls = LockScope.lock(lock)) {
            Enumeration<URL> urls = cls.findResources("META-INF/MANIFEST.MF");
            if (urls == null) {
                return Collections.emptyList();
            }

            List<Class<?>> classes = new LinkedList<>();
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                URLConnection conn = url.openConnection();
                // Workaround JDK bug: https://bugs.openjdk.org/browse/JDK-8246714
                conn.setUseCaches(false);
                try (InputStream is = conn.getInputStream()) {
                    Manifest manifest = new Manifest(is);
                    Attributes attr = manifest.getMainAttributes();
                    if (attr != null) {
                        String className = attr.getValue("GG-Plugin-Class");
                        if (className != null) {
                            classes.add(cls.loadClass(className));
                        }
                    }
                } catch (ClassNotFoundException e) {
                    logger.atWarn().log("Class specified by the GG-Plugin-Class manifest entry was not found", e);
                }
            }
            return classes;
        }
    }

    // Only use in tests to scan our own classpath for @ImplementsService
    public EZPlugins scanSelfClasspath() {
        loadPlugins(true, this.getClass().getClassLoader());
        return this;
    }

    /**
     * Don't call loadCache until after all of the implementing/annotated matchers have been registered.
     *
     * @throws IOException if loading the cache fails
     */
    public EZPlugins loadCache() throws IOException {
        try (LockScope ls = LockScope.lock(lock)) {
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
        classMatchers.add(x -> {
            if (c.isAssignableFrom(x)) {
                m.processMatch((Class<? extends T>) x);
            }
        });
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
        classMatchers.add((x) -> {
            if (x.isAnnotationPresent(c)) {
                m.processMatch(x);
            }
        });
        return this;
    }

    /**
     * Load a class from the root classloader.
     *
     * @param name name of the class
     * @return the class
     * @throws ClassNotFoundException if the class isn't found in the classloaders
     */
    public Class<?> forName(String name) throws ClassNotFoundException {
        try (LockScope ls = LockScope.lock(lock)) {
            return root.loadClass(name);
        }
    }

    @SuppressWarnings("PMD.CloseResource")
    @Override
    public void close() throws IOException {
        for (URLClassLoader classLoader : classLoaders) {
            classLoader.close();
        }
    }
}
