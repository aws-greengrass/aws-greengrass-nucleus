#!/bin/sh
#
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
#

# If no options provided, will unzip and install the CLI and local dashboard
#
# OPTIONS
# [-r|--root] <root dir>: specify Greengrass root directory for installation
# [-f|--fresh-install]: delete previous Greengrass installation if exists
# [-ld|--launch-default]: after install, launch Greengrass with default params for provision and running

# defaults
GG_ROOT_DIR="/greengrass/v2"
HTTP_INSTALL_DIR="$GG_ROOT_DIR/plugins/trusted"
FRESH_INSTALL=false
LAUNCH_WITH_DEFAULT=false
AWS_REGION="us-east-1"

# expected files
NUCLEUS_ZIP="aws.greengrass.nucleus.zip"
CLI_ZIP="aws-greengrass-cli.zip"
CLI_DIR="cli-1.0-SNAPSHOT"
HTTP_JAR="aws-greengrass-http.jar"

if [ "$(whoami)" != "root" ]; then
  echo "You must run the installer as root"
  echo "Suggestion: sudo $0 $@"
  echo "Want us to run this for you? (y/n)"
  read -r choice
  case "$choice" in
    y|Y) exec sudo "$0" "$@";;
    *) echo "Quitting.";;
  esac
  exit 1
fi

# make sure required file/directory exist
for i in $NUCLEUS_ZIP $CLI_ZIP $HTTP_JAR ; do
  if [ ! -e $i ] ; then
    echo "Install failed. $i not found"
    exit 1
  fi
done

# get arguments
for arg in "$@" ; do
  case $arg in
    -r|--root)
    GG_ROOT_DIR=$2
    HTTP_INSTALL_DIR="$GG_ROOT_DIR/plugins/trusted"
    shift
    ;;
    -f|--fresh-install)
    FRESH_INSTALL=true
    shift
    ;;
    -ld|--launch-default)
    LAUNCH_WITH_DEFAULT=true
    shift
    ;;
  esac
done

# remove existing installation if requested
if "$FRESH_INSTALL" && [ -d "$GG_ROOT_DIR" ]; then
  echo "Attempting fresh install. This will delete the existing directory: $GG_ROOT_DIR"
  echo "Are you sure (y/n)?"
  read -r choice
  case "$choice" in
    y|Y) rm -rf "$GG_ROOT_DIR";;
    *) echo "Aborted.";;
  esac
fi

GG_INSTALL_DIR="$GG_ROOT_DIR"/first-install
mkdir -p "$GG_INSTALL_DIR"

# unzip
(unzip $CLI_ZIP && unzip $NUCLEUS_ZIP -d "$GG_INSTALL_DIR") || { echo "Install failed. Cannot unzip"; exit 1; }

# install CLI if not already
if type 'greengrass-cli' > /dev/null 2>&1; then
  echo "Greengrass CLI already installed"
else
  echo "Installing Greengrass CLI"
  ./"$CLI_DIR/install.sh" || { echo 'Install failed. cannot install CLI'; exit 1; }
fi

# copy over local dashboard jar
mkdir -p "$HTTP_INSTALL_DIR" || { echo 'Install failed. cannot create local dashboard directory'; exit 1; }
cp $HTTP_JAR "$HTTP_INSTALL_DIR/"
echo "local dashboard installed"

echo "Installation complete. Installed at: $GG_ROOT_DIR"

if "$LAUNCH_WITH_DEFAULT"; then
  echo "Starting Greengrass"
  java -Droot="$GG_ROOT_DIR" -Dlog.store=FILE -jar "$GG_INSTALL_DIR"/lib/Greengrass.jar \
    --aws-region "$AWS_REGION" --provision true --setup-tes true
else
  java -Droot="$GG_ROOT_DIR" -Dlog.store=FILE -jar "$GG_INSTALL_DIR"/lib/Greengrass.jar --start false
  echo "You can now start Greengrass"
fi
