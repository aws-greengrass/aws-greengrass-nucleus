# AWS IoT Greengrass Nucleus — Factory Reset

## Overview

The factory reset feature allows a Greengrass device to be restored to its **post-provisioning state** — the state immediately after the device was provisioned but before any software deployments were applied. This is useful for:

- Breaking crash loops caused by bad deployments
- Repurposing a device for a different workload
- Cleaning up after local development/testing
- Fleet-wide device reset operations
- Security incident response

The device **retains its IoT identity** (Thing, certificate, endpoints) after a factory reset. All deployed software state is wiped. After the reset, the device comes online ready to receive new deployments.

---

## Architecture

The factory reset is implemented in two parts:

### Part 1: Snapshot (Java — automatic, during provisioning)
```
Device is provisioned → factory-reset.tlog is saved automatically
(the golden image of the post-provisioning config)
```

### Part 2: Reset (Shell script — direct cleanup, before restart)
```
factory-reset.sh:
  Stop Greengrass → restore config → clean all state → start Greengrass
  (all cleanup happens BEFORE restart — works even on bricked devices)
```

---

## File Structure

| File | Purpose |
|------|---------|
| `{GG_ROOT}/config/factory-reset.tlog` | Golden snapshot of post-provisioning config. Saved once, never overwritten. |
| `scripts/factory-reset.sh` | Shell script that performs the factory reset. Located on device at `alts/init/distro/bin/factory-reset.sh`. |

---

## Part 1: Snapshot Creation

### When It's Saved

The snapshot (`factory-reset.tlog`) is saved **automatically** the first time `writeEffectiveConfig()` is called after the device becomes fully provisioned (all 7 identity fields are set):

- `thingName`
- `certificateFilePath`
- `privateKeyPath`
- `rootCaPath`
- `iotDataEndpoint`
- `iotCredEndpoint`
- `awsRegion`

**It is saved only once and never overwritten.** This guarantees it always represents the original provisioning state.

### Implementation

```
Kernel.writeEffectiveConfig()
  └── saveFactoryResetSnapshotIfNeeded(configPath)
        ├── Check: factory-reset.tlog already exists? → skip
        ├── Check: isDeviceConfiguredToTalkToCloud() = true?
        └── Write current config as transaction log → factory-reset.tlog
```

### Provisioning Method Coverage

| Provisioning Method | When Snapshot Is Saved |
|---------------------|----------------------|
| Automatic (`--provision true`) | During `kernel.launch()` in `KernelLifecycle`, right after provisioning |
| Manual (init-config) | During `initConfigAndTlog()` → `writeEffectiveConfig()` |
| Fleet provisioning (plugin) | After plugin writes identity + explicit `kernel.writeEffectiveConfig()` call in `executeProvisioningPlugin()` |
| Custom provisioning (plugin) | Same as fleet provisioning |

### Timestamp Considerations

The snapshot stores config values with their original provisioning-time timestamps. These are safe because:
- The nucleus config system uses timestamps for **relative ordering** only, not wall-clock validation
- New deployments always use `System.currentTimeMillis()` which will be newer → deployments always win
- Default values use `DEFAULT_VALUE_TIMESTAMP = 1` → defaults never clobber identity

---

## Part 2: Factory Reset Execution

### How to Run

**The script performs all cleanup BEFORE restarting Greengrass.** This design means it works even on bricked devices where Greengrass can't boot normally.

The script is packaged into the Greengrass distribution and available on the device at:

```
{GG_ROOT}/alts/init/distro/bin/factory-reset.sh   ← always accessible
{GG_ROOT}/bin/factory-reset.sh                     ← convenient shortcut (via symlink)
```

Usage:
```bash
# Normal device (Greengrass running or stopped):
sudo /greengrass/v2/bin/factory-reset.sh

# Bricked device (crash loop — alts/current may be broken):
sudo /greengrass/v2/alts/init/distro/bin/factory-reset.sh
```

The `alts/init` path is recommended for bricked devices because `alts/init` is **never modified by deployments**. The script auto-detects `GG_ROOT` from its own file path — no arguments needed.

### What the Script Does

```
Step 1: Stop Greengrass (systemd or service)

Step 2: Restore config
  └── cp factory-reset.tlog → config.tlog
  └── rm config.tlog~, effectiveConfig.yaml

Step 3: Reset nucleus launch directory
  └── rm alts/current
  └── ln -s alts/init alts/current   (back to original installation)
  └── rm alts/old, alts/new, alts/broken (deployment leftover symlinks)
  └── rm alts/init/launch.params     (regenerated from defaults on next boot)

Step 4: Delete deployed content
  └── rm -rf deployments/            (deployment state and history)
  └── rm -rf work/                   (component runtime state)
  └── rm -rf plugins/untrusted/      (cloud-deployed plugin JARs)

Step 5: Delete accumulated state
  └── rm -rf telemetry/              (telemetry metric data)
  └── rm -rf logs/                   (log files)

Step 6: Clean IPC runtime files
  └── rm ipc.socket                  (Unix domain socket)
  └── rm -rf cli_ipc_info/           (CLI auth tokens)

Step 7: Start Greengrass
```

### Post-Reset Boot

The nucleus boots from `alts/init` (the original installation) using the restored `config.tlog`:

```
initConfigAndTlog() reads restored config.tlog
  → Device has valid IoT identity → connects to IoT Core
  → No deployed components in config → only built-in services start
  → Device online, awaiting new deployments ✅
```

---

## What Is Preserved vs. Deleted

| Item | Status | Reason |
|------|--------|--------|
| `config/factory-reset.tlog` | ✅ Preserved | The golden snapshot — never deleted |
| `config/bootstrap.tlog` | ✅ Preserved | Config fallback (not modified) |
| `thingCert.crt`, `privKey.key`, `rootCA.pem` | ✅ Preserved | Device IoT identity |
| Nucleus binary (`plugins/trusted/`) | ✅ Preserved | Required to boot |
| `alts/init/` | ✅ Preserved | Original nucleus installation |
| `packages/` (component store) | ✅ Preserved | Contains nucleus artifacts/recipe; stale deployed entries cleaned up by `ComponentManager.cleanupStaleVersions()` later |
| `alts/current` | 🔄 Reset to `alts/init` | Returns to original nucleus version |
| `alts/old`, `alts/new`, `alts/broken` | 🔄 Deleted | Leftover deployment artifacts |
| `launch.params` | 🔄 Deleted | Regenerated from defaults on next boot |
| `config/config.tlog` | 🔄 Replaced | Restored from factory-reset.tlog |
| `config/effectiveConfig.yaml` | 🔄 Deleted | Regenerated on boot |
| `deployments/` | ❌ Deleted | Deployment state and history |
| `work/` | ❌ Deleted | Component runtime state |
| `plugins/untrusted/` | ❌ Deleted | Cloud-deployed plugin JARs |
| `telemetry/` | ❌ Deleted | Accumulated telemetry metrics |
| `logs/` | ❌ Deleted | Log files |
| `ipc.socket` | ❌ Deleted | Stale runtime socket |
| `cli_ipc_info/` | ❌ Deleted | Stale CLI auth tokens |

---

## Cloud State After Reset

The local factory reset **does not** automatically clean up cloud state. When the device comes back online, the cloud may re-push previous deployments.

### Recommended Cloud Cleanup (Phase 2 — Not Yet Implemented)

For a complete factory reset, an IPC-based `FactoryReset` operation will:

1. Call **`greengrassv2.deleteCoreDevice(thingName)`** — removes all Greengrass deployment history
2. Execute the same shell cleanup steps (or invoke the script)
3. Restart nucleus

The device auto-registers as a new core device on next connect (IoT Thing and certificate are preserved).

> **Note for thing-group deployments**: If the device remains in thing groups that have active deployments, the cloud will re-deploy after reset. This is expected behavior for fleet management. To prevent re-deployment, remove the device from thing groups before performing a factory reset.

---

## Sequence Diagram

```
SSH into device
  │
  │  sudo /greengrass/v2/alts/init/distro/bin/factory-reset.sh
  ▼
factory-reset.sh
  ├── Stops Greengrass (systemctl stop)
  ├── Copies factory-reset.tlog → config.tlog
  ├── Resets alts/current → alts/init
  ├── Deletes deployments/, work/, plugins/untrusted/
  ├── Deletes telemetry/, logs/
  ├── Deletes ipc.socket, cli_ipc_info/
  └── Starts Greengrass (systemctl start)
            │
            ▼
      Nucleus boots from alts/init
      └── Reads clean config.tlog (provisioning state)
      └── Connects to IoT Core with existing identity
      └── No deployed components — only Nucleus
      └── Ready for new deployments ✅
```

---

## Code Changes Summary

| File | Type | Description |
|------|------|-------------|
| `scripts/factory-reset.sh` | NEW | Shell script — stops Greengrass, performs all cleanup, starts Greengrass |
| `src/main/java/.../lifecyclemanager/Kernel.java` | MODIFIED | Added `FACTORY_RESET_TLOG_FILE` constant; `saveFactoryResetSnapshotIfNeeded()` in `writeEffectiveConfig()` |
| `src/main/java/.../lifecyclemanager/KernelLifecycle.java` | MODIFIED | Added `kernel.writeEffectiveConfig()` after fleet provisioning plugin runs (to capture snapshot) |
| `assembly.xml` | MODIFIED | Added `factory-reset.sh` to the distribution zip with execute permissions |
| `pom.xml` | MODIFIED | Added `scripts/**` to license plugin excludes |

---

## Limitations and Future Work

1. **Cloud cleanup not implemented** — IPC operation (Phase 2) needed for cloud-side cleanup via `deleteCoreDevice`
2. **No component store cleanup** — stale deployed artifacts remain until `cleanupStaleVersions()` runs
3. **Thing-group deployments** — cloud will re-deploy after reset if device is in active thing groups
4. **Nucleus version** — reset returns to the original nucleus version from `alts/init`; a nucleus update deployment is needed to get back to a newer version
