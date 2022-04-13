/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class CmdParserTest {

    @Test
    void GIVEN_parser_WHEN_command_THEN_success() {
        String[] cmdParts = CmdParser.parse("r1 r2 r3 r4");
        assertEquals("r1", cmdParts[0]);
        assertEquals("r2", cmdParts[1]);
        assertEquals("r3", cmdParts[2]);
        assertEquals("r4", cmdParts[3]);
    }

    @Test
    void GIVEN_parser_WHEN_command_with_double_quote_THEN_success() {
        String[] cmdParts = CmdParser.parse("r1 \"r2 extra\" r3 r4");
        assertEquals("r1", cmdParts[0]);
        assertEquals("r2 extra", cmdParts[1]);
        assertEquals("r3", cmdParts[2]);
        assertEquals("r4", cmdParts[3]);
    }

    @Test
    void GIVEN_parser_WHEN_command_with_double_unclosed_quote_THEN_success() {
        String[] cmdParts = CmdParser.parse("r1 \"r2 extra\" r3 \"r4 extra");
        assertEquals("r1", cmdParts[0]);
        assertEquals("r2 extra", cmdParts[1]);
        assertEquals("r3", cmdParts[2]);
        assertEquals("r4", cmdParts[3]);
        assertEquals("extra", cmdParts[4]);
    }

    @Test
    void GIVEN_parser_WHEN_command_with_double_double_quote_THEN_success() {
        String[] cmdParts = CmdParser.parse("r1 \"\"r2 extra\"\" r3 \"r4 extra\"");
        assertEquals("r1", cmdParts[0]);
        assertEquals("", cmdParts[1]);
        assertEquals("r2", cmdParts[2]);
        assertEquals("extra", cmdParts[3]);
        assertEquals("", cmdParts[4]);
        assertEquals("r3", cmdParts[5]);
        assertEquals("r4 extra", cmdParts[6]);
    }

    @Test
    void GIVEN_parser_WHEN_command_with_single_quote_THEN_success() {
        String[] cmdParts = CmdParser.parse("r1 'r2 extra' r3 r4");
        assertEquals("r1", cmdParts[0]);
        assertEquals("r2 extra", cmdParts[1]);
        assertEquals("r3", cmdParts[2]);
        assertEquals("r4", cmdParts[3]);
    }

    @Test
    void GIVEN_parser_WHEN_command_with_single_unclosed_quote_THEN_success() {
        String[] cmdParts = CmdParser.parse("r1 'r2 extra' r3 'r4 extra");
        assertEquals("r1", cmdParts[0]);
        assertEquals("r2 extra", cmdParts[1]);
        assertEquals("r3", cmdParts[2]);
        assertEquals("r4", cmdParts[3]);
        assertEquals("extra", cmdParts[4]);
    }

    @Test
    void GIVEN_parser_WHEN_command_with_double_single_quote_THEN_success() {
        String[] cmdParts = CmdParser.parse("r1 ''r2 extra'' r3 'r4 extra'");
        assertEquals("r1", cmdParts[0]);
        assertEquals("", cmdParts[1]);
        assertEquals("r2", cmdParts[2]);
        assertEquals("extra", cmdParts[3]);
        assertEquals("", cmdParts[4]);
        assertEquals("r3", cmdParts[5]);
        assertEquals("r4 extra", cmdParts[6]);
    }

    @Test
    void GIVEN_parser_WHEN_command_with_double_and_single_quote_THEN_success() {
        String[] cmdParts = CmdParser.parse("r1 \"r2 extra\" r3 'r4 extra'");
        assertEquals("r1", cmdParts[0]);
        assertEquals("r2 extra", cmdParts[1]);
        assertEquals("r3", cmdParts[2]);
        assertEquals("r4 extra", cmdParts[3]);
    }
}
