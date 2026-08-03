#!/bin/sh
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
#
# AWS IoT Greengrass Factory Reset Script
#
# Resets the device to its post-provisioning state. This script performs all cleanup
# BEFORE restarting Greengrass, so it works even on bricked devices.
#
# What this script does:
#   1. Stops Greengrass (if running)
#   2. Restores config from the factory-reset.tlog snapshot (saved at provisioning time)
#   3. Resets the nucleus launch directory to alts/init (the original installation)
#   4. Deletes all deployed content: deployments, work, untrusted plugins, telemetry, logs
#   5. Cleans IPC files and runtime state
#   6. Starts Greengrass
#
# After restart, the device comes up in its post-provisioning state, ready for new
# deployments. The IoT identity (Thing, certificate, endpoints) is preserved.
#
# Usage (on the Greengrass device):
#
#   Normal (use the active nucleus version via alts/current):
#     sudo /greengrass/v2/alts/current/distro/bin/factory-reset.sh
#
#   Note: alts/current is a symlink that always points to the active nucleus version directory.
#   This is the correct path regardless of whether the nucleus was updated or not.
#   Do NOT use alts/init — that only contains the original installation and may not have this script.
#
# The Greengrass root directory is auto-detected from this script's location.
# This script is located at: {GG_ROOT}/alts/<dir>/distro/bin/factory-reset.sh

set -e

# Auto-detect GG_ROOT from this script's location.
# The script is at: {GG_ROOT}/alts/<dir>/distro/bin/factory-reset.sh
# So we walk up 4 levels: bin -> distro -> <dir> -> alts -> GG_ROOT
SCRIPT_DIR=$(dirname "$0")
GG_ROOT=$(cd "$SCRIPT_DIR/../../../.." && pwd)
SNAPSHOT_FILE="${GG_ROOT}/config/factory-reset.tlog"
ALTS_DIR="${GG_ROOT}/alts"
CONFIG_DIR="${GG_ROOT}/config"

echo "AWS IoT Greengrass Factory Reset"
echo "================================="
echo "Greengrass root: ${GG_ROOT}"

# Validate that the Greengrass root directory looks correct
if [ ! -d "${ALTS_DIR}" ]; then
    echo "ERROR: Does not appear to be a valid Greengrass root directory: ${GG_ROOT}"
    echo "The script should be run from its installed location:"
    echo "  sudo /greengrass/v2/alts/current/distro/bin/factory-reset.sh"
    exit 1
fi

# Validate that a factory reset snapshot exists
# The snapshot is saved automatically when the device first becomes fully provisioned
if [ ! -f "${SNAPSHOT_FILE}" ]; then
    echo "ERROR: No factory reset snapshot found at ${SNAPSHOT_FILE}"
    echo ""
    echo "This can happen if:"
    echo "  1. The device has not been fully provisioned yet"
    echo "  2. The device was provisioned with an older version of Greengrass"
    echo "     that did not support factory reset"
    echo ""
    echo "Please ensure the device is fully provisioned before attempting factory reset."
    exit 1
fi

echo "Factory reset snapshot found: ${SNAPSHOT_FILE}"
echo "Performing factory reset..."

# Step 1: Stop Greengrass
# All cleanup happens before restart so this works even on bricked devices.
if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet greengrass 2>/dev/null; then
    echo "Stopping Greengrass via systemd..."
    systemctl stop greengrass
elif command -v service >/dev/null 2>&1 && service greengrass status >/dev/null 2>&1; then
    echo "Stopping Greengrass via service..."
    service greengrass stop
else
    echo "NOTE: Greengrass does not appear to be running as a system service. Proceeding with cleanup."
fi

# Step 2: Restore config from factory reset snapshot
# The snapshot contains the post-provisioning identity config (thingName, endpoints, certs, etc.)
echo "Restoring config from factory reset snapshot..."
cp "${SNAPSHOT_FILE}" "${CONFIG_DIR}/config.tlog"
rm -f "${CONFIG_DIR}/config.tlog~"
rm -f "${CONFIG_DIR}/effectiveConfig.yaml"

# Step 3: Clean up nucleus launch directory state
# Keep alts/current as-is — the currently running nucleus version is working and should be kept.
# No version rollback is performed: factory reset restores config only, not the binary.
echo "Cleaning up nucleus launch directory state..."

# Delete launch.params from the current launch dir so it is regenerated from config defaults on next boot.
CURRENT_TARGET=$(readlink -f "${ALTS_DIR}/current" 2>/dev/null)
if [ -n "${CURRENT_TARGET}" ] && [ -d "${CURRENT_TARGET}" ]; then
    rm -f "${CURRENT_TARGET}/launch.params"
fi

# Clean up deployment-related alt symlinks left over from nucleus upgrade deployments (may not exist).
rm -f "${ALTS_DIR}/old"
rm -f "${ALTS_DIR}/new"
rm -f "${ALTS_DIR}/broken"

# Step 4: Delete all deployed content
echo "Deleting deployed content..."
rm -rf "${GG_ROOT}/deployments"          # Deployment state and history
rm -rf "${GG_ROOT}/work"                 # Component runtime state and data
rm -rf "${GG_ROOT}/plugins/untrusted"    # Cloud-deployed plugin JARs

# Step 5: Delete accumulated runtime state
echo "Deleting accumulated state..."
rm -rf "${GG_ROOT}/telemetry"            # Telemetry metric data
# Empty the logs directory (keep the directory itself so the systemd service
# redirect ">> logs/loader.log" does not fail before the loader runs).
rm -f "${GG_ROOT}/logs/"*               # Log files (directory preserved)

# Step 6: Clean up IPC runtime files
echo "Cleaning IPC files..."
rm -f  "${GG_ROOT}/ipc.socket"           # Unix domain socket
rm -rf "${GG_ROOT}/cli_ipc_info"         # CLI auth tokens and socket info

echo "Factory reset complete."
echo ""
echo "Starting Greengrass..."

# Step 7: Start Greengrass with the restored clean state
if command -v systemctl >/dev/null 2>&1 && systemctl is-enabled --quiet greengrass 2>/dev/null; then
    systemctl start greengrass
    echo "Greengrass started. Device is in post-provisioning state, ready for new deployments."
elif command -v service >/dev/null 2>&1; then
    service greengrass start
    echo "Greengrass started. Device is in post-provisioning state, ready for new deployments."
else
    echo "NOTE: Could not start Greengrass automatically."
    echo "Please start Greengrass manually."
fi
