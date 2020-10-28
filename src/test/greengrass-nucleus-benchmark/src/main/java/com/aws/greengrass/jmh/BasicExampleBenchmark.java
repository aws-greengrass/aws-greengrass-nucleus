/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.jmh;

import com.aws.greengrass.jmh.profilers.ForcedGcMemoryProfiler;
import com.aws.greengrass.lifecyclemanager.Kernel;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@Fork(5)
@Measurement(iterations = 1)
@Warmup(iterations = 0)
public class BasicExampleBenchmark {

    @Benchmark
    public void testMethod() throws Exception {
        Kernel kernel = new Kernel();
        try {
            kernel.parseArgs("-i", BasicExampleBenchmark.class.getResource("config.yaml").toString());
            kernel.launch();
            Thread.sleep(20000);
            ForcedGcMemoryProfiler.recordUsedMemory();
        } finally {
            kernel.shutdown();
        }
    }

}
