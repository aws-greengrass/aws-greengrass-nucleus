/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.dependency;

import com.aws.iot.evergreen.util.Utils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ClassAnnotationMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ImplementingClassMatchProcessor;

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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;


@SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", justification = "Spotbugs false positive")
public class EZPlugins {
    private final List<Consumer<FastClasspathScanner>> matchers = new ArrayList<>();
    private Path cacheDirectory;
    private Path trustedCacheDirectory;
    private Path untrustedCacheDirectory;
    private ClassLoader root = this.getClass().getClassLoader();
    private boolean doneFirstLoad;

    public EZPlugins() {
    }

    public EZPlugins(Path d) {
        setCacheDirectory(d);
    }

    private static void walk(Path p, Consumer<Path> action) throws IOException {
        if (Files.exists(p)) {
            Files.walk(p).forEach(action);
        }
    }

    /**
     * Set the plugin root directory.
     *
     * @param d plugin root directory
     * @return this
     */
    @SuppressWarnings({"checkstyle:emptycatchblock"})
    public final EZPlugins setCacheDirectory(Path d) {
        cacheDirectory = d;
        trustedCacheDirectory = cacheDirectory.resolve("trusted");
        untrustedCacheDirectory = cacheDirectory.resolve("untrusted");
        try {
            Files.createDirectories(trustedCacheDirectory);
            Files.createDirectories(untrustedCacheDirectory);
        } catch (IOException ignored) {
        }
        return this;
    }

    private void loadPlugins(boolean trusted, ClassLoader cls) {
        doneFirstLoad = true;
        FastClasspathScanner sc = new FastClasspathScanner();
        sc.addClassLoader(cls);
        matchers.forEach(m -> m.accept(sc));
        sc.scan();
        if (trusted) {
            root = cls;
        }
    }

    private void loadPlugins(boolean trusted, Path p) throws IOException {
        try (URLClassLoader cl = new URLClassLoader(new URL[]{p.toUri().toURL()})) {
            loadPlugins(trusted, cl);
        }
    }

    /**
     * Don't call loadCache until after all of the implementing/annotated
     * matchers have been registered.
     *
     * @throws IOException if loading the cache fails
     */
    public EZPlugins loadCache() throws IOException {
        AtomicReference<IOException> e1 = new AtomicReference<>(null);
        ArrayList<URL> trustedFiles = new ArrayList<>();
        walk(trustedCacheDirectory, p -> {
            if (p.toString().endsWith(".jar")) {
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
                root = trusted;
                loadPlugins(true, trusted);
                return null;
            });
        }
        walk(untrustedCacheDirectory, p -> {
            if (p.toString().endsWith(".jar")) {
                try {
                    loadPlugins(false, p);
                } catch (Throwable ex) {
                    e1.compareAndSet(null, new IOException("Error loading untrusted plugin " + p, ex));
                    ex.printStackTrace(System.out);
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
                if (ps.endsWith(".jar")) {
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
        walk(cacheDirectory, p -> {
            if (p.toString().endsWith(".jar")) {
                try {
                    Files.delete(p);
                } catch (Throwable ex) {
                    ex.printStackTrace(System.out);
                }
            }
        });
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
        if (!nm.endsWith(".jar")) {
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
        if (!nm.endsWith(".jar")) {
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
}
