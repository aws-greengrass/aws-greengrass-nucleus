#!/usr/bin/env bash

# defaults
GG_ROOT_DIR="/greengrass/v2"
HTTP_INSTALL_DIR="$GG_ROOT_DIR/plugins/trusted"
FRESH_INSTALL=false

# expected files
NUCLEUS_ZIP="aws.greengrass.nucleus.zip"
CLI_ZIP="aws-greengrass-cli.zip"
CLI_DIR="evergreen-cli-1.0-SNAPSHOT"
HTTP_JAR="aws-greengrass-http.jar"

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
  esac
done

# unzip
(unzip $CLI_ZIP && unzip $NUCLEUS_ZIP) || { echo "Install failed. Cannot unzip"; exit 1; }

# remove existing installation if requested
if "$FRESH_INSTALL" && [ -d "$GG_ROOT_DIR" ]; then
  echo "Attempting fresh install. This will delete existing directory: $GG_ROOT_DIR"
  read -r -p "Are you sure (y/n)?" choice
  case "$choice" in
    y|Y ) rm -rf "$GG_ROOT_DIR";;
    n|N ) echo "Aborted.";;
  esac
fi

# install CLI if not already
if ! type 'greengrass-cli' &> /dev/null; then
  echo "Installing Greengrass CLI"
  sudo bash "$CLI_DIR/install.sh" || { echo 'Install failed. cannot install CLI'; exit 1; }
else
  echo "Greengrass CLI already installed"
fi

# copy over local dashboard jar
mkdir -p "$HTTP_INSTALL_DIR" || { echo 'Install failed. cannot create local dashboard directory'; exit 1; }
cp $HTTP_JAR "$HTTP_INSTALL_DIR/"
echo "local dashboard installed"

echo "Installation complete"
