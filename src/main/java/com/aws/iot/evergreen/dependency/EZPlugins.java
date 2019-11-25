/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.dependency;

import com.aws.iot.evergreen.util.Utils;
import io.github.lukehutch.fastclasspathscanner.*;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.*;
import io.github.lukehutch.fastclasspathscanner.scanner.*;
import java.io.*;
import java.lang.annotation.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;


public class EZPlugins {
    private Path cacheDirectory, trustedCacheDirectory, untrustedCacheDirectory;
    private ClassLoader root = this.getClass().getClassLoader();
    private boolean doneFirstLoad;
    public EZPlugins(){}
    public EZPlugins(Path d) { setCacheDirectory(d); }
    public final EZPlugins setCacheDirectory(Path d) {
        cacheDirectory = d;
        trustedCacheDirectory = cacheDirectory.resolve("trusted");
        untrustedCacheDirectory = cacheDirectory.resolve("untrusted");
        try {
            Files.createDirectories(trustedCacheDirectory);
            Files.createDirectories(untrustedCacheDirectory);
        } catch (IOException ex) { }
        return this;
    }
    private void loadPlugins(boolean trusted, ClassLoader cls) {
        doneFirstLoad = true;
        FastClasspathScanner sc = new FastClasspathScanner();
        sc.addClassLoader(cls);
        matchers.forEach(m -> m.accept(sc));
        ScanResult sr = sc.scan();
        if (trusted) root = cls;
    }
    private void loadPlugins(boolean trusted, Path p) throws MalformedURLException {
        URLClassLoader cl = new URLClassLoader(new URL[]{p.toUri().toURL()});
        loadPlugins(trusted, cl);
    }
    /**
     * Don't call loadCache until after all of the implementing/annotated
     * matchers have been registered
     */
    public EZPlugins loadCache() throws IOException {
        AtomicReference<IOException> e1 = new AtomicReference<>(null);
        ArrayList<URL> trustedFiles = new ArrayList<>();
        walk(trustedCacheDirectory, p -> {
            if (p.toString().endsWith(".jar"))
                try {
                    trustedFiles.add(p.toUri().toURL());
                } catch (MalformedURLException ex) {
                    e1.compareAndSet(null, new IOException(
                            "Error loading trusted plugin " + p, ex));
                }
        });
        loadPlugins(true, this.getClass().getClassLoader());
        if (!trustedFiles.isEmpty()) {
            URLClassLoader trusted = new URLClassLoader(
                    trustedFiles.toArray(new URL[trustedFiles.size()]),
                    root);
            root = trusted;
            loadPlugins(true, trusted);
        }
        walk(untrustedCacheDirectory, p -> {
            if (p.toString().endsWith(".jar")) try {
                loadPlugins(false, p);
            } catch (Throwable ex) {
                e1.compareAndSet(null, new IOException(
                        "Error loading untrusted plugin " + p, ex));
                ex.printStackTrace(System.out);
            }
        });
        if (e1.get() != null) // throw first error
            throw e1.get();
        return this;
    }
    public String[] list(boolean trusted) {
        ArrayList<String> s = new ArrayList<>();
        try {
            walk(trusted ? trustedCacheDirectory
                               : untrustedCacheDirectory,
                    p -> {
                        String ps = p.getFileName().toString();
                        if (ps.endsWith(".jar")) s.add(ps);
                    });
        } catch (IOException ex) {
            s.add(ex.toString());
        }
        return s.toArray(new String[s.size()]);
    }
    private static void walk(Path p, Consumer<Path> action) throws IOException {
        if(Files.exists(p))
            Files.walk(p).forEach(action);
    }
    public EZPlugins clearCache() throws IOException {
        walk(cacheDirectory, p -> {
            if (p.toString().endsWith(".jar")) try {
                Files.delete(p);
            } catch (Throwable ex) {
                ex.printStackTrace(System.out);
            }
        });
        return this;
    }
    public EZPlugins loadToCache(boolean trusted, URL u) throws IOException {
        String nm = Utils.namePart(u.getPath());
        if (!nm.endsWith(".jar")) throw new IOException(
                    "Only .jar files can be cached: " + u);
        Path d = (trusted ? trustedCacheDirectory : untrustedCacheDirectory)
                .resolve(nm);
        Files.copy(u.openStream(), d, StandardCopyOption.REPLACE_EXISTING);
        loadPlugins(trusted, d);
        return this;
    }
    public EZPlugins moveToCache(boolean trusted, Path u) throws IOException {
        String nm = u.getFileName().toString();
        if (!nm.endsWith(".jar")) throw new IOException(
                    "Only .jar files can be cached: " + u);
        Path d = (trusted ? trustedCacheDirectory : untrustedCacheDirectory)
                .resolve(nm);
        if(!d.equals(u)) Files.copy(u, d, StandardCopyOption.REPLACE_EXISTING);
        loadPlugins(trusted, d);
        return this;
    }
    public <T> EZPlugins implementing(Class<T> c,
            ImplementingClassMatchProcessor<T> m) {
        if (doneFirstLoad)
            throw new IllegalStateException(
                    "EZPlugins: all matchers must be specified before the first class load");
        matchers.add(fcs -> fcs.matchClassesImplementing(c, m));
        return this;
    }
    public <T extends Annotation> EZPlugins annotated(Class<T> c,
            ClassAnnotationMatchProcessor m) {
        if (doneFirstLoad)
            throw new IllegalStateException(
                    "EZPlugins: all matchers must be specified before the first class load");
        matchers.add(fcs -> fcs.matchClassesWithAnnotation(c, m));
        return this;
    }
    private final ArrayList<Consumer<FastClasspathScanner>> matchers = new ArrayList<>();
}
