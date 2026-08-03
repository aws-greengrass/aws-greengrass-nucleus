# Design Doc: AWS IoT Greengrass Nucleus — Factory Reset Feature

**Branch:** `factory_reset`
**Status:** Phase 1 + Phase 2 Implemented (BUILD SUCCESS)
**Component version bump:** `2.16.0` → `2.16.99` (dev marker)

---

## Table of Contents

1. [Overview](#1-overview)
2. [Architecture Summary](#2-architecture-summary)
3. [Phase 1 — Local Factory Reset (Shell Script)](#3-phase-1--local-factory-reset-shell-script)
   - [3.1 Files Changed](#31-files-changed)
   - [3.2 Snapshot Creation (Java)](#32-snapshot-creation-java)
   - [3.3 Snapshot Design: Identity + Nucleus Config, Save Once](#33-snapshot-design-identity--nucleus-config-save-once)
   - [3.4 Why Not `bootstrap.tlog`?](#34-why-not-bootstraptlog)
   - [3.5 Upgrade Scenario (2.15.0 → new version)](#35-upgrade-scenario-2150--new-version)
   - [3.6 Reset Execution (Shell)](#36-reset-execution-shell)
   - [3.7 What Is Preserved vs. Deleted](#37-what-is-preserved-vs-deleted)
   - [3.8 Cloud State Limitation](#38-cloud-state-limitation)
4. [Phase 2 — Cloud-Integrated Factory Reset (IPC)](#4-phase-2--cloud-integrated-factory-reset-ipc)
   - [4.1 New Components](#41-new-components)
   - [4.2 IPC Operation Design](#42-ipc-operation-design)
   - [4.3 Authorization Model](#43-authorization-model)
   - [4.4 `deleteCoreDevice` via TES Credentials](#44-deletecoredevice-via-tes-credentials)
   - [4.5 Full Phase 2 Flow](#45-full-phase-2-flow)
   - [4.6 Smithy Model Changes](#46-smithy-model-changes)
5. [Key Design Decisions](#5-key-design-decisions)
6. [End-to-End Sequence Diagrams](#6-end-to-end-sequence-diagrams)
7. [Phase 1 vs Phase 2 Comparison](#7-phase-1-vs-phase-2-comparison)
8. [Limitations and Future Work](#8-limitations-and-future-work)
9. [Related Files](#9-related-files)

---

## 1. Overview

The factory reset feature allows an operator (or an authorized component) to restore a Greengrass device to its **post-provisioning state** — the state immediately after the device was provisioned, before any software deployments were applied.

The device **retains its IoT identity** (Thing name, certificate, private key, root CA, data endpoint, credential endpoint, AWS region). All deployed software state is wiped. After the reset, the device comes online ready to receive fresh deployments.

### Motivating Use Cases

- **Breaking crash loops** — a bad deployment bricks the device; factory reset restores it without a reflash
- **Device repurposing** — reassign a device to a different workload/fleet
- **Dev/test cleanup** — quickly return a test device to a clean state
- **Security incident response** — wipe deployed components while preserving identity
- **Fleet-wide reset** — uniform, scriptable reset across a device fleet

---

## 2. Architecture Summary

```
┌─────────────────────────────────────────────────────────────────────┐
│  Snapshot (Java — automatic, once at provisioning)                   │
│                                                                      │
│  Device provisioned → isDeviceConfiguredToTalkToCloud() = true       │
│  → saveFactoryResetSnapshotIfNeeded()                                │
│  → Write identity+nucleus-config tlog → config/factory-reset.tlog   │
│    (immutable: saved once, never overwritten)                        │
└─────────────────────────────────────────────────────────────────────┘
                         │  (snapshot lives on device indefinitely)
                         │  (operator triggers reset later)
                         ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Phase 1: Local Reset (Shell script — SSH into device)               │
│                                                                      │
│  factory-reset.sh:                                                   │
│    Stop GG → Restore config → Wipe state → Recreate logs/ → Start GG│
│  Works even on bricked devices (all cleanup BEFORE restart)          │
│  Cloud cleanup: MANUAL (customer must run deleteCoreDevice)          │
└─────────────────────────────────────────────────────────────────────┘
                         │  (Phase 2 builds on Phase 1)
                         ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Phase 2: Cloud-Integrated Reset (IPC operation — IMPLEMENTED)       │
│                                                                      │
│  FactoryReset IPC:                                                   │
│    deleteCoreDevice (via TES) → Local cleanup → Restart              │
│  Fully automated, no manual cloud cleanup needed                     │
│  Triggerable by authorized components, CLI, or automation            │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. Phase 1 — Local Factory Reset (Shell Script)

### 3.1 Files Changed

| File | Type | Description |
|------|------|-------------|
| `scripts/factory-reset.sh` | **NEW** | Shell script that performs the full factory reset |
| `FACTORY_RESET.md` | **NEW** | End-user facing documentation for the feature |
| `src/main/java/.../lifecyclemanager/Kernel.java` | Modified | Added `saveFactoryResetSnapshotIfNeeded()` + `writeIdentityOnlySnapshot()`; added `factory-reset.sh` to deploy-copy file list |
| `src/main/java/.../lifecyclemanager/KernelLifecycle.java` | Modified | Added `kernel.writeEffectiveConfig()` after fleet/custom provisioning plugin completes |
| `assembly.xml` | Modified | Packages `factory-reset.sh` into distribution zip with `0755` execute permissions |
| `pom.xml` | Modified | Adds `scripts/**` to license-header plugin excludes |

### 3.2 Snapshot Creation (Java)

The snapshot is triggered via `Kernel.writeEffectiveConfig()` → `saveFactoryResetSnapshotIfNeeded()`.

**Coverage across all provisioning methods:**

| Provisioning Method | How snapshot is triggered |
|---------------------|--------------------------|
| `--provision true` (automatic) | `kernel.launch()` → `KernelLifecycle.initConfigAndTlog()` → `writeEffectiveConfig()` |
| Manual (`init-config` / YAML) | `initConfigAndTlog()` → `writeEffectiveConfig()` |
| Fleet provisioning plugin | Explicit `kernel.writeEffectiveConfig()` in `executeProvisioningPlugin()` |
| Custom provisioning plugin | Same as fleet provisioning |

### 3.3 Snapshot Design: Identity + Nucleus Config, Save Once

**What the snapshot contains:**

```yaml
system:
  certificateFilePath: "/greengrass/v2/thingCert.crt"
  privateKeyPath:      "/greengrass/v2/privKey.key"
  rootCaPath:          "/greengrass/v2/rootCA.pem"
  rootpath:            "/greengrass/v2"
  thingName:           "MyDevice"

services:
  aws.greengrass.Nucleus:
    componentType: "NUCLEUS"
    configuration:
      awsRegion:       "us-west-2"
      iotDataEndpoint: "xxxxx-ats.iot.us-west-2.amazonaws.com"
      iotCredEndpoint: "xxxxx.credentials.iot.us-west-2.amazonaws.com"
      iotRoleAlias:    "GreengrassCoreTokenExchangeRoleAlias"
      # ... other nucleus configuration
```

**What is intentionally excluded:**
- `services.aws.greengrass.Nucleus.version` — **deliberately omitted**. `initializeNucleusVersion()` uses `.dflt()` to set the version from the currently running binary's `conf/recipe.yaml` at boot time. This ensures the version always matches the installed binary, even after nucleus upgrades.
- All other `services.*` entries (deployed components) — device starts clean with no previously deployed components.

**Save-once guard:**
```java
if (Files.exists(snapshotPath)) {
    return;  // Never overwrite — first provisioning wins
}
```

**Why version is excluded — the `.dflt()` mechanism:**

```java
// Kernel.java: initializeNucleusVersion()
config.lookup(SERVICES_NAMESPACE_TOPIC, nucleusComponentName, VERSION_CONFIG_KEY)
      .dflt(nucleusComponentVersion);  // Only sets value if none exists
```

`.dflt()` only sets a value if none is currently set. When the nucleus boots after factory reset:
1. Reads `config.tlog` — no version entry
2. `initializeNucleusVersion()` → `.dflt(version from recipe.yaml)` → sets correct version from running binary
3. ✅ Version always matches the installed binary — identical to fresh manual provisioning flow

### 3.4 Why Not `bootstrap.tlog`?

`bootstrap.tlog` is written BEFORE provisioning plugins run (no endpoints/region yet). `factory-reset.tlog` is written AFTER provisioning is complete (all identity fields present). They serve different purposes:

| | `bootstrap.tlog` | `factory-reset.tlog` |
|---|---|---|
| **Purpose** | Crash-recovery fallback | Factory reset golden snapshot |
| **When written** | Before plugin provisioning (no identity) | After provisioning (full identity) |
| **Overwritten?** | Yes, on subsequent boots | **Never** |
| **Has all required fields?** | ❌ For plugin provisioning | ✅ All methods |

### 3.5 Upgrade Scenario (2.15.0 → new version)

```
1. Device provisioned on v2.15.0 (no factory reset support)
2. Components A, B, C deployed
3. Cloud sends Nucleus upgrade → new version boots for first time
4. factory-reset.tlog does NOT exist yet
5. saveFactoryResetSnapshotIfNeeded() runs
   ├── isDeviceConfiguredToTalkToCloud() = true ✅
   └── writeIdentityOnlySnapshot()
         ├── Captures: system.* + Nucleus.configuration
         └── EXCLUDES: A, B, C service entries ✅

Result: clean snapshot regardless of pre-upgrade deployment state
```

**`alts/current` is kept as-is** — no nucleus version rollback is performed. The currently running version is correct.

### 3.6 Reset Execution (Shell)

**Script location:**

```
{GG_ROOT}/alts/<version-uuid>/distro/bin/factory-reset.sh
```

Access via the `alts/current` symlink (always points to active version):
```bash
sudo /greengrass/v2/alts/current/distro/bin/factory-reset.sh
```

> ⚠️ Do NOT use `alts/init/distro/bin/factory-reset.sh` — `alts/init` contains the original installation which may not include this script (if the device was originally installed with a version before this feature).

**8-Step Reset Flow:**

```
factory-reset.sh
│
├── [Validation]
│     ├── alts/ directory exists? (confirms GG_ROOT)
│     └── config/factory-reset.tlog exists?
│
├── Step 1: STOP GREENGRASS
│     ├── systemctl stop greengrass   (systemd)
│     ├── service greengrass stop     (SysV init fallback)
│     └── Neither → warn and continue
│
├── Step 2: RESTORE CONFIG
│     ├── cp factory-reset.tlog → config/config.tlog
│     ├── rm config/config.tlog~
│     └── rm config/effectiveConfig.yaml
│
├── Step 3: CLEAN NUCLEUS LAUNCH DIRECTORY STATE
│     ├── rm <alts/current target>/launch.params  (regenerated on boot)
│     └── rm alts/old, alts/new, alts/broken  (deployment remnants)
│
├── Step 4: DELETE DEPLOYED CONTENT
│     ├── rm -rf deployments/
│     ├── rm -rf work/
│     └── rm -rf plugins/untrusted/
│
├── Step 5: DELETE ACCUMULATED STATE
│     ├── rm -rf telemetry/
│     └── rm -rf logs/
│
├── Step 6: CLEAN IPC RUNTIME FILES
│     ├── rm ipc.socket
│     └── rm -rf cli_ipc_info/
│
├── [Pre-start fix]
│     └── mkdir -p logs/   ← IMPORTANT: systemd redirects loader output to
│                              logs/loader.log before the JVM starts; directory
│                              must exist or the shell redirect fails (exit code 1)
│
└── Step 7: START GREENGRASS
      ├── systemctl start greengrass
      └── service greengrass start (fallback)
```

**Post-Reset Boot:**

```
Nucleus boot
  └── loader reads alts/current/launch.params (recreated from config defaults)
  └── java -jar Greengrass.jar
        ├── Reads config.tlog (restored identity + Nucleus config)
        ├── initializeNucleusVersion() → .dflt() → sets version from recipe.yaml
        ├── Nucleus connects to IoT Core ✅
        ├── No deployed component entries → only built-in services ✅
        └── Device online, awaiting new deployments ✅
```

### 3.7 What Is Preserved vs. Deleted

| Item | Outcome | Reason |
|------|---------|--------|
| `config/factory-reset.tlog` | ✅ **Preserved** | Golden snapshot — never deleted |
| `config/bootstrap.tlog` | ✅ **Preserved** | Crash recovery fallback |
| `thingCert.crt`, `privKey.key`, `rootCA.pem` | ✅ **Preserved** | IoT identity must survive |
| Nucleus binary (`plugins/trusted/`) | ✅ **Preserved** | Required to boot |
| `alts/init/` | ✅ **Preserved** | Original installation |
| `alts/current` (symlink) | ✅ **Preserved** | Kept as-is — no version rollback |
| `packages/` (component store) | ✅ **Preserved** | Contains nucleus artifacts; stale entries cleaned by ComponentManager on next boot |
| `alts/old`, `alts/new`, `alts/broken` | 🔄 **Deleted** | Deployment remnants |
| `launch.params` | 🔄 **Deleted** | Regenerated from config defaults on boot |
| `config/config.tlog` | 🔄 **Replaced** | Overwritten with `factory-reset.tlog` |
| `config/effectiveConfig.yaml` | 🔄 **Deleted** | YAML cache; regenerated on boot |
| `deployments/` | ❌ **Deleted** | Deployment state and history |
| `work/` | ❌ **Deleted** | Component runtime data |
| `plugins/untrusted/` | ❌ **Deleted** | Cloud-deployed plugin JARs |
| `telemetry/` | ❌ **Deleted** | Telemetry metrics |
| `logs/` | ❌ **Deleted then recreated** | Old logs wiped; dir recreated for loader redirect |
| `ipc.socket` | ❌ **Deleted** | Stale Unix domain socket |
| `cli_ipc_info/` | ❌ **Deleted** | Stale CLI auth tokens |

### 3.8 Cloud State Limitation

**Phase 1 does NOT clean up cloud state.** After a local-only reset, the cloud may re-push previous deployments if the device remains in thing groups.

**Customer must manually clean cloud state** before triggering reset:

```bash
# Option A: Delete the core device (full cloud cleanup)
aws greengrassv2 delete-core-device --core-device-thing-name MyDevice

# Option B: Remove from thing groups (prevents re-deployment)
aws iot remove-thing-from-thing-group \
  --thing-name MyDevice \
  --thing-group-name MyFleetGroup
```

This limitation is automated by Phase 2.

---

## 4. Phase 2 — Cloud-Integrated Factory Reset (IPC)

Phase 2 is **fully implemented**. It adds a programmatic factory reset triggered via IPC that:

1. Automatically calls `deleteCoreDevice` to clean cloud state (best-effort via TES)
2. Exposes a `FactoryReset` IPC operation callable by components
3. Performs the same local cleanup as Phase 1
4. Restarts the nucleus

### 4.1 New Components

```
src/main/java/com/aws/greengrass/builtin/services/factoryreset/
  ├── FactoryResetAgent.java               ← Core reset logic + deleteCoreDevice
  └── FactoryResetIPCEventStreamAgent.java ← IPC handler (auth check → delegate)

src/main/java/com/aws/greengrass/ipc/modules/
  └── FactoryResetIPCService.java          ← IPC registration + auth setup

src/main/java/software/amazon/awssdk/aws/greengrass/
  ├── FactoryResetRequest.java             ← Generated from Smithy (empty request)
  ├── FactoryResetResponse.java            ← Generated: {status, message}
  ├── FactoryResetOperationContext.java    ← Generated operation context
  └── GeneratedAbstractFactoryResetOperationHandler.java ← Generated handler base
```

**`KernelLifecycle.java`** — `FactoryResetIPCService.class` added to `startables` list.

#### `FactoryResetAgent.java`

```java
public void performFactoryReset() {
    // Step 1: best-effort cloud cleanup via TES credentials
    tryDeleteCoreDevice();  // AccessDeniedException → warn, continue

    // Step 2: Close the tlog writer
    kernelLifecycle.softShutdown(0);

    // Step 3: Restore config from factory-reset.tlog
    Path configTlog = configPath.resolve("config.tlog");
    Files.copy(snapshotPath, configTlog, REPLACE_EXISTING);
    Files.deleteIfExists(configPath.resolve("config.tlog~"));
    Files.deleteIfExists(configPath.resolve("effectiveConfig.yaml"));

    // Step 4: Wipe deployed content
    deleteRecursively(nucleusPaths.deploymentPath());
    deleteRecursively(nucleusPaths.workPath());
    deleteRecursively(nucleusPaths.pluginPath().resolve("untrusted"));

    // Step 5: Wipe runtime state
    deleteRecursively(nucleusPaths.rootPath().resolve("telemetry"));
    deleteRecursively(nucleusPaths.loaderLogsPath().getParent()); // logs/

    // Step 6: Clean IPC files
    deleteRecursively(nucleusPaths.cliIpcInfoPath());

    // Step 7: Restart
    kernel.shutdown(30, REQUEST_RESTART);
}
```

### 4.2 IPC Operation Design

**Operation: `FactoryReset`** (defined in Smithy model in `CrtSmithyJavaCodegen`)

```
Request:  FactoryResetRequest  (empty — no parameters)

Response: FactoryResetResponse
  status: String   (e.g., "INITIATED")
  message: String  (human-readable status)

Errors:
  UnauthorizedError   — caller not in accessControl policy
  ServiceError        — e.g., factory-reset.tlog missing
```

The response is `INITIATED` because the nucleus restarts as part of the operation — the caller's IPC connection will be dropped before completion.

### 4.3 Authorization Model

```yaml
# In caller component's recipe:
accessControl:
  aws.greengrass.ipc.factoryreset:
    com.example.MyAdminComponent:factoryreset:1:
      policyDescription: "Allow factory reset"
      operations:
        - "aws.greengrass#FactoryReset"
      resources:
        - "*"
```

### 4.4 `deleteCoreDevice` via TES Credentials

`GreengrassV2Client` is built with TES credentials (temporary IAM credentials exchanged from the device certificate). Requires this IAM policy on the TES role:

```json
{
  "Effect": "Allow",
  "Action": "greengrass:DeleteCoreDevice",
  "Resource": "arn:aws:greengrass:*:*:coreDevices/${iot:Connection.Thing.ThingName}"
}
```

`AccessDeniedException` → warning logged, local reset proceeds (graceful degradation).

### 4.5 Full Phase 2 Flow

```
Component (IPC FactoryReset)
  │
  ▼
FactoryResetIPCEventStreamAgent
  ├── AuthorizationHandler.isAuthorized() ✅
  └── FactoryResetAgent.performFactoryReset()
        │
        ├── tryDeleteCoreDevice() → GreengrassV2Client (TES creds)
        │     ├── 200 OK → cloud state wiped ✅
        │     └── 403 AccessDenied → warn, continue
        │
        ├── Close tlog writer
        ├── Restore config.tlog from factory-reset.tlog
        ├── rm -rf deployments/, work/, plugins/untrusted/
        ├── rm -rf telemetry/, logs/
        ├── rm cli_ipc_info/
        └── kernel.shutdown(30, REQUEST_RESTART)
              │
              ▼
          Loader detects exit code 100 → relaunches Greengrass.jar
              │
              ▼
          Nucleus boots clean → ready for new deployments ✅
```

### 4.6 Smithy Model Changes

The `FactoryReset` operation was added to `CrtSmithyJavaCodegen/greengrass-ipc-model/main.smithy`:

```smithy
@documentation("Trigger a factory reset on the device.")
operation FactoryReset {
    input: FactoryResetRequest,
    output: FactoryResetResponse,
    errors: [UnauthorizedError, ServiceError]
}

structure FactoryResetRequest {}

structure FactoryResetResponse {
    @documentation("Status of the factory reset request.")
    status: String,
    @documentation("Human-readable status message.")
    message: String
}
```

Generated files copied into nucleus source tree: `FactoryResetRequest.java`, `FactoryResetResponse.java`, `FactoryResetOperationContext.java`, `GeneratedAbstractFactoryResetOperationHandler.java`, `GreengrassCoreIPCService.java`, `GreengrassCoreIPCServiceModel.java`.

---

## 4.7 CLI ↔ Nucleus Interaction Flowchart

```
User on Device
  │
  │  sudo java -jar greengrass-cli.jar --ggcRootPath /greengrass/v2 factory-reset
  ▼
┌─────────────────────────────────────────────────────────────────────┐
│  CLI Process (greengrass-cli.jar)                                    │
│                                                                      │
│  1. CLI.main(args)                                                   │
│       │                                                              │
│       ├── Parse --ggcRootPath /greengrass/v2                        │
│       └── picocli routes to FactoryResetCommand.run()               │
│                   │                                                  │
│  2. NucleusAdapterIpcClientImpl.factoryReset()                      │
│       │                                                              │
│       ├── Read /greengrass/v2/cli_ipc_info/user-<uid>               │
│       │     Contains: { domain_socket_path, cli_auth_token }        │
│       │                                                              │
│       ├── Create SocketOptions (LOCAL domain, STREAM type)          │
│       │                                                              │
│       ├── EventStreamRPCConnection.connect()                        │
│       │     → connects to domain socket (Unix IPC)                  │
│       │     → sends Connect frame with cli_auth_token               │
│       │                                                              │
│       └── GreengrassCoreIPCClient.factoryReset(request)             │
│             → sends FactoryReset operation over EventStream RPC     │
└───────────────────────────────────┬─────────────────────────────────┘
                                    │  Unix domain socket
                                    │  (e.g. /greengrass/v2/ipc.socket)
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Nucleus JVM (Greengrass.jar)                                        │
│                                                                      │
│  3. IPCEventStreamService receives request                           │
│       │                                                              │
│       └── FactoryResetIPCService routes to                          │
│             FactoryResetIPCEventStreamAgent.handleRequest()          │
│                   │                                                  │
│  4. AuthorizationHandler.isAuthorized()                             │
│       ├── Is caller in accessControl policy for                     │
│       │   "aws.greengrass.ipc.factoryreset"?                        │
│       ├── YES → proceed                                              │
│       └── NO  → return UnauthorizedError to CLI                     │
│                                                                      │
│  5. Send response back to CLI:                                       │
│       FactoryResetResponse { status: "INITIATED" }                  │
│       (CLI prints this and exits)                                   │
│                                                                      │
│  6. FactoryResetAgent.performFactoryReset() [runs AFTER response]   │
│       │                                                              │
│       ├── tryDeleteCoreDevice()                                      │
│       │     └── GreengrassV2Client (TES creds) → AWS Cloud          │
│       │           greengrassv2:DeleteCoreDevice                     │
│       │           ├── 200 OK → cloud state wiped                    │
│       │           └── 403 → warn, continue                         │
│       │                                                              │
│       ├── kernelLifecycle.softShutdown(0)                           │
│       │     └── Stop writing to config tlog                         │
│       │                                                              │
│       ├── Restore config.tlog ← factory-reset.tlog                 │
│       │                                                              │
│       ├── rm -rf deployments/ work/ plugins/untrusted/             │
│       ├── rm -rf telemetry/ logs/                                   │
│       ├── rm -rf cli_ipc_info/                                      │
│       │                                                              │
│       └── kernel.shutdown(30, REQUEST_RESTART=100)                  │
│             └── JVM exits with code 100                             │
└───────────────────────────────────┬─────────────────────────────────┘
                                    │  exit code 100
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Loader script (alts/current/distro/bin/loader)                     │
│                                                                      │
│  7. Detects exit code 100                                           │
│       └── exec "${LAUNCH_DIR}/distro/bin/loader"                   │
│             (re-exec itself — picks up any updated loader)          │
│                                                                      │
│  8. Launches new JVM                                                │
│       java -Droot=/greengrass/v2 \                                  │
│            -jar alts/current/distro/lib/Greengrass.jar              │
└───────────────────────────────────┬─────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Nucleus JVM — Clean Boot                                            │
│                                                                      │
│  9. Reads restored config.tlog                                      │
│       ├── system.* → IoT identity (thing, cert, key, CA)           │
│       └── services.Nucleus.configuration → endpoints, region        │
│                                                                      │
│  10. initializeNucleusVersion() → .dflt(version from recipe.yaml)  │
│        └── Sets version from currently running binary               │
│                                                                      │
│  11. Connects to IoT Core with existing certificate                 │
│        └── Cloud: auto-registers as new core device (if deleted)   │
│                                                                      │
│  12. No deployed components → only built-in services start          │
│        └── Device online, ready for new deployments ✅              │
└─────────────────────────────────────────────────────────────────────┘
```

**Key data flows:**

| Step | What flows | How |
|------|-----------|-----|
| CLI → Nucleus | FactoryResetRequest (empty) | Unix domain socket, EventStream RPC |
| Nucleus → CLI | FactoryResetResponse {INITIATED} | Same socket, before cleanup starts |
| Nucleus → AWS | DeleteCoreDevice API call | HTTPS, SigV4 with TES IAM credentials |
| Loader → JVM | Restart with same flags | `exec` relaunch, alts/current unchanged |
| JVM → config | Reads restored tlog | Local filesystem |

---

## 5. Key Design Decisions

### 5.1 Snapshot-First, Script-Second (Phase 1)

All cleanup in Phase 1 happens BEFORE Greengrass restarts. Works even on bricked/crash-looping devices — no Java code runs during the reset.

### 5.2 Identity + Nucleus Config Snapshot (Not Full Config)

The snapshot captures:
- `system.*` — cert paths, thing name, root CA, root path
- `services.aws.greengrass.Nucleus.configuration.*` — endpoints, region, roleAlias
- `services.aws.greengrass.Nucleus.componentType` — "NUCLEUS"

**NOT captured:** nucleus `version` (auto-set via `.dflt()` from running binary), deployed component service entries.

The full-config-dump approach is incorrect because on upgraded devices, the config includes deployed component entries at snapshot-save time.

### 5.3 Save Once, Never Overwrite

The `if (Files.exists(snapshotPath)) return;` guard ensures the snapshot always reflects the original provisioning state. Subsequent deployments cannot corrupt it.

### 5.4 No Nucleus Version Rollback

`alts/current` is kept as-is. The currently running nucleus version is the right version. Rolling back to `alts/init` would downgrade upgraded devices unnecessarily.

### 5.5 Version Auto-Set via `.dflt()` at Boot

`initializeNucleusVersion()` uses `.dflt()` — same as fresh manual provisioning. If no version is in config, the version from `conf/recipe.yaml` (the running binary) is used. This correctly handles:
- Fresh install: version from original zip
- Post-factory-reset: version from currently running binary
- Post-upgrade: version from upgraded binary

### 5.6 `logs/` Directory Must Be Recreated

The systemd service file redirects loader output to `logs/loader.log` before the JVM starts. If `logs/` doesn't exist, the shell redirect fails and systemd sees exit code 1. `factory-reset.sh` explicitly runs `mkdir -p ${GG_ROOT}/logs` before starting Greengrass.

### 5.7 Best-Effort Cloud Cleanup (Phase 2)

`deleteCoreDevice` via TES is best-effort. `AccessDeniedException` is a warning, not a failure — local reset proceeds regardless. Customers opt in to cloud cleanup by adding `greengrass:DeleteCoreDevice` to their TES role.

---

## 6. End-to-End Sequence Diagrams

### Phase 1: Local-Only Reset (SSH)

```
Operator (SSH)
  │
  │  sudo /greengrass/v2/alts/current/distro/bin/factory-reset.sh
  ▼
factory-reset.sh
  ├─► Validate: alts/ exists, factory-reset.tlog exists
  ├─► systemctl stop greengrass
  ├─► cp factory-reset.tlog → config.tlog
  ├─► rm/clean alts symlinks, rm launch.params
  ├─► rm -rf deployments/ work/ plugins/untrusted/
  ├─► rm -rf telemetry/ logs/
  ├─► rm ipc.socket cli_ipc_info/
  ├─► mkdir -p logs/         ← prevents systemd loader redirect failure
  └─► systemctl start greengrass
            │
            ▼
      Nucleus boots from alts/current (unchanged version)
      └─► Clean config → IoT identity → builtins only → ready ✅
```

### Phase 2: Cloud-Integrated Reset (IPC)

```
Operator (Component/CLI)          Nucleus                         AWS Cloud
  │                                  │                                │
  │  IPC: FactoryReset               │                                │
  │─────────────────────────────────►│                                │
  │                                  │ Auth check ✅                  │
  │                                  │──────────────────────────────►│
  │                                  │  deleteCoreDevice(thingName)   │
  │                                  │◄──────────────────────────────│
  │                                  │  200 OK (or 403 → warn+skip)  │
  │                                  │                                │
  │   Response: {status:INITIATED}   │  Restore config.tlog           │
  │◄─────────────────────────────────│  Wipe deployments/work/logs    │
  │   (IPC connection drops)         │  kernel.shutdown(RESTART)      │
  │                                  │         ↓                      │
  │                              loader relaunches JVM                │
  │                                  │──────────────────────────────►│
  │                                  │  Connect to IoT Core           │
  │                                  │  Cloud: auto-register device   │
  │                                  │◄──────────────────────────────│
  │                                  │  Ready for new deployments ✅  │
```

---

## 7. Phase 1 vs Phase 2 Comparison

| Aspect | Phase 1 | Phase 2 |
|--------|---------|---------|
| **Trigger** | Shell script (SSH) | IPC (any authorized component) |
| **Cloud cleanup** | ❌ Manual | ✅ Automatic via `deleteCoreDevice` (best-effort) |
| **Works on bricked device** | ✅ Yes | ⚠️ Requires running nucleus; use Phase 1 as fallback |
| **Authorization** | `sudo` (OS-level) | IPC `accessControl` policy |
| **IAM requirements** | None | `greengrass:DeleteCoreDevice` on TES role (optional) |
| **Automation-friendly** | Limited | ✅ Components, fleet management |
| **After reset: re-deployment?** | ⚠️ Cloud re-pushes (if in thing groups) | ✅ Clean slate (if deleteCoreDevice succeeds) |
| **Implementation status** | ✅ Implemented | ✅ Implemented |

---

## 8. Limitations and Future Work

| # | Limitation | Status | Mitigation |
|---|-----------|--------|------------|
| 1 | **Snapshot only captured if provisioned with new nucleus** — devices on ≤2.15.x have no snapshot until first upgrade | Open | factory-reset.sh validates snapshot exists before proceeding |
| 2 | **Component store not cleaned** — stale artifacts remain in `packages/` | Open | `ComponentManager.cleanupStaleVersions()` handles on next boot |
| 3 | **Cloud re-deployment** in Phase 1 — device re-pushed if in thing groups | Open | Document manual `deleteCoreDevice`; Phase 2 automates |
| 4 | **`alts/current/distro/bin/factory-reset.sh` path** requires knowledge of `alts/` structure | Open | Consider adding convenience wrapper at `bin/factory-reset.sh` |
| 5 | **No Greengrass CLI integration yet** for Phase 2 | ✅ **Resolved** | `factory-reset` subcommand implemented in `aws-greengrass-cli` |

---

## 9. Related Files

| File | Purpose |
|------|---------|
| `FACTORY_RESET.md` | End-user documentation |
| `scripts/factory-reset.sh` | Phase 1 reset script |
| `src/.../lifecyclemanager/Kernel.java` | `saveFactoryResetSnapshotIfNeeded()`, `writeIdentityOnlySnapshot()` |
| `src/.../lifecyclemanager/KernelLifecycle.java` | Snapshot trigger after provisioning plugin; `FactoryResetIPCService` in startables |
| `src/.../builtin/services/factoryreset/FactoryResetAgent.java` | Core Phase 2 reset logic |
| `src/.../builtin/services/factoryreset/FactoryResetIPCEventStreamAgent.java` | IPC handler |
| `src/.../ipc/modules/FactoryResetIPCService.java` | IPC service registration + auth |
| `src/.../awssdk/aws/greengrass/FactoryResetRequest.java` | Generated Smithy model |
| `src/.../awssdk/aws/greengrass/FactoryResetResponse.java` | Generated Smithy model |
| `../CrtSmithyJavaCodegen/greengrass-ipc-model/main.smithy` | IPC Smithy model (FactoryReset operation) |
| `src/.../deployment/DeviceConfiguration.java` | `isDeviceConfiguredToTalkToCloud()` |
| `src/.../lifecyclemanager/KernelAlternatives.java` | `getCurrentDir()`, `locateCurrentKernelUnpackDir()` |
| **CLI repo (`aws-greengrass-cli`)** | |
| `cli/commands/FactoryResetCommand.java` | picocli `factory-reset` subcommand |
| `cli/adapter/NucleusAdapterIpc.java` | Interface: added `factoryReset()` |
| `cli/adapter/impl/NucleusAdapterIpcClientImpl.java` | IPC implementation of `factoryReset()` |
| `cli/CLI.java` | Added `FactoryResetCommand.class` to subcommands |
| `cli/module/CommandsComponent.java` | Added `factoryReset()` to Dagger component |
| `cli/awssdk/aws/greengrass/GreengrassCoreIPCClient.java` | Extended with `factoryReset()` (no `@Override`, not in published SDK base) |
