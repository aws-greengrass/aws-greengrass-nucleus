#  Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
#  SPDX-License-Identifier: Apache-2.0

"""
Script to upload test metrics to CloudWatch
"""
import json
import os
import subprocess

import boto3

try:
    # Try getting type hints when developing but we don't need it in prod
    from boto3_type_annotations.cloudwatch import Client
except:
    pass
from retryable import retry


def batch(iterable, batch_size=1):
    length = len(iterable)
    for index in range(0, length, batch_size):
        yield iterable[index:min(index + batch_size, length)]


@retry()
def put_metrics_retryable(cw, namespace, datapoints):
    # Returns None, will throw if an error occurs
    cw.put_metric_data(
        Namespace=namespace,
        MetricData=datapoints
    )


@retry()
def put_dashboard_retryable(cw, dashboard_name, dashboard_data):
    cw.put_dashboard(DashboardName=dashboard_name, DashboardBody=json.dumps(dashboard_data))


def convert_units(input_unit):
    """
    Convert units into CloudWatch understandable units.
    """
    input_unit = input_unit.lower()
    if input_unit == "s/op":
        return "Seconds"
    elif input_unit == "bytes" or input_unit == "byte":
        return "Bytes"
    elif input_unit == "op/s":
        return "Count/Second"
    elif input_unit == "ms":
        return "Milliseconds"
    else:
        print("Unknown unit type", input_unit)


def main():
    namespace = "Evergreen/Benchmark"
    with open("jmh-result.json", "r") as f:
        report = json.load(f)

    cw = boto3.client("cloudwatch")  # type: Client
    datapoints = []
    event_type = os.getenv("GITHUB_EVENT_NAME", "pull_request")
    secondary_metric_names = []

    # Generate CloudWatch metrics from our benchmarks
    for benchmark in report:
        dims = [
            {
                "Name": "Benchmark",
                "Value": benchmark["benchmark"]
            },
            {
                "Name": "GitHub Event",
                "Value": event_type
            }
        ]
        datapoints.append({
            "MetricName": "ExecutionTime",
            "Value": benchmark["primaryMetric"]["score"],
            "Dimensions": dims,
            "Unit": convert_units(benchmark["primaryMetric"]["scoreUnit"])
        })
        if "secondaryMetrics" in benchmark:
            for metric_name, values in benchmark["secondaryMetrics"].items():
                datapoints.append({
                    "MetricName": metric_name,
                    "Value": values["score"],
                    "Unit": convert_units(values["scoreUnit"]),
                    "Dimensions": dims
                })
                secondary_metric_names.append(metric_name)

    if event_type == "push":
        # Put metrics up to CloudWatch in batches of 20 (their max limit)
        for b in batch(datapoints, 20):
            put_metrics_retryable(cw, namespace, b)

        num_commit_history = 50
        os.system(f"git fetch --depth={num_commit_history} origin master")
        # Get the last 50 merges to master with the short commit hash and commiter's date
        # Format like: 43a4929 2019-11-24T11:29:22-08:00
        merges_to_master = subprocess.check_output(["git", "log", "-n", str(num_commit_history), "--merges",
                                                    "--first-parent", "origin/master",
                                                    "--pretty=format:%h %cI"]).decode("utf-8").strip().split("\n")

        annotations = {
            "vertical": [
                {
                    "label": merge.split(" ")[0],
                    "value": merge.split(" ")[1],
                    "color": "#16b"  # Annotate with CloudWatch blue
                } for merge in merges_to_master
            ]
        }

        # Update dashboard with the latest classes of metrics.
        # This dashboard creates 1 graph for each metric type (ie. execution time, heap used, etc).

        width = 12
        height = 8
        region = os.getenv("AWS_REGION")
        period = 300
        dashboard_data = {
            "widgets": [
                {
                    "type": "metric",
                    "x": 0,
                    "y": 0,
                    "width": 24,
                    "height": height,
                    "properties": {
                        "view": "timeSeries",
                        "stacked": False,
                        "metrics": [
                            [{
                                "expression": f"SEARCH('{{{namespace},Benchmark,\"GitHub Event\"}} "
                                              "MetricName=\"ExecutionTime\"', 'Maximum', 300)",
                                "id": "e1", "period": period}]
                        ],
                        "region": region,
                        "title": "ExecutionTime",
                        "annotations": annotations,
                        "yAxis": {
                            "left": {
                                "showUnits": False
                            }
                        }
                    }
                },
                *[{
                    "type": "metric",
                    "x": (i % int(24 / width)) * width,
                    "y": height + i * height,
                    "width": width,
                    "height": height,
                    "properties": {
                        "view": "timeSeries",
                        "stacked": False,
                        "metrics": [
                            [{
                                "expression": f"SEARCH('{{{namespace},Benchmark,\"GitHub Event\"}} "
                                              f"MetricName=\"{name}\"', 'Maximum', 300)",
                                "id": "e1", "period": period}]
                        ],
                        "region": region,
                        "title": name,
                        "annotations": annotations,
                        "yAxis": {
                            "left": {
                                "showUnits": False
                            }
                        }
                    }
                } for i, name in enumerate(secondary_metric_names)]
            ]
        }
        put_dashboard_retryable(cw, "EvergreenBenchmarks", dashboard_data)

        # Only continue to run for pull requests
        return


if __name__ == "__main__":
    main()
