# Evergreen Developer Guide
The purpose of this guide is to get you up to speed with developing Evergreen.

## Pull Down Repositories From GitHub
To begin developing with Evergreen you will need to clone the repositories. 

1. You will need permission to download from GitHub using SSH.
    1. Go to GitHub and then click on your picture -> Settings -> SSH and GPG keys
    2. Add an SSH key to your account. You can create a new key, or just use your existing key located at `~/.ssh/id_rsa.pub`
    3. Copy and paste the key, ensuring that there are no extraneous new lines
2. Use `git clone ssh://git@github.com/aws/<repository-name>.git` to clone the repository to your current directory
3. Repeat (2) for all the repositories that you want

## IntelliJ IDEA
- Open IntelliJ and go to File -> New -> Project from Existing Sources
- Then open the kernel or any other module and import it as a Maven project
- Once that's imported, go to File -> New -> Module from Existing Sources for each of the remaining modules you want to 
develop on. For each one, import it as a Maven project again.
- You should now have all the projects properly configured to begin working
- Once all projects are in, go to the Maven pane (usually on the right hand side) and run the `site` and `install`
targets for each of the projects

## NetBeans
- Each project is already configured as a NetBeans project, so just import it into NetBeans and it should work

## Running the Kernel
Once built, in order to run the kernel, you can create a run configuration in your IDE or just use the command line.
What you want is to run `java -jar <kernel-jar-path>.jar -root <config-root-dir-path>`. The config directory
needs to have a `config.yaml` file in it.

### Config YAML
The `config.yaml` file contains keys for platforms, launchers, and services. The file must contain
a `main`.

A very basic example looks like:

```yaml
---
sayHi:
  run: |-
    echo "Hello there, friend"
main:
  requires: sayHi
```

### Plugins
Plugins like the internalhttp server can be installed simply by placing the jar file that it generates into the
root dir/plugins/trusted. The plugin will be injected immediately when the kernel starts up.


## Testing
JUnit 5 is used for both unit and integration testing.

`mvn test` will only run the unit tests.
Use `mvn verify` to run both, or use `mvn surefire:test@integration-tests` to run only the integration tests.

### End-To-End Tests
End-To-End (E2E) tests differ from our integration tests in that they require AWS credentials and network
access. In order to run these tests, first you must put AWS credentials into your environment such as by using
Isengard and copying the credentials for your own, or the Evergreen dev account. Once you have credentials
with Iot:* access, you can then use our E2E tests. To run only the E2E tests, use:
`mvn surefire:test@integration-tests -Dgroups="E2E" -DexcludedGroups="" -Dsurefire.argLine=""`. This command
executes all integration tests tagged with "E2E". It will not run any other tests. Additionally, `mvn verify` does not
run the E2E tests by default since they take longer and require additional resources.

## PR/CR
Since development is on GitHub and not GitFarm we can't use `cr` to create a code review. Instead you must
push to a remote branch. Pushing to a remote branch will give you a URL to open a pull request against `master`.

If you didn't start by creating a local branch, or you just prefer the CR style workflow, then you can use the command
`git push origin master:<Temp-Branch-Name-For-PR>` where `Temp-Branch-Name-For-PR` is the name of the new remote
branch which will be used in the pull request. This branch name should be a good name, just like we should have
good commit messages. If you need to update the remote branch and you don't want an extra commit, then you can
add `-f` to the git command to force it to update the branch.
