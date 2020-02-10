package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.model.PackageEntry;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;

class PackageDatabaseAccessorTest {

    private PackageDatabaseAccessor databaseAccessor = new PackageDatabaseAccessor();

    @Test
    void testCreate() {
        databaseAccessor.createPackageIfNotExist("Log", "1.0");

        PackageEntry packageEntry = databaseAccessor.findPackage("Log", "1.0");
        assertThat(packageEntry.getPackageName(), is("Log"));
        assertThat(packageEntry.getPackageVersion(), is("1.0"));
        assertThat(packageEntry.getArtifactPaths().size(), is(0));

        packageEntry = databaseAccessor.findPackage("Log", "2.0");
        assertThat(packageEntry, nullValue());
    }

    @Test
    void testUpdateArtifactCachedLocation() {
        databaseAccessor.createPackageIfNotExist("Log", "1.0");
        PackageEntry packageEntry = databaseAccessor.findPackage("Log", "1.0");
        System.out.println(packageEntry.getId());

        databaseAccessor.updatePackageArtifacts(packageEntry, Arrays.asList("$HOME/loc1", "$HOME/loc2"));

        packageEntry = databaseAccessor.findPackage("Log", "1.0");
        List<String> paths = packageEntry.getArtifactPaths();
        assertThat(packageEntry.getArtifactPaths().size(), is(2));
    }

    @Test
    void testFindPackage() {
        PackageEntry packageEntry = databaseAccessor.findPackage("Log", "1.0");
        assertThat(packageEntry.getArtifactPaths().size(), is(2));
    }

}
