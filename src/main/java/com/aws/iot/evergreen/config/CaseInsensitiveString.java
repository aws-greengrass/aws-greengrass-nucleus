/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.config;

import lombok.NonNull;

import java.util.Objects;

public class CaseInsensitiveString implements CharSequence {
    private final String value;
    private String lower;

    public CaseInsensitiveString(@NonNull String value) {
        this.value = value;
    }

    private String getLower() {
        if (lower == null) {
            lower = value.toLowerCase();
        }
        return lower;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CaseInsensitiveString that = (CaseInsensitiveString) o;
        return getLower().equals(that.getLower());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getLower());
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public int length() {
        return value.length();
    }

    @Override
    public char charAt(int index) {
        return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return value.subSequence(start, end);
    }
}
