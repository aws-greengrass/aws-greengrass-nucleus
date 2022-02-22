package com.aws.greengrass.android.provision;

import androidx.annotation.NonNull;

import com.google.gson.annotations.SerializedName;

public class ProvisionConfig {
    @SerializedName("aws.accessKeyId")
    public String awsAccessKeyId;
    @SerializedName("aws.secretAccessKey")
    public String awsSecretAccessKey;
    @SerializedName("--aws-region")
    public String awsRegion;
    @SerializedName("--thing-name")
    public String thingName;
    @SerializedName("--thing-group-name")
    public String thingGroupName;
    @SerializedName("--thing-policy-name")
    public String thingPolicyName;
    @SerializedName("--tes-role-alias-name")
    public String tesRoleAliasName;
    @SerializedName("--component-default-user")
    public String componentDefaultUser;
    @SerializedName("--provision")
    public String provision;
    @SerializedName("--setup-system-service")
    public String setupSystemService;

    @NonNull
    @Override
    public String toString() {
        return "awsAccessKeyId=" + awsAccessKeyId + "\n" +
                "awsSecretAccessKey=" + awsSecretAccessKey + "\n" +
                "awsRegion=" + awsRegion + "\n" +
                "thingName=" + thingName + "\n" +
                "thingGroupName=" + thingGroupName + "\n" +
                "thingPolicyName=" + thingPolicyName + "\n" +
                "tesRoleAliasName=" + tesRoleAliasName + "\n" +
                "componentDefaultUser=" + componentDefaultUser + "\n" +
                "provision=" + provision + "\n" +
                "setupSystemService=" + setupSystemService + "\n";
    }
}
