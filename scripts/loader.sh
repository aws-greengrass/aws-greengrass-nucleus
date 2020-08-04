#!/bin/sh

set -euo pipefail

PWD=$(dirname "$0")

# Assuming loader.sh is launched from "$GG_ROOT/alts/current/distro/bin/loader.sh"
GG_ROOT=$(cd $PWD/../../../..; pwd)

echo "Greengrass root: "${GG_ROOT}

LAUNCH_DIR="$GG_ROOT/alts/current"

launch_kernel() {
  if [[ ! -d ${LAUNCH_DIR} ]] ; then
    echo FATAL: No Kernel found!
    exit 1
  fi

  JVM_OPTIONS=$(cat "$LAUNCH_DIR/launch.params")
  # TODO: add $CONFIG_FILE
  OPTIONS="--root $GG_ROOT"
  echo "jvm options: "${JVM_OPTIONS}
  java ${JVM_OPTIONS} -cp "$LAUNCH_DIR/distro/lib/*" com.aws.iot.evergreen.kernel.KernelCommandLine ${OPTIONS}
  kernel_exit_code=$?
  echo "kernel exit at code: "${kernel_exit_code}
}

atomic_move() {
  mv -f $1 $2
}

### Launch Kernel
if [[ -d ${GG_ROOT}/alts/old ]]; then
  # During deployment. Try to proceed with new kernel.
  # TODO: set correct config file
  CONFIG_FILE="$GG_ROOT/bin/ongoingDeployment/target.tlog"
  # In this runtime, Kernel starts with DEPLOYMENT_ACTIVATION state,
  # and may transition into DEFAULT if success or restart into DEPLOYMENT_ROLLBACK

elif [[ -d ${GG_ROOT}/alts/broken ]]; then
  # During deployment rollback.
  if [[ ! -d ${LAUNCH_DIR} ]] && [[ -d ${GG_ROOT}/alts/old ]]; then
    atomic_move ${GG_ROOT}/alts/old ${LAUNCH_DIR}
  fi

  # TODO: set correct config file
  CONFIG_FILE="$GG_ROOT/bin/ongoingDeployment/rollback.tlog"
  # In this runtime, Kernel starts with DEPLOYMENT_ROLLBACK state,
  # and WILL transition into DEFAULT

else
  echo "TODO: set correct config file"
  # TODO: set correct config file
fi


j=1
while [[ $j -le 3 ]]; do
  launch_kernel
  case ${kernel_exit_code} in
  100)
    # Restart Kernel by restarting loader. Assume symlinks have been flipped.
    echo "Restarting kernel"
    exit 0 # or exec
    ;;
  101)
    # Reboot device. Assume symlinks have been flipped.
    echo "Rebooting host"
    sudo reboot
    ;;
  0)
    # TODO: disable loader when kernel exit normally?
    exit 0
    ;;
  *)
    echo "Kernel exit ${kernel_exit_code}. Retrying $j times"
    ;;
  esac
  j=$(( j + 1 ))
done

# when reaching here, kernel has restarted 3 times and fails. Keep the current status and flip symlink.
# 1. If old+current: Kernel update failed. flip current to broken, old to current
if [[ -d ${GG_ROOT}/alts/old ]] || [[ -d ${GG_ROOT}/alts/current ]]; then
  atomic_move ${GG_ROOT}/alts/current ${GG_ROOT}/alts/broken
  atomic_move ${GG_ROOT}/alts/old ${GG_ROOT}/alts/current
fi

# 2. If current+broken: rollback failed and kernel crashed. What to do? start with empty config?

# 3. If current: no rollback option. What to do??? Restart Kernel?? Keep track of broken Kernel??

exit ${kernel_exit_code}

