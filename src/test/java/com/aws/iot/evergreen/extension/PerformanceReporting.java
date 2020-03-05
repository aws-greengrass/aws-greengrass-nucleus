/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SequenceWriter;
import com.fasterxml.jackson.jr.ob.JSON;
import com.fasterxml.jackson.jr.ob.JSONObjectException;
import com.fasterxml.jackson.jr.ob.ValueIterator;
import lombok.AllArgsConstructor;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.File;
import java.io.FileWriter;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class PerformanceReporting
        implements Extension, BeforeAllCallback, BeforeTestExecutionCallback, AfterTestExecutionCallback {
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create("com.aws.iot.evergreen.extension");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ScheduledExecutorService exec = new ScheduledThreadPoolExecutor(1);
    private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();
    private static final ClassLoadingMXBean CLASS_BEAN = ManagementFactory.getClassLoadingMXBean();
    private ScheduledFuture<?> running;
    private static File reportFile;

    @Override
    public void beforeTestExecution(ExtensionContext context) throws Exception {
        // Request that we first gc to make sure that we start as small as possible
        Runtime.getRuntime().gc();
        context.getStore(NAMESPACE).put(context.getDisplayName(), new LinkedList<RuntimeInfo>());

        // Refresh the stats every 50ms, taking the maximum of each value every time
        running = exec.scheduleAtFixedRate(() -> {
            ((List<RuntimeInfo>) context.getStore(NAMESPACE).get(context.getDisplayName()))
                    .add(new RuntimeInfo(MEMORY_BEAN.getHeapMemoryUsage().getUsed(),
                            MEMORY_BEAN.getNonHeapMemoryUsage().getUsed(), CLASS_BEAN.getLoadedClassCount()));
        }, 0, 50, TimeUnit.MILLISECONDS);
    }

    @Override
    public void afterTestExecution(ExtensionContext context) throws Exception {
        running.cancel(false);
        List<RuntimeInfo> infoList = (List<RuntimeInfo>) context.getStore(NAMESPACE).get(context.getDisplayName());
        double maxHeapMemoryMB = infoList.stream().mapToLong(x -> x.heapMemory).max().getAsLong() / 1024.0 / 1024.0;
        double maxNonHeapMemoryMB =
                infoList.stream().mapToLong(x -> x.nonHeapMemory).max().getAsLong() / 1024.0 / 1024.0;
        long maxLoadedClassCount = infoList.stream().mapToLong(x -> x.loadedClassCount).max().getAsLong();

        double avgHeapMemoryMB =
                infoList.stream().mapToLong(x -> x.heapMemory).average().getAsDouble() / 1024.0 / 1024.0;
        double avgNonHeapMemoryMB =
                infoList.stream().mapToLong(x -> x.nonHeapMemory).average().getAsDouble() / 1024.0 / 1024.0;
        double avgLoadedClassCount = infoList.stream().mapToLong(x -> x.loadedClassCount).average().getAsDouble();

        Map<String, Object> perfValueMap = new LinkedHashMap<>();
        perfValueMap.put("name", context.getRequiredTestMethod().getName());
        perfValueMap.put("classname", context.getRequiredTestClass().getName());
        perfValueMap.put("avgHeapMemoryMB", avgHeapMemoryMB);
        perfValueMap.put("maxHeapMemoryMB", maxHeapMemoryMB);
        perfValueMap.put("avgNonHeapMemoryMB", avgNonHeapMemoryMB);
        perfValueMap.put("maxNonHeapMemoryMB", maxNonHeapMemoryMB);
        perfValueMap.put("avgLoadedClassCount", avgLoadedClassCount);
        perfValueMap.put("maxLoadedClassCount", maxLoadedClassCount);

        ValueIterator<?> existing = null;
        try {
            existing = JSON.std.anySequenceFrom(reportFile);
        } catch (JSONObjectException ignored) {
        }

        try (FileWriter fileWriter = new FileWriter(reportFile, false);
             SequenceWriter seqWriter = MAPPER.writer().writeValuesAsArray(fileWriter)) {
            if (existing != null) {
                seqWriter.writeAll(existing.readAll());
            }
            seqWriter.write(perfValueMap);
        }
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        File surefireReportDir = new File(System.getProperty("surefireReportDir", "target/surefire-reports"));
        if (!surefireReportDir.exists()) {
            surefireReportDir.mkdirs();
        }

        reportFile = surefireReportDir.toPath().resolve("junitReport.json").toFile();
        if (!reportFile.exists()) {
            reportFile.createNewFile();
        }
    }

    @AllArgsConstructor
    private static class RuntimeInfo {
        long heapMemory;
        long nonHeapMemory;
        long loadedClassCount;
    }
}
