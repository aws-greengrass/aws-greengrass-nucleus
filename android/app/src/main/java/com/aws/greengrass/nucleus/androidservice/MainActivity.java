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
import com.aws.greengrass.android.provision.AutoStartDataStore;
import com.aws.greengrass.android.provision.ProvisionManager;
import com.aws.greengrass.android.service.NucleusForegroundService;
import com.aws.greengrass.nucleus.androidservice.databinding.ActivityMainBinding;
import org.json.JSONObject;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static android.content.Intent.ACTION_OPEN_DOCUMENT;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static com.aws.greengrass.android.provision.ProvisionManager.PROVISION_THING_NAME;
import static com.aws.greengrass.android.provision.ProvisionManager.THING_NAME_CHECKER;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final ProvisionManager provisionManager = new ProvisionManager();
    private Executor mainExecutor = null;
    private JSONObject config = null;

    private final ActivityResultLauncher<Intent> resultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null) {
                        processFile(data.getData());
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainExecutor = ContextCompat.getMainExecutor(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        View view = binding.getRoot();
        setContentView(view);

        bindConfigUI();
        bindStartStopUI();

        if (provisionManager.isProvisioned(getApplicationContext())) {
            switchUI(false);
            if (AutoStartDataStore.get(getApplicationContext())) {
                NucleusForegroundService.launch(getApplicationContext(), null);
            }
        } else {
            provisionManager.clear(getApplicationContext());
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
        binding.configBtn.setOnClickListener(v -> openFileDialog());

        binding.checkbox.setChecked(AutoStartDataStore.get(getApplicationContext()));
        binding.checkbox.setOnCheckedChangeListener(
                (buttonView, isChecked) -> AutoStartDataStore.set(getApplicationContext(), isChecked)
        );

        binding.appleBtn.setOnClickListener(v -> {
            if (config == null) {
                Toast.makeText(MainActivity.this,
                        R.string.please_select_config_file,
                        Toast.LENGTH_LONG).show();
            } else if (!config.has(PROVISION_THING_NAME)) {
                Editable thingName = binding.nameInputEdit.getText();
                if (TextUtils.isEmpty(thingName)) {
                    binding.nameInputLayout.setError(getString(R.string.thing_name_error));
                } else if (!thingName.toString().matches(THING_NAME_CHECKER)) {
                    binding.nameInputLayout.setError(getString(R.string.thing_name_error2));
                } else {
                    try {
                        config.put(PROVISION_THING_NAME, thingName);
                    } catch (Throwable e) {
                        e.printStackTrace();
                    }
                    binding.nameInputLayout.setError(null);
                    switchUI(false);
                    NucleusForegroundService.launch(getApplicationContext(), config);
                }
            } else {
                switchUI(false);
                NucleusForegroundService.launch(getApplicationContext(), config);
            }
        });
    }

    private void openFileDialog() {
        Intent intent = new Intent()
                .setType("*/*")
                .setAction(ACTION_OPEN_DOCUMENT);
        Intent chooserIntent = Intent.createChooser(intent, getString(R.string.select_config_file));
        resultLauncher.launch(chooserIntent);
    }

    private void processFile(Uri uri) {
        backgroundExecutor.execute(() -> {
            config = provisionManager.parseFile(getApplicationContext(), uri);
            mainExecutor.execute(() -> {
                if (config == null) {
                    Toast.makeText(MainActivity.this,
                            R.string.wrong_file,
                            Toast.LENGTH_LONG).show();
                    binding.fieldsText.setVisibility(GONE);
                    binding.fieldsText.setText("");
                } else {
                    if (config.has(PROVISION_THING_NAME)) {
                        binding.nameInputLayout.setVisibility(GONE);
                    } else {
                        binding.nameInputLayout.setVisibility(VISIBLE);
                    }
                    binding.fieldsText.setVisibility(VISIBLE);
                    binding.fieldsText.setText(config.toString());
                    binding.appleBtn.setVisibility(VISIBLE);
                }
            });
        });
    }

    private void bindStartStopUI() {
        binding.startBtn.setOnClickListener(v -> backgroundExecutor.execute(() ->
                NucleusForegroundService.launch(MainActivity.this.getApplicationContext(), config)));
        binding.stopBtn.setOnClickListener(v -> {
            switchUI(true);
            backgroundExecutor.execute(() -> {
                provisionManager.clear(getApplicationContext());
                NucleusForegroundService.finish(MainActivity.this.getApplicationContext(), null);
            });
        });
    }

    private void switchUI(Boolean showConfig) {
        if (showConfig) {
            binding.configBtn.setVisibility(VISIBLE);
            binding.appleBtn.setVisibility(VISIBLE);
            binding.checkbox.setVisibility(VISIBLE);

            binding.startBtn.setVisibility(GONE);
            binding.stopBtn.setVisibility(GONE);
        } else {
            binding.configBtn.setVisibility(GONE);
            binding.fieldsText.setVisibility(GONE);
            binding.checkbox.setVisibility(GONE);
            binding.nameInputLayout.setVisibility(GONE);
            binding.appleBtn.setVisibility(GONE);

            binding.startBtn.setVisibility(VISIBLE);
            binding.stopBtn.setVisibility(VISIBLE);
        }
    }
}
