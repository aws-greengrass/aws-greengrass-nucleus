package com.aws.iot.evergreen.deployment.model;

import com.aws.iot.evergreen.kernel.Kernel;
import java.util.Map;
import java.util.function.BiPredicate;

public class DeploymentPacket {

    private Map<String, Map<String, Parameter>> targetPackageConfigs;

    private BiPredicate<Kernel, Map<String, Map<String, Parameter>>> downloadCondition;

    private BiPredicate<Kernel, Map<String, Map<String, Parameter>>> updateCondition;

    public void setTargetPackageConfigs(Map<String, Map<String, Parameter>> configs) {
        this.targetPackageConfigs = configs;
    }

    public void setDownloadCondition(BiPredicate<Kernel, Map<String, Map<String, Parameter>>> condition) {
        this.downloadCondition = condition;
    }

    public void setUpdateCondition(BiPredicate<Kernel, Map<String, Map<String, Parameter>>> condition) {
        this.updateCondition = condition;
    }

    public Map<String, Map<String, Parameter>> getTargetPackageConfigs() {
        return targetPackageConfigs;
    }

    public BiPredicate<Kernel, Map<String, Map<String, Parameter>>> getDownloadCondition() {
        return downloadCondition;
    }

    public BiPredicate<Kernel, Map<String, Map<String, Parameter>>> getUpdateCondition() {
        return updateCondition;
    }
}
