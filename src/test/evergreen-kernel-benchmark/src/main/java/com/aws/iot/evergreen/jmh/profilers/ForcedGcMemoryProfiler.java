/*
 * #%L
 * Benchmarks: JMH suite.
 * %%
 * Copyright (C) 2013 - 2019 headissue GmbH, Munich
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 *
 * Modifications copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.jmh.profilers;

import com.aws.iot.evergreen.util.Pair;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.runner.IterationType;
import org.openjdk.jmh.util.Utils;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.PrintStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Record the used heap memory of a benchmark iteration by forcing a full garbage collection.
 *
 * @author Jens Wilke
 */
public class ForcedGcMemoryProfiler implements InternalProfiler {
    // Extracts memory pool name and amount of committed memory from jcmd output
    // Example output:
    /*
    42:

 Native Memory Tracking:

 Total: reserved=1840MB, committed=91MB
 -                 Java Heap (reserved=498MB, committed=12MB)
                             (mmap: reserved=498MB, committed=12MB)

 -                     Class (reserved=1050MB, committed=28MB)
                             (classes #4094)
                             (malloc=4MB #3323)
                             (mmap: reserved=1046MB, committed=24MB)
     */
    private static final Pattern JCMD_NATIVE_COMMITTED_MEMORY_PATTERN =
            Pattern.compile("^[\\-\\s]*(\\w+[\\w\\s]+)[: ].*committed=([\\d]+)\\w+\\)?$", Pattern.MULTILINE);

    private static boolean runOnlyAfterLastIteration = true;
    @SuppressWarnings("unused")
    private static Object keepReference;
    private static long gcTimeMillis = -1;
    private static long usedHeapViaHistogram = -1;
    private static List<Pair<String, Long>> nativeMemoryUsage;
    private static volatile boolean enabled = false;
    private static UsageTuple usageAfterIteration;
    private static UsageTuple usageAfterSettled;

    /**
     * The benchmark needs to hand over the reference so the memory is kept after
     * the shutdown of the benchmark and can be measured.
     */
    public static void keepReference(Object _rootReferenceToKeep) {
        if (enabled) {
            keepReference = _rootReferenceToKeep;
        }
    }

    public static UsageTuple getUsage() {
        MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = bean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = bean.getNonHeapMemoryUsage();
        return new UsageTuple(heapUsage, nonHeapUsage);
    }

    /**
     * Called from the benchmark when the objects are still referenced to record the
     * used memory. Enforces a full garbage collection and records memory usage.
     * Waits and triggers GC again, as long as the memory is still reducing. Some workloads
     * needs some time until they drain queues and finish all the work.
     */
    public static void recordUsedMemory() {
        long t0 = System.currentTimeMillis();
        long usedMemorySettled;
        if (runSystemGC()) {
            usageAfterIteration = getUsage();
            long m2 = usageAfterIteration.getTotalUsed();
            do {
                try {
                    Thread.sleep(567);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                runSystemGC();
                usedMemorySettled = m2;
                usageAfterSettled = getUsage();
                m2 = usageAfterSettled.getTotalUsed();
            } while (m2 < usedMemorySettled);
            gcTimeMillis = System.currentTimeMillis() - t0;
        }
        usedHeapViaHistogram = printHeapHistogram(System.out, 30);
        nativeMemoryUsage = printNativeMemoryUsage(System.out);
    }

    public static boolean runSystemGC() {
        List<GarbageCollectorMXBean> enabledBeans = new ArrayList<>();

        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = bean.getCollectionCount();
            if (count != -1) {
                enabledBeans.add(bean);
            }
        }

        long beforeGcCount = countGc(enabledBeans);

        System.runFinalization();
        System.gc();
        System.runFinalization();
        System.gc();

        final int MAX_WAIT_MSECS = 20 * 1000;
        final int STABLE_TIME_MSECS = 500;

        if (enabledBeans.isEmpty()) {
            System.err.println("WARNING: MXBeans can not report GC info.");
            return false;
        }

        boolean gcHappened = false;

        long start = System.nanoTime();
        long gcHappenedTime = 0;
        while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < MAX_WAIT_MSECS) {
            try {
                TimeUnit.MILLISECONDS.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            long afterGcCount = countGc(enabledBeans);

            if (!gcHappened) {
                if (afterGcCount - beforeGcCount >= 2) {
                    gcHappened = true;
                }
            }
            if (gcHappened) {
                if (afterGcCount == beforeGcCount) {
                    if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - gcHappenedTime) > STABLE_TIME_MSECS) {
                        return true;
                    }
                } else {
                    gcHappenedTime = System.nanoTime();
                    beforeGcCount = afterGcCount;
                }
            }
        }
        if (gcHappened) {
            System.err.println(
                    "WARNING: System.gc() was invoked but unable to wait while GC stopped, is GC too asynchronous?");
        } else {
            System.err.println(
                    "WARNING: System.gc() was invoked but couldn't detect a GC occurring, is System.gc() disabled?");
        }
        return false;
    }

    private static long countGc(final List<GarbageCollectorMXBean> _enabledBeans) {
        long cnt = 0;
        for (GarbageCollectorMXBean bean : _enabledBeans) {
            cnt += bean.getCollectionCount();
        }
        return cnt;
    }

    public static String getJmapExcutable() {
        return getJreExecutable("jmap");
    }

    public static String getJcmdExecutable() {
        return getJreExecutable("jcmd");
    }

    public static String getJreExecutable(String executableName) {
        String javaHome = System.getProperty("java.home");
        String jreDir = File.separator + "jre";
        if (javaHome.endsWith(jreDir)) {
            javaHome = javaHome.substring(0, javaHome.length() - jreDir.length());
        }
        return (javaHome + File.separator + "bin" + File.separator + executableName + (Utils.isWindows() ? ".exe"
                : ""));
    }

    public static long printHeapHistogram(PrintStream out, int _maxLines) {
        long _totalBytes = 0;
        boolean _partial = false;
        try {
            Process proc = Runtime.getRuntime()
                    .exec(new String[]{getJmapExcutable(), "-histo", Long.toString(Utils.getPid())});
            InputStream in = proc.getInputStream();
            LineNumberReader r = new LineNumberReader(new InputStreamReader(in));
            String s;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(buffer);
            while ((s = r.readLine()) != null) {
                if (s.startsWith("Total")) {
                    ps.println(s);
                    String[] sa = s.split("\\s+");
                    _totalBytes = Long.parseLong(sa[2]);
                } else if (r.getLineNumber() <= _maxLines) {
                    ps.println(s);
                } else {
                    if (!_partial) {
                        ps.println("[ ... truncated ... ]");
                    }
                    _partial = true;
                }
            }
            r.close();
            in.close();
            ps.close();
            byte[] _histoOuptut = buffer.toByteArray();
            buffer = new ByteArrayOutputStream();
            ps = new PrintStream(buffer);
            ps.println("[Heap Histogram Live Objects] used=" + _totalBytes);
            ps.write(_histoOuptut);
            ps.println();
            ps.close();
            out.write(buffer.toByteArray());
        } catch (Exception ex) {
            System.err.println("ForcedGcMemoryProfiler: error attaching / reading histogram");
            ex.printStackTrace();
        }
        return _totalBytes;
    }

    public static List<Pair<String, Long>> printNativeMemoryUsage(PrintStream out) {
        List<Pair<String, Long>> memList = new LinkedList<>();
        try {
            Process proc = Runtime.getRuntime()
                    .exec(new String[]{getJcmdExecutable(), Long.toString(Utils.getPid()), "VM.native_memory",
                            "summary"});
            InputStream in = proc.getInputStream();
            String nativeMemoryOutput =
                    new BufferedReader(new InputStreamReader(in)).lines().collect(Collectors.joining("\n"));
            out.println(nativeMemoryOutput);

            Matcher matcher = JCMD_NATIVE_COMMITTED_MEMORY_PATTERN.matcher(nativeMemoryOutput);
            while (matcher.find()) {
                // jcmd outputs data in KB, so translate that to B to match our other outputs
                memList.add(new Pair<>(matcher.group(1), Long.parseLong(matcher.group(2)) * 1024));
            }
        } catch (Exception ex) {
            System.err.println("ForcedGcMemoryProfiler: error attaching / reading native memory usage with jcmd");
            ex.printStackTrace();
        }
        return memList;
    }

    int iterationNumber = 0;

    @Override
    public Collection<? extends Result<?>> afterIteration(final BenchmarkParams benchmarkParams,
                                                          final IterationParams iterationParams,
                                                          final IterationResult result) {
        if (runOnlyAfterLastIteration) {
            if (iterationParams.getType() != IterationType.MEASUREMENT
                    || iterationParams.getCount() != ++iterationNumber) {
                return Collections.emptyList();
            }
        }
        List<Result<?>> l = new ArrayList<>(Arrays.asList(
                new OptionalScalarResult("+forced-gc-mem.gcTimeMillis", (double) gcTimeMillis, "ms",
                        AggregationPolicy.AVG),
                new OptionalScalarResult("+forced-gc-mem.heapUsed.jmap", (double) usedHeapViaHistogram, "bytes",
                        AggregationPolicy.AVG)));
        if (usageAfterIteration != null) {
            l.addAll(Arrays.asList(new OptionalScalarResult("+forced-gc-mem.nonHeapUsed.settled",
                            (double) usageAfterSettled.nonHeap.getUsed(), "bytes", AggregationPolicy.AVG),
                    new OptionalScalarResult("+forced-gc-mem.nonHeapUsed",
                            (double) usageAfterIteration.nonHeap.getUsed(), "bytes", AggregationPolicy.AVG),
                    new OptionalScalarResult("+forced-gc-mem.totalCommitted.settled",
                            (double) usageAfterSettled.getTotalCommitted(), "bytes", AggregationPolicy.AVG),
                    new OptionalScalarResult("+forced-gc-mem.totalCommitted",
                            (double) usageAfterIteration.getTotalCommitted(), "bytes", AggregationPolicy.AVG),
                    new OptionalScalarResult("+forced-gc-mem.heapUsed.settled",
                            (double) usageAfterSettled.heap.getUsed(), "bytes", AggregationPolicy.AVG),
                    new OptionalScalarResult("+forced-gc-mem.heapUsed", (double) usageAfterIteration.heap.getUsed(),
                            "bytes", AggregationPolicy.AVG)));
        }
        if (nativeMemoryUsage != null) {
            l.addAll(nativeMemoryUsage.stream()
                    .map(v -> new OptionalScalarResult("+forced-gc-mem.jcmd." + v.getLeft(), (double) v.getRight(),
                            "bytes", AggregationPolicy.AVG)).collect(Collectors.toList()));
        }
        // Record metaspace, code cache, and compressed class space (all are non-heap) usage
        for (MemoryPoolMXBean memoryMXBean : ManagementFactory.getMemoryPoolMXBeans()) {
            if ("Metaspace".equals(memoryMXBean.getName())) {
                l.add(new OptionalScalarResult("+forced-gc-mem.metaspaceUsed",
                        (double) memoryMXBean.getUsage().getUsed(), "bytes", AggregationPolicy.AVG));
            } else if ("Code Cache".equals(memoryMXBean.getName())) {
                l.add(new OptionalScalarResult("+forced-gc-mem.codeCacheUsed",
                        (double) memoryMXBean.getUsage().getUsed(), "bytes", AggregationPolicy.AVG));
            } else if ("Compressed Class Space".equals(memoryMXBean.getName())) {
                l.add(new OptionalScalarResult("+forced-gc-mem.compressedClassSpaceUsed",
                        (double) memoryMXBean.getUsage().getUsed(), "bytes", AggregationPolicy.AVG));
            }
        }
        LinuxVmProfiler.addLinuxVmStats("+forced-gc-mem.linuxVm", l);
        keepReference = null;
        return l;
    }

    @Override
    public void beforeIteration(final BenchmarkParams benchmarkParams, final IterationParams iterationParams) {
        usageAfterIteration = usageAfterSettled = null;
        enabled = true;
    }

    @Override
    public String getDescription() {
        return "Adds used memory to the result, if recorded via recordUsedMemory()";
    }

    private static class UsageTuple {
        MemoryUsage heap;
        MemoryUsage nonHeap;

        public UsageTuple(final MemoryUsage heapUsage, final MemoryUsage nonHeapUsage) {
            heap = heapUsage;
            nonHeap = nonHeapUsage;
        }

        public long getTotalUsed() {
            return heap.getUsed() + nonHeap.getUsed();
        }

        public long getTotalCommitted() {
            return heap.getCommitted() + nonHeap.getCommitted();
        }
    }
}
