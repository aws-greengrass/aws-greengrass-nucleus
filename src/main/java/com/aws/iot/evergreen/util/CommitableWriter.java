/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */


package com.aws.iot.evergreen.util;

import java.io.*;
import java.nio.file.*;

public class CommitableWriter extends BufferedWriter implements Commitable {
    private final CommitableFile out;
    private CommitableWriter(CommitableFile f) {
        super(new OutputStreamWriter(f));
        out =f;
    }
    public static CommitableWriter of(Path p) throws IOException {
        return new CommitableWriter(CommitableFile.of(p));
    }
    public void commit() throws IOException {
        flush();
        out.commit();
    }

}
