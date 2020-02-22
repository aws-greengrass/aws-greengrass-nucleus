package com.aws.iot.evergreen.packagemanagement.model;

import com.vdurmont.semver4j.Semver;
import lombok.NonNull;
import lombok.Value;

@Value
public class VersionConstraint {

    @NonNull ComparisonOperator comparisonOperator;

    @NonNull Semver version;

    public enum ComparisonOperator {
        EQUAL("="), LARGER(">"), LESS("<"), LARGEROREQUAL(">="), LESSOREQUAL("<=");

        private String symbol;

        ComparisonOperator(String symbol) {
            this.symbol = symbol;
        }
    }
}
