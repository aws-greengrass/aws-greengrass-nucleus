package com.aws.iot.evergreen.deployment.model;

import com.aws.iot.evergreen.kernel.Kernel;
import java.util.Map;
import java.util.function.BiPredicate;

public class DeploymentPacket {

    private Map<String, Map<String, Parameter>> targetPackageConfigs;

    private BiPredicate<Kernel, Map<String, Map<String, Parameter>>> downloadCondition;

    private BiPredicate<Kernel, Map<String, Map<String, Parameter>>> updateCondition;

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
