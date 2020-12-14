# Greengrass Developer Guide
The purpose of this guide is to get you up to speed with developing Greengrass.

## Prerequisite
1. Install JDK 8 and set up JAVA_HOME
> Note: JDK 8 is required because we want to be _able_ to run Greengrass on devices where only JDK8 is available.
> Greengrass is often run on more modern JDKs.

## Pull Down Repositories From GitHub
To begin developing with Greengrass you will need to clone the repositories. 

1. You will need permission to download from GitHub using SSH.
    1. Go to GitHub and then click on your picture -> Settings -> SSH and GPG keys
    2. Add an SSH key to your account. You can create a new key, or just use your existing key located at `~/.ssh/id_rsa.pub`
    3. Copy and paste the key, ensuring that there are no extraneous new lines
2. Use `git clone ssh://git@github.com/aws-greengrass/<repository-name>.git` to clone the repository to your current directory
3. Repeat (2) for all the repositories that you want

## IDEs
### IntelliJ IDEA
- Open IntelliJ and go to File -> New -> Project from Existing Sources
- Then open the Nucleus or any other module and import it as a Maven project
- Once that's imported, go to File -> New -> Module from Existing Sources for each of the remaining modules you want to 
develop on. For each one, import it as a Maven project again.
- You should now have all the projects properly configured to begin working
- Once all projects are in, go to the Maven pane (usually on the right hand side) and run the `site` and `install`
targets for each of the projects

### NetBeans
- Each project is already configured as a NetBeans project, so just import it into NetBeans and it should work

## Running the Nucleus
Once built, in order to run the nucleus, you can create a run configuration in your IDE or just use the command line.
See details in [Greengrass easy setup](src/main/java/com/aws/greengrass/easysetup/README.md).

### Config YAML
The `config.yaml` file contains keys for platforms, launchers, and services.
Detailed config schema is in [README_CONFIG_SCHEMA](README_CONFIG_SCHEMA.md).

## Testing
JUnit 5 is used for both unit and integration testing. 
Both can be run locally without network access nor AWS credentials.

Run only unit tests: `mvn test`

Run only integration tests: `mvn surefire:test@integration-tests -Dsurefire.argLine=""`

Run both: `mvn verify`

### End-To-End Tests
End-To-End (E2E) tests differ from our integration tests in that they require AWS credentials and network
access. In order to run these tests, first you must put AWS credentials into your environment. Once you have credentials
with the following access, you can then use our E2E tests.
- `iot:*`
- `greengrass:*`
- `s3:*`
- `iam:*`

To run only the E2E tests, run the following commands:

```bash
mvn -ntp generate-test-sources test-compile -DskipTests
mvn surefire:test@integration-tests -Dgroups="E2E" -DexcludedGroups="" -Dsurefire.argLine=""
```

These commands re-build all tests with test resources, and execute all integration tests tagged with "E2E". It will 
not run any other tests. 

To ensure getting exact same result as CI, refer to the E2E test setup in [CI workflow](.github/workflows/e2eTest.yml).

Additionally, `mvn verify` does not run the E2E tests by default since they take longer and require additional 
resources.

#### Run E2E tests in parallel
To run E2E tests in parallel, append `-DforkCount=1C -DreuseForks=false` to the test command above. This enables
Maven to run test classes in parallel with the number of forks up to the number of available CPU cores.

Currently, tests cases within the same class should not run in parallel, due to usages of global resources.

#### Add a new E2E test
For new tests which are non-intrusive to test environment, annotate with `@Tag("E2E")`.
For those changing the underlying test environment, e.g. MQTT connection test, annotate with `@Tag("E2E-INTRUSIVE")`.

## Submit Pull Requests
See [Contibuting Guidelines](CONTRIBUTING.md).
