package com.aws.iot.evergreen.packagemanager;


import com.aws.iot.evergreen.packagemanager.model.Package;
import com.aws.iot.evergreen.packagemanager.model.PackageEntry;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SoftwareInstallerTest {

    private SoftwareInstaller softwareInstaller;

    @Mock
    private PackageDatabaseAccessor databaseAccessor;

    @BeforeEach
    void beforeEach() {
        softwareInstaller = new SoftwareInstaller(databaseAccessor, Paths.get(System.getProperty("user.dir")).resolve("working_directory"));
    }

    @Test
    void testCopySinglePackageArtifact() {
        Package pkg = new Package("MonitorService", "pkg", "1.0", null, null, null);
        List<String> paths = Arrays.asList(Paths.get(System.getProperty("user.dir")).resolve("mock_repository").resolve("cache").resolve("daemon").toString(),
                Paths.get(System.getProperty("user.dir")).resolve("mock_repository").resolve("cache").resolve("daemon2").toString());
        PackageEntry packageEntry = new PackageEntry(2, "pkg", "1.0", paths);
        when(databaseAccessor.findPackage(anyString(), anyString())).thenReturn(packageEntry);

        softwareInstaller.copyInstall(pkg);
    }
}
