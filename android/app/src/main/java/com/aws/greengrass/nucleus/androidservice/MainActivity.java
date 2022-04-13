/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.nucleus.androidservice;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.view.View;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.aws.greengrass.android.component.utils.NotificationsManager;
import com.aws.greengrass.android.managers.ServicesConfigurationProvider;
import com.aws.greengrass.android.provision.AutoStartDataStore;
import com.aws.greengrass.android.provision.BaseProvisionManager;
import com.aws.greengrass.android.provision.ProvisionManager;
import com.aws.greengrass.android.service.DefaultGreengrassComponentService;
import com.aws.greengrass.android.util.LogHelper;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.nucleus.androidservice.databinding.ActivityMainBinding;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static android.content.Intent.ACTION_OPEN_DOCUMENT;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static com.aws.greengrass.android.provision.BaseProvisionManager.PROVISION_THING_NAME;
import static com.aws.greengrass.android.provision.BaseProvisionManager.PROVISION_THING_NAME_SHORT;
import static com.aws.greengrass.android.provision.BaseProvisionManager.THING_NAME_CHECKER;
import static com.aws.greengrass.android.service.DefaultGreengrassComponentService.NUCLEUS_SERVICE_NOT_ID;
import static java.util.concurrent.TimeUnit.SECONDS;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private ProvisionManager provisionManager;
    private ServicesConfigurationProvider servicesConfigProvider;
    private Executor mainExecutor = null;
    private JsonNode provisioningConfig = null;
    private static Logger logger;

    private final String thingNameStr = "Thing name:\n";

    private final ActivityResultLauncher<Intent> provisioningConfigResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null) {
                        processFile(data.getData());
                    }
                }
            });

    private final ActivityResultLauncher<Intent> servicesConfigResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null) {
                        try {
                            servicesConfigProvider.setExternalConfig(getContentResolver()
                                    .openInputStream(data.getData()));
                        } catch (FileNotFoundException e) {
                            logger.atError().setCause(e).log("File isn't found.");
                        }
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (logger == null) {
            logger = LogHelper.getLogger(this.getFilesDir(), MainActivity.class);
        }
        provisionManager = BaseProvisionManager.getInstance(getFilesDir());
        servicesConfigProvider = ServicesConfigurationProvider.getInstance(getFilesDir());
        mainExecutor = ContextCompat.getMainExecutor(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        View view = binding.getRoot();
        setContentView(view);

        bindConfigUI();
        bindStartStopUI();
        binding.version.setText(getString(R.string.label_app_version, BuildConfig.VERSION_NAME));

        if (provisionManager.isProvisioned()) {
            binding.thingNameText.setText(String.format("%s%s",
                    thingNameStr, provisionManager.getThingName()));
            switchUI(false);
        } else {
            provisionManager.clearProvision();
            switchUI(true);
        }
    }

    @Override
    protected void onDestroy() {
        try {
            backgroundExecutor.shutdown();
            if (!backgroundExecutor.awaitTermination(5, SECONDS)) {
                logger.atError().log("Timed out waiting to shutdown executor");
            }
        } catch (Throwable e) {
            logger.atError().setCause(e).log("Exception in onDestroy");
            backgroundExecutor.shutdownNow();
        } finally {
            binding = null;
            super.onDestroy();
        }
    }

    private void putThingNameToConfig(String key, String thingName) {
        try {
            ((ObjectNode) provisioningConfig).put(key,
                    thingName + "-"
                            + Settings.Secure.getString(getApplicationContext().getContentResolver(),
                            Settings.Secure.ANDROID_ID));
            binding.thingNameText.setText(String.format("%s%s", thingNameStr,
                    provisioningConfig.get(key).asText()));
        } catch (Throwable e) {
            logger.atError().setCause(e).log("Couldn't put thingName to the config.");
        }
    }

    private void bindConfigUI() {
        binding.provisioningConfigBtn.setOnClickListener(v -> openProvisioningConfigFileDialog());
        binding.servicesConfigBtn.setOnClickListener(v -> openServicesConfigFileDialog());

        binding.checkbox.setChecked(AutoStartDataStore.get(getApplicationContext()));
        binding.checkbox.setOnCheckedChangeListener(
                (buttonView, isChecked) -> AutoStartDataStore.set(getApplicationContext(), isChecked)
        );

        binding.appleBtn.setOnClickListener(v -> {
            if (provisioningConfig == null) {
                Toast.makeText(MainActivity.this,
                        R.string.please_select_config_file,
                        Toast.LENGTH_LONG).show();
            } else if ((!provisioningConfig.has(PROVISION_THING_NAME)
                    || Utils.isEmpty(provisioningConfig.get(PROVISION_THING_NAME).asText()))
                    && (!provisioningConfig.has(PROVISION_THING_NAME_SHORT)
                    || Utils.isEmpty(provisioningConfig.get(PROVISION_THING_NAME_SHORT).asText()))) {
                Editable thingName = binding.nameInputEdit.getText();
                if (Utils.isEmpty(thingName)) {
                    binding.nameInputLayout.setError(getString(R.string.thing_name_error));
                } else if (!thingName.toString().matches(THING_NAME_CHECKER)) {
                    binding.nameInputLayout.setError(getString(R.string.thing_name_error2));
                } else {
                    putThingNameToConfig(PROVISION_THING_NAME, thingName.toString());
                    binding.nameInputLayout.setError(null);
                    binding.nameInputEdit.setText(null);
                    switchUI(false);
                    launchNucleus(provisioningConfig);
                }
            } else {
                if (provisioningConfig.has(PROVISION_THING_NAME)) {
                    putThingNameToConfig(PROVISION_THING_NAME,
                            provisioningConfig.get(PROVISION_THING_NAME).asText());
                } else if (provisioningConfig.has(PROVISION_THING_NAME_SHORT)) {
                    putThingNameToConfig(PROVISION_THING_NAME_SHORT,
                            provisioningConfig.get(PROVISION_THING_NAME_SHORT).asText());
                }
                switchUI(false);
                launchNucleus(provisioningConfig);
            }
        });
    }

    private void openProvisioningConfigFileDialog() {
        Intent intent = new Intent()
                .setType("*/*")
                .setAction(ACTION_OPEN_DOCUMENT);
        Intent chooserIntent = Intent.createChooser(intent, getString(R.string.select_config_file));
        provisioningConfigResultLauncher.launch(chooserIntent);
    }

    private void openServicesConfigFileDialog() {
        Intent intent = new Intent()
                .setType("*/*")
                .setAction(ACTION_OPEN_DOCUMENT);
        Intent chooserIntent = Intent.createChooser(intent, getString(R.string.select_config_file));
        servicesConfigResultLauncher.launch(chooserIntent);
    }

    private void processFile(Uri uri) {
        backgroundExecutor.execute(() -> {
            provisioningConfig = parseFile(uri);
            mainExecutor.execute(() -> {
                if (provisioningConfig == null) {
                    Toast.makeText(MainActivity.this,
                            R.string.wrong_file,
                            Toast.LENGTH_LONG).show();
                    binding.fieldsText.setVisibility(GONE);
                    binding.fieldsText.setText("");
                } else {
                    if ((provisioningConfig.has(PROVISION_THING_NAME)
                            && !Utils.isEmpty(provisioningConfig.get(PROVISION_THING_NAME).asText()))
                            || (provisioningConfig.has(PROVISION_THING_NAME_SHORT)
                            && !Utils.isEmpty(provisioningConfig.get(PROVISION_THING_NAME_SHORT).asText()))) {
                        binding.nameInputLayout.setVisibility(GONE);
                    } else {
                        binding.nameInputLayout.setVisibility(VISIBLE);
                    }
                    binding.fieldsText.setVisibility(VISIBLE);
                    binding.fieldsText.setText(provisioningConfig.toString());
                    binding.appleBtn.setVisibility(VISIBLE);
                }
            });
        });
    }

    private void bindStartStopUI() {
        binding.startBtn.setOnClickListener(v -> {
                    if (NotificationsManager.isNucleusNotExist(this, NUCLEUS_SERVICE_NOT_ID)) {
                        Toast.makeText(MainActivity.this, R.string.nucleus_running, Toast.LENGTH_LONG).show();
                    } else {
                        if (provisionManager.isProvisioned()) {
                            launchNucleus(null);
                        } else {
                            launchNucleus(provisioningConfig);
                        }
                    }
                }
        );
        binding.stopBtn.setOnClickListener(v -> {
            if (!NotificationsManager.isNucleusNotExist(this, NUCLEUS_SERVICE_NOT_ID)) {
                Toast.makeText(MainActivity.this, R.string.nucleus_not_running, Toast.LENGTH_LONG).show();
            } else {
                finishNucleus();
            }
        });
        binding.resetBtn.setOnClickListener(v -> {
            if (NotificationsManager.isNucleusNotExist(this, NUCLEUS_SERVICE_NOT_ID)) {
                Toast.makeText(this, R.string.need_to_stop_nucleus, Toast.LENGTH_LONG).show();
            } else {
                provisionManager.clearNucleusConfig();
            }
        });
    }

    private void switchUI(Boolean showConfig) {
        if (showConfig) {
            binding.provisioningConfigBtn.setVisibility(VISIBLE);
            binding.servicesConfigBtn.setVisibility(VISIBLE);
            binding.appleBtn.setVisibility(VISIBLE);

            binding.thingNameText.setVisibility(GONE);
            binding.startBtn.setVisibility(GONE);
            binding.stopBtn.setVisibility(GONE);
            binding.resetBtn.setVisibility(GONE);
        } else {
            binding.provisioningConfigBtn.setVisibility(GONE);
            binding.servicesConfigBtn.setVisibility(GONE);
            binding.fieldsText.setVisibility(GONE);
            binding.nameInputLayout.setVisibility(GONE);
            binding.appleBtn.setVisibility(GONE);

            binding.thingNameText.setVisibility(VISIBLE);
            binding.startBtn.setVisibility(VISIBLE);
            binding.stopBtn.setVisibility(VISIBLE);
            binding.resetBtn.setVisibility(VISIBLE);
        }
    }

    /**
     * Parsing a file for provisioning.
     *
     * @param sourceUri path to file
     * @return Json
     */
    private JsonNode parseFile(Uri sourceUri) {
        JsonNode config = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        getContentResolver().openInputStream(sourceUri), StandardCharsets.UTF_8)
        )) {
            StringBuilder builder = new StringBuilder();
            String line = reader.readLine();
            while (line != null) {
                builder.append(line).append("\n");
                line = reader.readLine();
            }

            ObjectMapper mapper = new ObjectMapper();
            config = mapper.readTree(builder.toString());

        } catch (Throwable e) {
            logger.atError().setCause(e).log("Couldn't parse config file.");
        }
        return config;
    }

    /**
     * Starting Nucleus as Android Foreground Service.
     *
     * @param config new provisioning config
     */
    private void launchNucleus(JsonNode config) {
        provisionManager.setConfig(config);
        provisionManager.prepareAssetFiles(getApplicationContext());
        DefaultGreengrassComponentService.resetStartAttemptsCounter();
        DefaultGreengrassComponentService.launch(getApplicationContext());
    }

    /**
     * Stop Nucleus as Android Foreground Service.
     */
    private void finishNucleus() {
        backgroundExecutor.execute(() -> {
            DefaultGreengrassComponentService.finish(getApplicationContext());
        });
    }
}
