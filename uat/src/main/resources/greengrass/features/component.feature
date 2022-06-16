Feature: Testing Cloud component in Greengrass

  Background:
    Given my device is registered as a Thing
    And my device is running Greengrass

  @NucleusSnapshotUat @CloudDeployment @stable
  Scenario: As a developer, I can create a component in Cloud and deploy it on my device
    When I create a Greengrass deployment with components
      | com.aws.HelloWorld | classpath:/greengrass/recipes/recipe.yaml |
    And I deploy the Greengrass deployment configuration
    Then the Greengrass deployment is COMPLETED on the device after 180 seconds
    And the com.aws.HelloWorld log on the device contains the line "Hello World!!" within 20 seconds
    # Deployment with new version
    When I create a Greengrass deployment with components
      | com.aws.HelloWorld | classpath:/greengrass/recipes/updated_recipe.yaml |
    And I deploy the Greengrass deployment configuration
    Then the Greengrass deployment is COMPLETED on the device after 180 seconds
    And the com.aws.HelloWorld log on the device contains the line "Hello World Updated!!" within 20 seconds
