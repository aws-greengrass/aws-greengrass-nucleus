package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.model.Package;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

@ExtendWith(MockitoExtension.class)
class PackageLoaderTest {

    @InjectMocks
    private PackageLoader packageLoader;

    @Mock
    private PackageDatabaseAccessor databaseAccessor;

    @Test
    void testLoadSinglePackage() {
        Package pkg = packageLoader.loadPackage(getRootRecipePath("closed_dependency"));

        assertThat(pkg.getServiceName(), is("MonitorService"));
        assertThat(pkg.getPackageName(), is("Monitor"));
        assertThat(pkg.getPackageVersion(), is("1.0"));
    }

    @Test
    void testLoadTransitiveDependencies() {
        Package pkg = packageLoader.loadPackage(getRootRecipePath("transitive_dependency"));

        assertThat(pkg.getDependencyPackageMap().size(), is(2));
        assertThat(pkg.getDependencyPackageMap().get("Log-2.0").getDependencyPackageMap().size(), is(1));
        assertThat(pkg.getDependencyPackageMap().get("Cool-Database-1.0").getArtifactUrls().size(), is(1));
    }

    private Path getRootRecipePath(String packageFolder) {
        String recipePath = getClass().getResource("README.md").toString();
        if (recipePath.contains(":/")) {
            recipePath = recipePath.substring(recipePath.lastIndexOf(":/") + 1);
        }
        return Paths.get(recipePath.substring(0, recipePath.lastIndexOf("/"))).resolve(packageFolder);
    }
}
