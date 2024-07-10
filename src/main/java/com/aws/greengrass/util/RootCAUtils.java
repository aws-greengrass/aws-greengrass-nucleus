/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.utils.IoUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public final class RootCAUtils {
    public static final String AMAZON_ROOT_CA_1_URL = "https://www.amazontrust.com/repository/AmazonRootCA1.pem";
    public static final String AMAZON_ROOT_CA_2_URL = "https://www.amazontrust.com/repository/AmazonRootCA2.pem";
    public static final String AMAZON_ROOT_CA_3_URL = "https://www.amazontrust.com/repository/AmazonRootCA3.pem";
    public static final String AMAZON_ROOT_CA_4_URL = "https://www.amazontrust.com/repository/AmazonRootCA4.pem";
    private static final Logger logger = LogManager.getLogger(ProxyUtils.class);

    private RootCAUtils() {

    }

    /**
     * Download root CA to a local file.
     * To support HTTPS proxies and other custom truststore configurations, append to the file if it exists.
     * @param f destination file
     * @param urls list of URLs needs to be downloaded
     * @throws IOException if download failed
     */
    public static void downloadRootCAToFile(File f, String... urls) throws IOException {
        if (f.exists()) {
            logger.atInfo().log("CA file found at {}. Contents will be preserved.", f);
        }
        try {
            for (String url : urls) {
                logger.atInfo().log("Downloading CA from {}", url);
                downloadFileFromURL(url, f);
            }
            removeDuplicateCertificates(f);
        } catch (IOException e) {
            throw new IOException("Failed to download CA from path", e);
        }
    }

    private static void removeDuplicateCertificates(File f) {
        try {
            String certificates = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Set<String> uniqueCertificates =
                    Arrays.stream(certificates.split(EncryptionUtils.CERTIFICATE_PEM_HEADER))
                            .map(s -> s.trim())
                            .collect(Collectors.toSet());

            try (BufferedWriter bw = Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8)) {
                for (String certificate : uniqueCertificates) {
                    if (certificate.length() > 0) {
                        bw.write(EncryptionUtils.CERTIFICATE_PEM_HEADER);
                        bw.write("\n");
                        bw.write(certificate);
                        bw.write("\n");
                    }
                }
            }
        } catch (IOException e) {
            logger.atDebug().log("Failed to remove duplicate certificates - %s%n", e);
        }
    }

    /**
     * Download content from a URL to a local file.
     * @param url the URL from which the content needs to be downloaded
     * @param f destination local file
     * @throws IOException if download failed
     */
    @SuppressWarnings("PMD.AvoidFileStream")
    public static void downloadFileFromURL(String url, File f) throws IOException {
        SdkHttpFullRequest request = SdkHttpFullRequest.builder()
                .uri(URI.create(url))
                .method(SdkHttpMethod.GET)
                .build();

        HttpExecuteRequest executeRequest = HttpExecuteRequest.builder()
                .request(request)
                .build();

        try (SdkHttpClient client = ProxyUtils.getSdkHttpClientBuilder().build()) {
            HttpExecuteResponse executeResponse = client.prepareRequest(executeRequest).call();

            int responseCode = executeResponse.httpResponse().statusCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Received invalid response code: " + responseCode);
            }

            try (InputStream inputStream = executeResponse.responseBody().get();
                 OutputStream outputStream = Files.newOutputStream(f.toPath(), StandardOpenOption.CREATE,
                         StandardOpenOption.APPEND, StandardOpenOption.SYNC)) {
                IoUtils.copy(inputStream, outputStream);
            }
        }
    }

    /**
     * Download rootCA 3 to root path.
     * @param rootCAPath the root path for CAs
     * @param urls the CA url array
     * @return if CA downloaded
     */
    public static boolean downloadRootCAsWithPath(String rootCAPath, String... urls) {
        if (rootCAPath == null || rootCAPath.isEmpty()) {
            return false;
        }
        Path caFilePath = Paths.get(rootCAPath);
        if (!Files.exists(caFilePath)) {
            return false;
        }
        try {
            downloadRootCAToFile(caFilePath.toFile(), urls);
        } catch (IOException e) {
            logger.atError().log("Failed to download CA from path - {}", caFilePath.toAbsolutePath(), e);
            return false;
        }
        return true;
    }

}
