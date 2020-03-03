#  Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
#  SPDX-License-Identifier: Apache-2.0

"""
Script to upload test metrics to CloudWatch and comment on GitHub pull request
"""
import json
import os
import sys
from collections import defaultdict
from datetime import datetime, timedelta

import boto3
from retryable import retry
from github import Github


def batch(iterable, batch_size=1):
    length = len(iterable)
    for index in range(0, length, batch_size):
        yield iterable[index:min(index + batch_size, length)]


@retry()
def put_metrics_retryable(cw, datapoints):
    # Returns None, will throw if an error occurs
    cw.put_metric_data(
        Namespace="Evergreen/Testing",
        MetricData=datapoints
    )


@retry(count=10, delay=10)
def comment_on_pr(comment, pr_number):
    gh = Github(sys.argv[1])
    repo = gh.get_repo(os.getenv("GITHUB_REPOSITORY"))
    pr = repo.get_pull(pr_number)
    pr.create_issue_comment(comment)


def main():
    with open("target/surefire-reports/junitReport.json", "r") as f:
        report = json.load(f)
    with open(os.getenv("GITHUB_EVENT_PATH"), "r") as f:
        github_event = json.load(f)

    cw = boto3.client("cloudwatch")
    datapoints = []
    current_metrics = defaultdict(dict)
    event_type = os.getenv("GITHUB_EVENT_NAME", "pull_request")

    # For each test in the report
    for test_run in report:
        # For each metric key and value (excluding name and classname since they aren't metrics)
        for k, v in test_run.items():
            if k != "name" and k != "classname":
                test_path = test_run["classname"] + "." + test_run["name"]
                current_metrics[test_path + " " + k] = v
                datapoints.append({
                    "MetricName": k,
                    "Value": v,
                    "Dimensions": [
                        {
                            "Name": "Test Path",
                            "Value": test_path
                        },
                        {
                            "Name": "GitHub Event",
                            "Value": event_type
                        }
                    ]
                })

    if event_type == "push":
        # Put metrics up to cloudwatch in batches of 20 (their max limit)
        for b in batch(datapoints, 20):
            put_metrics_retryable(cw, b)
        # Only continue to run for pull requests
        return

    # Get metrics from the last push to master to compare the current metrics to
    old_metrics_result = cw.get_metric_data(MetricDataQueries=[
        {
            "Id": "last_push",
            "Expression": f"SEARCH('{{Evergreen/Testing,\"GitHub Event\",\"Test Path\"}} \"GitHub "
                          f"Event\"=\"push\"', 'Maximum', 60)",
        }
    ],
        # Look up to 7 days in the past for the last push to master to compare against
        StartTime=datetime.utcnow() - timedelta(days=7),
        EndTime=datetime.utcnow(),
        ScanBy="TimestampDescending")

    # If there are any error messages, print them out so that we can do something about it
    if old_metrics_result["Messages"]:
        print(old_metrics_result["Messages"])
    prev_metrics = old_metrics_result["MetricDataResults"]

    prev_metric_map = {}
    for old_metric in reversed(prev_metrics):
        if old_metric["Label"] in current_metrics:
            old_value = max(old_metric["Values"])
            prev_metric_map[old_metric["Label"]] = old_value

    table = "| Measurement | Value | Change | Test |\n| - | - | - | - |\n"
    for test_path, v in current_metrics.items():
        change_str = "N/A"
        if test_path in prev_metric_map:
            change = v - prev_metric_map[test_path]
            change_str = str(change)
            if change > 0:
                change_str = f"+{change}"
            # Add emoji for changes over 10%
            if abs(change / v) > .1:
                change_str = f"💥 {change_str}"
        table += f"|{test_path.split(' ')[-1]}|{v}|{change_str}|{' '.join(test_path.split(' ')[0:-1])}|\n"

    print(table)
    if "number" in github_event and len(sys.argv) == 2:
        comment_on_pr(table, github_event["number"])


if __name__ == "__main__":
    main()
