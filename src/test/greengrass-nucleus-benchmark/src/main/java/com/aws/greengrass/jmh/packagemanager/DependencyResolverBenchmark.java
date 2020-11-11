/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.jmh.packagemanager;

import com.amazonaws.services.evergreen.model.ConfigurationValidationPolicy;
import com.aws.greengrass.componentmanager.DependencyResolver;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.DeploymentService;
import com.aws.greengrass.deployment.model.ComponentUpdatePolicy;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentPackageConfiguration;
import com.aws.greengrass.deployment.model.FailureHandlingPolicy;
import com.aws.greengrass.jmh.profilers.ForcedGcMemoryProfiler;
import com.aws.greengrass.lifecyclemanager.Kernel;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static com.amazonaws.services.evergreen.model.ComponentUpdatePolicyAction.NOTIFY_COMPONENTS;

public class DependencyResolverBenchmark {

    @BenchmarkMode(Mode.AverageTime)
    @Fork(1)
    @Measurement(iterations = 20)
    @Warmup(iterations = 5)
    @State(Scope.Benchmark)
    public abstract static class DRIntegration {
        private DeploymentDocument jobDoc = new DeploymentDocument("mockJob1",
                Arrays.asList(new DeploymentPackageConfiguration("boto3", true, "1.9.128", new HashMap<>()),
                        new DeploymentPackageConfiguration("awscli", true, "1.16.144", new HashMap<>())), "mockGroup1",
                1L, FailureHandlingPolicy.DO_NOTHING, new ComponentUpdatePolicy(60, NOTIFY_COMPONENTS),
                new ConfigurationValidationPolicy().withTimeout(20));

        private DependencyResolver resolver;
        private List<ComponentIdentifier> result;
        private Kernel kernel;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            kernel = new Kernel();
            kernel.parseArgs("-i", DependencyResolverBenchmark.class.getResource(getConfigFile()).toString());
            kernel.launch();

            // pre-load contents to package store
            Path localStoreContentPath = Paths.get(System.getProperty("user.dir"))
                    .resolve("src/test/greengrass-nucleus-benchmark/mock_artifact_source");

            // pre-load contents to package store
            copyFolderRecursively(localStoreContentPath, kernel.getNucleusPaths().componentStorePath());

            // get the resolver from context
            resolver = kernel.getContext().get(DependencyResolver.class);
        }

        @TearDown(Level.Invocation)
        public void doTeardown() {
            ForcedGcMemoryProfiler.recordUsedMemory();
        }

        @TearDown(Level.Trial)
        public void doShutdown() {
            kernel.shutdown();
        }

        @Benchmark
        public List<ComponentIdentifier> measure() throws Exception {
            result = resolver.resolveDependencies(jobDoc,
                    Topics.of(kernel.getContext(), DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS, null));
            return result;
        }

        protected abstract String getConfigFile();
    }

    public static class NewMain extends DRIntegration {
        @Override
        protected String getConfigFile() {
            return "DRBNewConfig.yaml";
        }
    }

    public static class StatefulMain extends DRIntegration {
        @Override
        protected String getConfigFile() {
            return "DRBStatefulConfig.yaml";
        }
    }

    private static void copyFolderRecursively(Path src, Path des) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(des.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, des.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
