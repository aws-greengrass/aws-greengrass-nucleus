/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import java.io.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class UnsyncBufferedInputStreamTest {

    @Test
    public void T1() {
        Tf1(5);
        Tf1(1 << 18 + 37);
    }
    @Test
    public void T2() {
        Tf2(5,3);
        Tf2((1 << 18) + 37, (1<<12)-1);
    }
    private void Tf1(int len) {
        try ( InputStream in = UnsyncBufferedInputStream.of(new sequential(len))) {
            in.mark(10);
            assertEquals(0, in.read());
            in.reset();
            int pos = 0;
            while (pos < len)
                assertEquals(pos++ & 0xFF, in.read());
            assertEquals(-1, in.read());
        } catch (IOException ex) {
            ex.printStackTrace(System.out);
            fail();
        }
    }
    private void Tf2(int len, int bl) {
        try ( InputStream in = UnsyncBufferedInputStream.of(new sequential(len))) {
            byte[] b = new byte[bl];
            in.mark(10);
            assertEquals(0, in.read());
            in.reset();
            int pos = 0;
            while (pos < len) {
                int nr = in.read(b);
//                System.out.println(nr);
                for (int i = 0; i < nr; i++)
                    assertEquals(pos++ & 0xFF, b[i]&0xFF);
            }
            assertEquals(-1, in.read());
        } catch (IOException ex) {
            ex.printStackTrace(System.out);
            fail();
        }
    }

    private class sequential extends InputStream {
        private int pos;
        private final int len;
        public sequential(int l) {
            len = l;
        }
        @Override
        public int read() throws IOException {
            return pos >= len ? -1 : pos++ & 0xFF;
        }
    }

}
