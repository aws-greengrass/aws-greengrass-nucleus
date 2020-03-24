/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.jmh.packagemanager;

import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.jmh.profilers.ForcedGcMemoryProfiler;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.plugins.LocalPackageStore;
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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public class DependencyResolverBenchmark {

    @BenchmarkMode(Mode.AverageTime)
    @Fork(1)
    @Measurement(iterations = 20)
    @Warmup(iterations = 5)
    @State(Scope.Benchmark)
    public static abstract class DRIntegration {
        private DeploymentDocument jobDoc = new DeploymentDocument("mockJob1",Arrays.asList("boto3", "awscli"), Arrays.asList(
                new DeploymentPackageConfiguration("boto3", "1.9.128", "", new HashSet<>(), new ArrayList<>()),
                new DeploymentPackageConfiguration("awscli", "1.16.144", "", new HashSet<>(), new ArrayList<>())
        ), "mockGroup1", 1L);

        private DependencyResolver resolver;
        private List<PackageIdentifier> result;

        @Setup
        public void setup() {
            Kernel kernel = new Kernel();
            kernel.parseArgs("-i", DependencyResolverBenchmark.class.getResource(getConfigFile()).toString());
            // We don't need to launch kernel here. Only configuration parsing and main service loading are
            // required for this benchmarking.

            // TODO: Update local package store accordingly when the new implementation is ready
            // TODO: Figure out if there's a better way to load resource directory in local package store
            // For now, hardcode to be under root of kernel package
            Path packagePath = Paths.get(System.getProperty("user.dir"))
                    .resolve("src/test/evergreen-kernel-benchmark/mock_artifact_source");
            resolver = new DependencyResolver(new LocalPackageStore(packagePath), kernel);
        }

        @TearDown(Level.Invocation)
        public void doTeardown() {
            ForcedGcMemoryProfiler.recordUsedMemory();
        }

        @Benchmark
        public List<PackageIdentifier> measure() throws Exception {
            result = resolver.resolveDependencies(jobDoc);
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
