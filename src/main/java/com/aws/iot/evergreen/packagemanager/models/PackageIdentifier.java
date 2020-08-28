package com.aws.iot.evergreen.packagemanager.models;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.util.Coerce;
import com.vdurmont.semver4j.Semver;
import lombok.AllArgsConstructor;
import lombok.Value;

import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.VERSION_CONFIG_KEY;

@Value
@AllArgsConstructor
public class PackageIdentifier implements Comparable<PackageIdentifier> {
    String name;
    Semver version;
    //TODO considering use enum if local name occluding is necessary.
    String scope;

    /**
     * PackageIdentifier constructor.
     *
     * @param name    package name
     * @param version package version in semver
     */
    @Deprecated  //scope needs to be recorded locally, switch to use all args constructor
    public PackageIdentifier(String name, Semver version) {
        this.name = name;
        this.version = version;
        //hardcode to 'private' before refactoring caller of this constructor
        this.scope = "private";
    }

    @Override
    public String toString() {
        return String.format("%s-v%s", name, version);
    }

    public static PackageIdentifier fromServiceTopics(Topics t) {
        return new PackageIdentifier(t.getName(), new Semver(Coerce.toString(t.findLeafChild(VERSION_CONFIG_KEY))));
    }

    @Override
    public int compareTo(PackageIdentifier o) {
        return version.compareTo(o.version) * -1;
    }
}
