#  Copyright Amazon.com Inc. or its affiliates.
#  SPDX-License-Identifier: Apache-2.0

import argparse
import json
import os
import subprocess
import defusedxml.ElementTree as ET
from collections import defaultdict

from agithub.GitHub import GitHub


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cmd', type=str, help='Command to run')
    parser.add_argument('-i', type=int, help='Iterations')
    parser.add_argument('--token', type=str, help='GitHub token')
    parser.add_argument('--out-dir', type=str, help='Failed test output dir')
    parser.add_argument('-ff', action="store_true", help='Fail fast. If enabled, quit '
                                                         'after the first failure')
    args = parser.parse_args()

    command = args.cmd
    iterations = args.i
    token = args.token

    # Dict for results as a dict of classname -> method name -> [failure details]
    results = defaultdict(lambda: defaultdict(list))

    for i in range(0, iterations):
        print(f"Running iteration {i + 1} of {iterations}", flush=True)
        process = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=True)
        # If the tests failed, then we should check which test(s) failed in order to report it
        if process.returncode != 0:
            print(f"Iteration {i + 1} failed, saving and parsing results now", flush=True)
            os.makedirs(args.out_dir, exist_ok=True)
            with open(f'{args.out_dir}{i+1}-full-stdout.txt', 'w') as f:
                f.write(process.stdout.decode('utf-8'))
            with open(f'{args.out_dir}{i+1}-full-stderr.txt', 'w') as f:
                f.write(process.stderr.decode('utf-8'))

            parse_test_results(i, results, args.out_dir)
            if args.ff:
                break
        else:
            print("Succeeded with no failure", flush=True)

    if len(results) == 0:
        return

    print("Found some flakiness. Creating/updating GitHub issue.", flush=True)
    print(json.dumps(results), flush=True)

    gh = GitHub(token=token)
    title = "[Bot] Flaky Test(s) Identified"
    existing_issues = gh.repos[os.getenv("GITHUB_REPOSITORY")].issues.get(creator="app/github-actions")
    if existing_issues[0] == 200:
        existing_issues = list(filter(lambda i: title in i["title"], existing_issues[1]))
    else:
        existing_issues = []

    body = f"Flaky test(s) found for commit {os.getenv('GITHUB_SHA')}.\n" \
           f" See the uploaded artifacts from the action for details.\n\n"
    for test_class, v in results.items():
        for test_case, failures in v.items():
            body += f"- {test_class}.{test_case} failed {len(failures)} times over {iterations} iterations "
            unique_failure_reasons = set(map(lambda f: f["failure"], failures))
            body += f"with {len(unique_failure_reasons)} unique failures.\n"

    if existing_issues:
        issue_number = existing_issues[0]["number"]
        updated_issue = gh.repos[os.getenv("GITHUB_REPOSITORY")].issues[issue_number].patch(body={"body": body,
                                                                                                  "title": title})
        print(updated_issue, flush=True)
    else:
        issue = gh.repos[os.getenv("GITHUB_REPOSITORY")].issues.post(body={"body": body,
                                                                           "title": title})
        print(issue, flush=True)


def parse_test_results(iteration, previous_results, failed_test_dir):
    report_dir = "target/surefire-reports/"

    if not os.path.exists(report_dir):
        return
    reports = list(filter(lambda f: f.startswith("TEST-") and f.endswith(".xml"), os.listdir(report_dir)))
    for r in reports:
        tree = ET.parse(report_dir + r)
        for testcase in tree.getroot().findall("./testcase"):
            failure = None
            # Find failures and errors (there's no important difference between these for us)
            if testcase.find("failure") is not None:
                failure = testcase.find("failure").text
            elif testcase.find("error") is not None:
                failure = testcase.find("error").text
            if failure is None:
                continue

            previous_results[testcase.get("classname")][testcase.get("name")] \
                .append({"iteration": iteration, "failure": failure})
            # Save test stdout and stderr
            file_path_prefix = f'{failed_test_dir}{iteration}-{testcase.get("classname")}.{testcase.get("name")}-'
            if testcase.find("system-out") is not None:
                with open(f'{file_path_prefix}stdout.txt', 'w') as f:
                    f.write(testcase.find("system-out").text)
            if testcase.find("system-err") is not None:
                with open(f'{file_path_prefix}stderr.txt', 'w') as f:
                    f.write(testcase.find("system-err").text)
            # Save test failure exception traceback
            with open(f'{file_path_prefix}error.txt', 'w') as f:
                f.write(failure)


if __name__ == '__main__':
    main()
