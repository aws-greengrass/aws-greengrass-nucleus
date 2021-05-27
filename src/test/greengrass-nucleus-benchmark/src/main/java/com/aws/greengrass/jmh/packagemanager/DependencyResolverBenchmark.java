/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.jmh.packagemanager;

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
import software.amazon.awssdk.services.greengrassv2.model.DeploymentConfigurationValidationPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Collections;
import java.util.List;

import static com.aws.greengrass.jmh.PreloadComponentStoreHelper.preloadRecipesFromTestResourceDir;
import static software.amazon.awssdk.services.greengrassv2.model.DeploymentComponentUpdatePolicyAction.NOTIFY_COMPONENTS;

public class DependencyResolverBenchmark {

    @BenchmarkMode(Mode.AverageTime)
    @Fork(1)
    @Measurement(iterations = 20)
    @Warmup(iterations = 5)
    @State(Scope.Benchmark)
    public abstract static class DRIntegration {
        private final DeploymentDocument jobDoc = new DeploymentDocument("mockJob1", "mockarn",
                Arrays.asList(new DeploymentPackageConfiguration("boto3", true, "1.9.128"),
                        new DeploymentPackageConfiguration("awscli", true, "1.16.144")), Collections.emptyList(),
                "mockGroup1", 1L, FailureHandlingPolicy.DO_NOTHING, new ComponentUpdatePolicy(60, NOTIFY_COMPONENTS),
                DeploymentConfigurationValidationPolicy.builder().timeoutInSeconds(20).build());

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
            preloadRecipesFromTestResourceDir(localStoreContentPath.resolve("recipes"),
                    kernel.getNucleusPaths().recipePath());

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
            result = resolver.resolveDependencies(jobDoc, new HashMap<>());
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
}
