# AWS IoT Greengrass — Factory Reset Feature

---

## The Problem

Greengrass devices accumulate state over time — deployed components, configuration changes, runtime data, logs. When something goes wrong, there is no standard recovery path.

**Bad deployments can brick a device.** A misconfigured endpoint prevents the device from talking to the cloud, or a misconfigured component recipe causes the nucleus to enter an infinite restart loop. Because deployment state persists across restarts, the device stays stuck. The only recovery today is to SSH in and manually figure out which directories to delete.

**Device repurposing requires a full re-provision.** If you want to reassign a device to a different workload, there's no quick way to wipe the old software and start fresh. Operators end up uninstalling and reinstalling the nucleus, re-provisioning the IoT identity even though the hardware hasn't changed.

**Dev/test cycles are slowed by manual cleanup.** Between each test iteration, developers have to stop the nucleus, delete state directories, and restart. That friction adds up and discourages rapid experimentation.

---

## The Solution: Factory Reset

A single command that returns a device to a **clean, operational state** without re-provisioning.

**The end state after a factory reset:**
- The device is running the same nucleus version it was before
- No deployed components are present
- The device reconnects to AWS IoT Core and is ready to receive new deployments
- The IoT identity (Thing name, certificate, endpoints) is fully intact

---

## How It Works: The Snapshot

The key mechanism is an **immutable identity snapshot** saved automatically by the nucleus on first boot after installing this demo version.

```
Latest nucleus installed and running
  │
  └──► Snapshot saved once: config/factory-reset.tlog
            Contains: IoT Thing name, certificate paths,
                      cloud endpoints, AWS region, role alias
            Never modified after this point.
```

When factory reset is triggered:
1. This snapshot is restored as the active config
2. All deployed component data and accumulated runtime state are removed
3. The nucleus restarts from the current binary (same version, same `alts/current` directory)

The device comes back online clean, without needing to re-register with AWS IoT.

---

## Two Ways to Trigger

### Method 1 — Shell Script (works with bricked devices)

```bash
sudo /greengrass/v2/alts/current/distro/bin/factory-reset.sh
```

Stops Greengrass, wipes state, restarts. No running nucleus required.

### Method 2 — IPC and Greengrass CLI

```bash
greengrass-cli --ggcRootPath /greengrass/v2 factory-reset
```

Triggers reset via IPC. Also calls `greengrassv2:DeleteCoreDevice` to refresh the Greengrass console.

---

## What Is Preserved vs. Wiped

| Item | After Reset |
|------|------------|
| IoT identity (Thing, certificate, key, CA) | ✅ Preserved |
| AWS endpoints, region, role alias | ✅ Preserved |
| Nucleus binary and running version | ✅ Preserved |
| Deployed components & runtime state | ❌ Wiped |
| Log files and telemetry | ❌ Wiped |

---

## Demo

```bash
# Before: device has hello-world deployed
greengrass-cli --ggcRootPath /greengrass/v2 list-components
# aws.greengrass.Nucleus, aws.greengrass.Cli, com.example.HelloWorld

# Trigger factory reset
java -jar greengrass-cli.jar --ggcRootPath /greengrass/v2 factory-reset
# Factory reset status: INITIATED
# The nucleus is restarting...

# After: only nucleus running, device is clean
greengrass-cli --ggcRootPath /greengrass/v2 list-components
# aws.greengrass.Nucleus
```

---

## Anticipated Questions

**Q: Does factory reset roll back the nucleus version?**

No. The nucleus binary is never touched. The device restarts using the exact same version from the same directory (`alts/current` is unchanged).

**Q: How does the device reconnect if the config was reset?**

The snapshot includes everything needed to connect: `iotDataEndpoint`, `iotCredEndpoint`, `awsRegion`, and the certificate paths. These are captured on first boot and restored exactly. The IoT certificate on disk is never deleted.

---

## Future Expansion Opportunities

**Cloud resource cleanup** — Currently, factory reset only resets device-side state. Cloud resources like deployments, device shadows, and thing group memberships remain unchanged. A future version could optionally clean up these cloud-side resources as part of the reset, giving operators a true end-to-end reset.

**Cloud-initiated factory reset** — Allow operators to trigger factory reset from the Greengrass console or API, without needing direct device access. The cloud would send a reset command via MQTT, and the device-side IPC handler (already built in this project) executes it.

**Customer-defined reset config** — Today the reset snapshot is auto-captured at first boot. A future improvement would let customers define exactly what their "factory state" looks like — choosing which configuration fields to preserve or providing a custom snapshot.

**Selective component reset** — Reset only specific components rather than the full device. Useful for recovering a single misbehaving component without wiping everything else.

**Fleet-wide reset via thing group** — Combine cloud-initiated reset with IoT thing group targeting to trigger a coordinated reset across an entire device fleet in one operation.

**Reset audit trail** — Emit a structured event (telemetry or MQTT message) when a factory reset occurs, giving fleet operators visibility into which devices were reset, when, and by what trigger.
