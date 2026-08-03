# [2026 Hackathon] One Click Factory Reset — Speaking Notes

---

**[INTRO]**

Hi everyone. Our hackathon project is called "One Click Factory Reset" for AWS IoT Greengrass.

---

**[THE PROBLEM]**

The idea for this project came from a real pain point. We've seen cases on oncall where a customer's device ended up in a broken state and the only fix was to manually reinstall nucleus. There was no clean, built-in way to recover. So we asked ourselves — what if there was?

So we started to think about what scenarios might need a factory reset:

A bad deployment can brick a device. For example, a misconfigured endpoint prevents the device from talking to the cloud, or a misconfigured component recipe causes the nucleus to enter an infinite restart loop. Because deployment state persists across restarts, the device stays stuck. The only recovery today is to SSH in and manually figure out which directories to delete.

Device repurposing is also painful. If you want to reassign a device to a different workload, there's no quick way to wipe the old software and start fresh. You end up uninstalling and reinstalling the nucleus from scratch, re-provisioning the IoT identity even though the hardware hasn't changed.

And for developers, the dev-test cycle is slowed by manual cleanup. Between each test iteration, you have to stop the nucleus, delete state directories, and restart. That friction adds up and slows down experimentation.

---

**[THE SOLUTION]**

So what we built is a factory reset feature — a single command that returns a device to a clean, operational state without re-provisioning.

After a factory reset: the device is running the same nucleus version it was before, no deployed components are present, it reconnects to AWS IoT Core automatically, and the IoT identity — Thing name, certificate, endpoints — is fully intact.

---

**[HOW IT WORKS]**

The key mechanism is an immutable identity snapshot. When you install the latest nucleus and it boots for the first time, it automatically saves a snapshot of the device's identity configuration — the Thing name, certificate paths, cloud endpoints, region, and role alias. This snapshot is saved once and never modified.

When factory reset is triggered, three things happen:
1. The snapshot is restored as the active config
2. All deployed component data and accumulated runtime state are removed
3. The nucleus restarts from the current binary — same version, same directory, nothing rolled back

The device comes back online clean, without needing to re-register with AWS IoT.

---

**[TWO WAYS TO TRIGGER]**

We built two ways to trigger a reset.

Method one is a shell script. You run `factory-reset.sh` directly on the device. This works even on bricked or crash-looping devices because it doesn't require a running nucleus — it stops Greengrass externally, does all the cleanup, and restarts.

Method two is through the Greengrass CLI. You run `greengrass-cli factory-reset`, which triggers the reset programmatically via IPC. This also makes a best-effort call to `DeleteCoreDevice` to refresh the device view in the Greengrass console.

---

**[DEMO]**

Let me show you a quick demo.

Here's a device with a hello-world component deployed. I'll run `list-components` — you can see Nucleus, CLI, and HelloWorld.

Now I trigger the factory reset...

> `java -jar greengrass-cli.jar --ggcRootPath /greengrass/v2 factory-reset`

"Factory reset status: INITIATED. The nucleus is restarting."

After it comes back up, I run `list-components` again — and now it's just Nucleus. HelloWorld is gone. The device is clean and ready for new deployments.

---

**[FUTURE EXPANSION]**

Looking ahead, there are several ways this could be expanded.

**Cloud resource cleanup** — right now we only reset device-side state. Cloud resources like deployments, shadows, and thing group memberships still remain. A future version could optionally clean those up too, giving operators a true end-to-end reset.

**Cloud-initiated factory reset** — allowing operators to trigger a reset from the Greengrass console or API without needing device access. The device-side IPC handler is already built, so the cloud would just need to send the command via MQTT.

**Customer-defined reset config** — instead of auto-capturing the snapshot at first boot, let customers define exactly what their factory state should look like.

**Selective component reset** — resetting just one misbehaving component instead of wiping the whole device.

**Fleet-wide reset** — combining cloud-initiated reset with thing group targeting to reset an entire fleet in one operation.

---

**[CLOSE]**

That's our project — one click factory reset for Greengrass. Thank you!
