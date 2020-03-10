/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.jmh;

import com.aws.iot.evergreen.jmh.profilers.ForcedGcMemoryProfiler;
import com.aws.iot.evergreen.kernel.Kernel;
import org.openjdk.jmh.annotations.Benchmark;

public class BasicExampleBenchmark {

    @Benchmark
    public void testMethod() throws Exception {
        String tdir = System.getProperty("user.home")+"/kernelTest";
        Kernel kernel = new Kernel();
        kernel.parseArgs("-r", tdir,
                "-i", BasicExampleBenchmark.class.getResource("config.yaml").toString()
        );
        kernel.launch();
        Thread.sleep(20000);
        ForcedGcMemoryProfiler.recordUsedMemory();
        kernel.shutdown();
    }

}
