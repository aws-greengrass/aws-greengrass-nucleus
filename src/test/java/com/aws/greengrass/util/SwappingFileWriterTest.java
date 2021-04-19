/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(GGExtension.class)
class SwappingFileWriterTest {
    protected static final String OUTPUT_STRING = "garbageABC\nDEF\nGHI\n";
    @TempDir
    Path temp;

    Path outputFile;

    @BeforeEach
    void beforeEach() {
        outputFile = temp.resolve("abc.txt");
    }

    @Test
    void test() throws IOException {
        Files.write(outputFile, "garbage".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE);
        Files.write(SwappingFileWriter.getWriteFile(outputFile), "some garbage".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE);
        try (Writer writer = new SwappingFileWriter(outputFile)) {
            writer.append("ABC\n");
            writer.append("DEF\n");
            writer.append("GHI\n");
        }
        assertEquals(new String(Files.readAllBytes(outputFile)), OUTPUT_STRING);
        assertEquals(new String(Files.readAllBytes(SwappingFileWriter.getWriteFile(outputFile))), OUTPUT_STRING);
    }
}
