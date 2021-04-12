#  Copyright Amazon.com Inc. or its affiliates.
#  SPDX-License-Identifier: Apache-2.0

import argparse
import json
import os
import xml.etree.ElementTree as ET

from agithub.GitHub import GitHub


def findall_recursive(node, element):
    for item in node.findall(element):
        yield item
    for item in list(node):
        yield from findall_recursive(item, element)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', type=argparse.FileType('r'), help='Input file to parse')
    parser.add_argument('--token', type=str, help='GitHub token')
    parser.add_argument('--pr', type=str, help='GitHub PR')
    parser.add_argument('--sha', type=str, help='GitHub SHA')
    args = parser.parse_args()

    file = args.input

    my_body_dedupe = "Produced by binaryCompatability.py"
    body = f"Binary incompatibility detected for commit {args.sha}.\n" \
           f" See the uploaded artifacts from the action for details. You must bump the version number.\n\n"

    incompatible = False
    tree = ET.parse(file)
    for clazz in tree.getroot().findall("./classes/class"):
        any_incompatible = False
        body += f"{clazz.attrib['fullyQualifiedName']} is "
        if clazz.attrib['binaryCompatible'] == "false":
            body += "binary incompatible"
            incompatible = True
            any_incompatible = True
        if clazz.attrib['sourceCompatible'] == "false":
            if any_incompatible:
                body += " and is "
            body += "source incompatible"
            any_incompatible = True
        if any_incompatible:
            body += f" because of " \
                    f"{', '.join({x.text for x in findall_recursive(clazz, 'compatibilityChanges/compatibilityChange')})}"
        else:
            body += "fully compatible"
        body += "\n"
    body += "\n" + my_body_dedupe

    token = args.token
    pr = args.pr

    gh = GitHub(token=token)
    existing_comments = gh.repos[os.getenv("GITHUB_REPOSITORY")].issues[pr].comments.get()
    if existing_comments[0] == 200:
        existing_comments = list(filter(lambda i: my_body_dedupe in i["body"], existing_comments[1]))
    else:
        existing_comments = []

    if existing_comments:
        comment_id = existing_comments[0]["id"]
        if incompatible:
            updated_issue = gh.repos[os.getenv("GITHUB_REPOSITORY")].issues.comments[comment_id].patch(body={"body": body})
            print(updated_issue, flush=True)
        else:
            gh.repos[os.getenv("GITHUB_REPOSITORY")].issues.comments[comment_id].delete()
    elif incompatible:
        issue = gh.repos[os.getenv("GITHUB_REPOSITORY")].issues[pr].comments.post(body={"body": body})
        print(issue, flush=True)


if __name__ == '__main__':
    main()
