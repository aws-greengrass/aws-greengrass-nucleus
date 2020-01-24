package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.model.PackageEntry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class PackageDatabaseAccessor {

    private static final String DB_URL = "jdbc:Sqlite:" + System.getProperty("user.dir")+"/package.db";

    public PackageDatabaseAccessor() {
        createPackageTableIfNotExist();
        createArtifactTableIfNotExist();
    }

    private void createPackageTableIfNotExist() {
        String sql = "CREATE TABLE IF NOT EXISTS package (\n"
                + "    id integer primary key,\n"
                + "    name text NOT NULL,\n"
                + "    version text NOT NULL,\n"
                + "    UNIQUE(name, version)\n"
                + ");";

        try (Connection connection = DriverManager.getConnection(DB_URL);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create package table", e);
        }
    }

    private void createArtifactTableIfNotExist() {
        String sql = "CREATE TABLE IF NOT EXISTS artifact (\n"
                + "    id integer primary key,\n"
                + "    url text NOT NULL,\n"
                + "    package_id integer NOT NULL,\n"
                + "    FOREIGN KEY (package_id) REFERENCES package (id)"
                + ");";

        try (Connection connection = DriverManager.getConnection(DB_URL);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create package table", e);
        }
    }

    public void createPackageIfNotExist(String packageName, String packageVersion) {
        // Sanity Check, not sufficiently ensure uniqueness, table itself should ensure uniqueness
        PackageEntry packageEntry = findPackageEntity(packageName, packageVersion);
        if (packageEntry == null) {
            String sql = "INSERT INTO package(name,version) VALUES(?,?)";
            try (Connection connection = DriverManager.getConnection(DB_URL);
                 PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                preparedStatement.setString(1, packageName);
                preparedStatement.setString(2, packageVersion);
                preparedStatement.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to create package entity", e);
            }
        }
    }

    public void updatePackageArtifacts(PackageEntry entry, List<String> artifactUrls) {
        if (entry.getArtifactUrls().isEmpty()) {
            String sql = "INSERT INTO package(url,package_id) VALUES(?,?)";
            try (Connection connection = DriverManager.getConnection(DB_URL);
                PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                for (String artifact : artifactUrls) {
                    preparedStatement.setString(1, artifact);
                    preparedStatement.setInt(2, entry.getId());
                    preparedStatement.addBatch();
                }
                preparedStatement.executeBatch();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to insert artifacts", e);
            }
        } else {
            //TODO properly update artifact table
        }
    }

    public PackageEntry findPackage(String packageName, String packageVersion) {
        PackageEntry packageEntry = findPackageEntity(packageName, packageVersion);
        if (packageEntry != null) {
            List<String> artifactUrls = findArtifactEntities(packageEntry.getId());
            return new PackageEntry(packageEntry, artifactUrls);
        }
        return null;
    }

    private PackageEntry findPackageEntity(String packageName, String packageVersion) {
        String sql = "SELECT * FROM package WHERE name = ? AND version = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            preparedStatement.setString(1, packageName);
            preparedStatement.setString(2, packageVersion);

            ResultSet rs = preparedStatement.executeQuery();
            if (rs.next()) {
                return new PackageEntry(rs.getInt("id"),
                        rs.getString("name"), rs.getString("version"));
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find packageEntity");
        }
    }

    private List<String> findArtifactEntities(int packageId) {
        String sql = "SELECT * FROM artifact WHERE package_id = ?";
        try (Connection connection = DriverManager.getConnection(DB_URL);
             PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            preparedStatement.setInt(1, packageId);

            ResultSet rs = preparedStatement.executeQuery();

            List<String> artifactUrls = new ArrayList<>();
            if (rs.next()) {
                artifactUrls.add(rs.getString("url"));
            }
            return artifactUrls;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find packageEntity");
        }
    }
}
