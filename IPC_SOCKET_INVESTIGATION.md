# IPC Socket Support Investigation

## Question
Does the uninstall lifecycle support IPC socket access as specified in Section 2.5 of the specification?

## Answer
**YES - IPC socket support is already implemented and available to uninstall scripts.**

## How It Works

### 1. IPC Authentication Token Registration
- Token is registered in `GenericExternalService.postInject()` via `AuthenticationHandler.registerAuthenticationToken(this)`
- Token is stored in the service's private config under `SERVICE_UNIQUE_ID_KEY` (`_UID`)
- Token is **never deregistered**, remaining available throughout the component's entire lifecycle

### 2. Automatic Environment Variable Setup
All lifecycle scripts (install, startup, run, shutdown, **and uninstall**) automatically receive IPC-related environment variables through `ShellRunner.setup()`:

**File:** `src/main/java/com/aws/greengrass/lifecyclemanager/ShellRunner.java`

```java
Exec exec = Platform.getInstance().createNewProcessRunner()
    .withShell(command)
    .setenv("SVCUID", 
        String.valueOf(onBehalfOf.getPrivateConfig().findLeafChild(SERVICE_UNIQUE_ID_KEY).getOnce()))
    // Additional IPC-related environment variables also set here
```

### 3. Uninstall Execution Path
1. `Lifecycle.executeUninstallAndRemove()` calls `greengrassService.uninstall()`
2. `GenericExternalService.uninstall()` calls `run(LIFECYCLE_UNINSTALL_NAMESPACE_TOPIC, ...)`
3. `run()` method calls `shellRunner.setup(t.getFullName(), cmd, this)`
4. `ShellRunner.setup()` automatically sets `SVCUID` and other IPC environment variables
5. Uninstall script executes with full IPC access

## Environment Variables Provided

Based on `ShellRunner.setup()` implementation, uninstall scripts receive:

1. **`SVCUID`** - IPC authentication token (automatically set)
2. **`AWS_GG_NUCLEUS_DOMAIN_SOCKET_FILEPATH`** - IPC socket path (automatically set)
3. **`AWS_GG_NUCLEUS_DOMAIN_SOCKET_FILEPATH_FOR_COMPONENT`** - Component-specific socket path (automatically set)
4. **`GREENGRASS_LIFECYCLE_EVENT`** - Set to "uninstall" (explicitly set in uninstall method)
5. **`GREENGRASS_COMPONENT_NAME`** - Component name (explicitly set in uninstall method)
6. **`GREENGRASS_COMPONENT_VERSION`** - Component version (explicitly set in uninstall method)
7. **Custom environment variables** - Via `setenv` configuration in recipe (automatically set via `addEnv()`)

## Specification Compliance

**Section 2.5 Requirement:** "Will provide IPC sockets to the script if the customer chooses to"

**Status:** ✅ **FULLY COMPLIANT**

- IPC sockets are automatically available to all lifecycle scripts, including uninstall
- No additional configuration or code changes needed
- Works exactly the same way as install, startup, run, and shutdown scripts
- Customer can use IPC APIs in uninstall scripts just like any other lifecycle script

## Code References

1. **Token Registration:** `GenericExternalService.postInject()` line ~200
2. **Token Storage:** `AuthenticationHandler.registerAuthenticationToken()` 
3. **Environment Setup:** `ShellRunner.setup()` - automatically sets SVCUID and IPC paths
4. **Uninstall Execution:** `GenericExternalService.uninstall()` line 628
5. **Run Method:** `GenericExternalService.run()` line 829 - calls ShellRunner.setup()

## Conclusion

IPC socket support for uninstall scripts is **already implemented** through the existing infrastructure. The `run()` method that uninstall uses automatically provides all IPC-related environment variables via `ShellRunner.setup()`, making IPC APIs available to uninstall scripts without any additional code changes.

**No further implementation work is needed for IPC socket support.**
