/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

/**
 * A writer which utilizes two in-sync files and swaps pointers on every write such that the "read" file is never
 * corrupt.
 */
public final class SwappingFileWriter extends Writer {
    private final Path readFilePath;
    private final Path writeFilePath;
    private final Path swapFilePath;
    private BufferedWriter writeFile;
    private BufferedWriter readFile;
    private boolean closed;

    /**
     * Creates a new instance of SwappingFileWriter.
     *
     * @param output path of the "read" file
     * @throws IOException when creating paths, writers, or copying fails
     */
    public SwappingFileWriter(Path output) throws IOException {
        super();
        readFilePath = output;
        writeFilePath = getWriteFile(output);
        swapFilePath = getSwappingFile(output);
        Utils.createPaths(readFilePath.getParent());

        // Cleanup any old write file if it exists
        Files.deleteIfExists(writeFilePath);
        // Sync read and write files if the read file exists
        if (Files.exists(readFilePath)) {
            Files.copy(readFilePath, writeFilePath, StandardCopyOption.REPLACE_EXISTING);
        }

        // Now we know that the read and write files are exactly the same so we can safely begin to simply append
        // to them without any additional, larger, copies.

        readFile = Files.newBufferedWriter(readFilePath, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        writeFile = Files.newBufferedWriter(writeFilePath, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND, StandardOpenOption.SYNC);
    }

    protected static Path getWriteFile(Path path) {
        return path.resolveSibling(path.getFileName() + ".write");
    }

    public static Path getSwappingFile(Path path) {
        return path.resolveSibling(path.getFileName() + ".swaptemp");
    }

    @Override
    public synchronized void write(char[] cbuf, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("Cannot write to a closed writer");
        }

        // write into the write file, swap pointers, write into what was the read file
        writeFile.write(cbuf, off, len);
        flush();
        swap();
        // looks like we're writing into the same file, but we've just swapped pointers, so this is the file
        // which was previously the read file.
        writeFile.write(cbuf, off, len);
        flush();
    }

    @Override
    public synchronized void flush() throws IOException {
        writeFile.flush();
        readFile.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            writeFile.close();
            readFile.close();
            closed = true;
        }
    }

    @SuppressWarnings("PMD.CloseResource")
    protected synchronized void swap() throws IOException {
        if (Files.exists(writeFilePath)) {
            // Swap on the filesystem

            // read file moves to swap
            Files.move(readFilePath, swapFilePath, ATOMIC_MOVE);
            // Until the next line, there is _no_ read file existing. Any reader needs to handle this case.

            // write moves to read
            Files.move(writeFilePath, readFilePath, ATOMIC_MOVE);
            // swap moves to write
            Files.move(swapFilePath, writeFilePath, ATOMIC_MOVE);

            // Swap write pointers in memory
            BufferedWriter newReadFile = writeFile;
            writeFile = readFile;
            readFile = newReadFile;
        }
    }
}
