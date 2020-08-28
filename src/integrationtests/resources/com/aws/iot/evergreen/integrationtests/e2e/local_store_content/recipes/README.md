## Platform for E2E
As cloud only matches exact OS name, in the recipe, we need to provide the OS name that will be detected when running 
the E2E tests.

As a result, both `macos` and `ubuntu` are provided to make E2E can work on our local laptop and Github Action 
(which uses ubuntu).

When making any change, don't forget to change the two manifests together, since they should also be consistent to 
ensure local and Github results are the same for local debugging purpose. 

See the following for an example:

```yaml
TemplateVersion: '2020-01-25'
ComponentName: {{SlowToDeployApp}}
Description: An app that takes some time to install so as to slow down deployments
Publisher: Me
Version: '1.0.0'
Manifests:
  - Platform:
      os: macos
    Lifecycle:
      install: sleep 5

  - Platform:
      os: unbuntu
    Lifecycle:
      install: sleep 5
```
