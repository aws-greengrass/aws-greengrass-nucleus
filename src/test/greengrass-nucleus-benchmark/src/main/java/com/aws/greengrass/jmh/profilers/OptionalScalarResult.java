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

package com.aws.greengrass.jmh.profilers;

import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.Aggregator;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.ResultRole;
import org.openjdk.jmh.results.ScalarResult;
import org.openjdk.jmh.util.ListStatistics;
import org.openjdk.jmh.util.Statistics;

import java.util.Collection;

/**
 * Same as {@link ScalarResult} but don't fill missing values with 0.
 */
public class OptionalScalarResult extends Result<OptionalScalarResult> {

    public OptionalScalarResult(String label, double n, String unit, AggregationPolicy policy) {
        this(label, of(n), unit, policy);
    }

    OptionalScalarResult(String label, Statistics s, String unit, AggregationPolicy policy) {
        super(ResultRole.SECONDARY, label, s, unit, policy);
    }

    @Override
    protected Aggregator<OptionalScalarResult> getThreadAggregator() {
        return new MyScalarResultAggregator();
    }

    @Override
    protected Aggregator<OptionalScalarResult> getIterationAggregator() {
        return new MyScalarResultAggregator();
    }

    @Override
    protected OptionalScalarResult getZeroResult() {
        return null;
    }

    AggregationPolicy getPolicy() {
        return policy;
    }

    private static class MyScalarResultAggregator implements Aggregator<OptionalScalarResult> {
        @Override
        public OptionalScalarResult aggregate(Collection<OptionalScalarResult> results) {
            ListStatistics stats = new ListStatistics();
            for (OptionalScalarResult r : results) {
                stats.addValue(r.getScore());
            }
            OptionalScalarResult first = results.iterator().next();
            return new OptionalScalarResult(first.getLabel(), stats, first.getScoreUnit(), first.getPolicy());
        }
    }
}
