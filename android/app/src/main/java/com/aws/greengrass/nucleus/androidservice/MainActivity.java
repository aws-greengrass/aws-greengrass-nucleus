/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.nucleus.androidservice;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.aws.greengrass.android.managers.NotManager;
import com.aws.greengrass.android.provision.AutoStartDataStore;
import com.aws.greengrass.android.provision.BaseProvisionManager;
import com.aws.greengrass.android.provision.ProvisionManager;
import com.aws.greengrass.android.service.NucleusForegroundService;
import com.aws.greengrass.nucleus.androidservice.databinding.ActivityMainBinding;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static android.content.Intent.ACTION_OPEN_DOCUMENT;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static com.aws.greengrass.android.provision.BaseProvisionManager.KERNEL_INIT_CONFIG_ARG;
import static com.aws.greengrass.android.provision.BaseProvisionManager.PROVISION_THING_NAME;
import static com.aws.greengrass.android.provision.BaseProvisionManager.THING_NAME_CHECKER;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private ProvisionManager provisionManager;
    private Executor mainExecutor = null;
    private JsonNode provisioningConfig = null;
    private Uri servicesConfigUri = null;

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
                        servicesConfigUri = data.getData();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        provisionManager = BaseProvisionManager.getInstance(getFilesDir());
        mainExecutor = ContextCompat.getMainExecutor(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        View view = binding.getRoot();
        setContentView(view);

        bindConfigUI();
        bindStartStopUI();

        if (provisionManager.isProvisioned()) {
            switchUI(false);
            if (AutoStartDataStore.get(getApplicationContext())) {
                provisionManager.setConfig(null);
                NucleusForegroundService.launch(getApplicationContext());
            }
        } else {
            provisionManager.clearProvision();
            switchUI(true);
        }
    }

    @Override
    protected void onDestroy() {
        backgroundExecutor.shutdownNow();
        binding = null;
        super.onDestroy();
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
            } else if (!provisioningConfig.has(PROVISION_THING_NAME)
                    || Utils.isEmpty(provisioningConfig.get(PROVISION_THING_NAME).asText())) {
                Editable thingName = binding.nameInputEdit.getText();
                if (TextUtils.isEmpty(thingName)) {
                    binding.nameInputLayout.setError(getString(R.string.thing_name_error));
                } else if (!thingName.toString().matches(THING_NAME_CHECKER)) {
                    binding.nameInputLayout.setError(getString(R.string.thing_name_error2));
                } else {
                    try {
                        ((ObjectNode) provisioningConfig).put(PROVISION_THING_NAME, thingName.toString());
                    } catch (Throwable e) {
                        e.printStackTrace();
                    }
                    binding.nameInputLayout.setError(null);
                    binding.nameInputEdit.setText(null);
                    switchUI(false);
                    provisionManager.setConfig(provisioningConfig);
                    NucleusForegroundService.launch(getApplicationContext());
                }
            } else {
                switchUI(false);
                provisionManager.setConfig(provisioningConfig);
                NucleusForegroundService.launch(getApplicationContext());
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
                    if (provisioningConfig.has(PROVISION_THING_NAME)
                            && !Utils.isEmpty(provisioningConfig.get(PROVISION_THING_NAME).asText())) {
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
                    if (NotManager.isNucleusNotExist(MainActivity.this)) {
                        Toast.makeText(MainActivity.this, R.string.nucleus_running, Toast.LENGTH_LONG).show();
                    } else {
                        backgroundExecutor.execute(() -> {
                            provisionManager.setConfig(provisioningConfig);
                            NucleusForegroundService.launch(MainActivity.this.getApplicationContext());
                        });
                    }
                }
        );
        binding.stopBtn.setOnClickListener(v -> {
            if (!NotManager.isNucleusNotExist(MainActivity.this)) {
                Toast.makeText(MainActivity.this, R.string.nucleus_not_running, Toast.LENGTH_LONG).show();
            } else {
                backgroundExecutor.execute(() ->
                        NucleusForegroundService.finish(MainActivity.this.getApplicationContext()));
            }
        });
        binding.resetBtn.setOnClickListener(v -> {
            if (NotManager.isNucleusNotExist(this)) {
                Toast.makeText(this, R.string.need_to_stop_nucleus, Toast.LENGTH_LONG).show();
            } else {
                provisionManager.clearProvision();
                switchUI(true);
            }
        });
    }

    private void switchUI(Boolean showConfig) {
        if (showConfig) {
            binding.provisioningConfigBtn.setVisibility(VISIBLE);
            binding.servicesConfigBtn.setVisibility(VISIBLE);
            binding.appleBtn.setVisibility(VISIBLE);
            binding.checkbox.setVisibility(VISIBLE);

            binding.startBtn.setVisibility(GONE);
            binding.stopBtn.setVisibility(GONE);
            binding.resetBtn.setVisibility(GONE);
        } else {
            binding.provisioningConfigBtn.setVisibility(GONE);
            binding.servicesConfigBtn.setVisibility(GONE);
            binding.fieldsText.setVisibility(GONE);
            binding.checkbox.setVisibility(GONE);
            binding.nameInputLayout.setVisibility(GONE);
            binding.appleBtn.setVisibility(GONE);

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
            e.printStackTrace();
        }
        return config;
    }
}
