/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.io.FileMatchers.anExistingFile;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith({
        GGExtension.class, MockitoExtension.class
})
class CommitableTest {
    @TempDir
    Path temp;
    Path testFile;

    @BeforeEach
    void beforeEach() throws Exception {
        testFile = temp.resolve("test");

        try (CommitableWriter out = CommitableWriter.abandonOnClose(testFile)) {
            out.write("rev1");
            out.commit();
        }

        StringBuilder sb = new StringBuilder();
        CommitableReader.of(testFile).read(getValidator(sb));
        assertEquals("rev1", sb.toString());
    }

    @Test
    void testAbandonOnCloseAndRead() throws Exception {
        try (CommitableWriter out = CommitableWriter.abandonOnClose(testFile)) {
            out.write("rev2");
        }
        StringBuilder sb = new StringBuilder();
        CommitableReader.of(testFile).read(getValidator(sb));
        assertEquals("rev1", sb.toString());

        assertThat(CommitableFile.getBackupFile(testFile).toFile(), not(anExistingFile()));
    }

    @Test
    void testCommitOnCloseAndRead() throws Exception {
        try (CommitableWriter out = CommitableWriter.commitOnClose(testFile)) {
            out.write("rev2");
        }
        StringBuilder sb = new StringBuilder();
        CommitableReader.of(testFile).read(getValidator(sb));
        assertEquals("rev2", sb.toString());

        assertThat(CommitableFile.getBackupFile(testFile).toFile(), anExistingFile());
        assertEquals("rev1", getFileContent(CommitableFile.getBackupFile(testFile)));
    }

    @Test
    void testReadRecovery(ExtensionContext context) throws Exception {
        ignoreExceptionWithMessage(context, "Mock error: Corrupted file");

        try (CommitableWriter out = CommitableWriter.commitOnClose(testFile)) {
            out.write("rev2*");
        }
        StringBuilder sb = new StringBuilder();
        CommitableReader.of(testFile).read(getValidator(sb));
        assertEquals("rev1", sb.toString());

        assertThat(CommitableFile.getBackupFile(testFile).toFile(), not(anExistingFile()));
    }

    private String getFileContent(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("PMD.AssignmentInOperand")
    private CrashableFunction<InputStream, Void, IOException> getValidator(StringBuilder sb) {
        return is -> {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("*")) {
                    throw new IOException("Mock error: Corrupted file");
                }
                sb.append(line);
            }
            return null;
        };
    }

}
