package com.bypass.projection;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.io.DataOutputStream;

public class SetupActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "module_prefs";
    private SharedPreferences prefs;

    private TextView iconHyperOS;
    private TextView textHyperOS;
    private TextView iconLSPosed;
    private TextView textLSPosed;
    private TextView iconRoot;
    private TextView textRoot;
    private MaterialButton btnContinue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Routing check: If setup is complete, route to MainActivity instantly
        // This is done BEFORE setContentView to prevent unnecessary UI inflation and speed up launch.
        java.io.File setupFlag = new java.io.File(getFilesDir(), "setup_complete.flag");
        if (setupFlag.exists()) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_setup);

        iconHyperOS = findViewById(R.id.iconHyperOS);
        textHyperOS = findViewById(R.id.textHyperOS);
        iconLSPosed = findViewById(R.id.iconLSPosed);
        textLSPosed = findViewById(R.id.textLSPosed);
        iconRoot = findViewById(R.id.iconRoot);
        textRoot = findViewById(R.id.textRoot);
        btnContinue = findViewById(R.id.btnContinue);

        btnContinue.setOnClickListener(v -> {
            try {
                new java.io.File(getFilesDir(), "setup_complete.flag").createNewFile();
            } catch (java.io.IOException e) {
                e.printStackTrace();
            }
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });

        // Run checks
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            checkHyperOS();
            checkLSPosed();
            checkRoot();
        }, 500); // Slight delay for visual effect
    }

    private void checkHyperOS() {
        String osCode = "";
        try {
            Class<?> sysProps = Class.forName("android.os.SystemProperties");
            osCode = (String) sysProps.getMethod("get", String.class, String.class)
                    .invoke(null, "ro.mi.os.version.code", "");
        } catch (Exception ignored) {}

        if ("3".equals(osCode)) {
            iconHyperOS.setText("✅");
            textHyperOS.setText("HyperOS 3 confirmed.");
        } else if (osCode != null && !osCode.isEmpty()) {
            iconHyperOS.setText("⚠️");
            textHyperOS.setText("HyperOS detected, but not version 3. Proceed with caution.");
        } else {
            iconHyperOS.setText("⚠️");
            textHyperOS.setText("Xiaomi HyperOS not detected. Features may fail.");
        }
    }

    private void checkLSPosed() {
        if (MainActivity.isModuleActive()) {
            iconLSPosed.setText("✅");
            textLSPosed.setText("Module is active in LSPosed.");
        } else {
            iconLSPosed.setText("❌");
            textLSPosed.setText("Module NOT active. Please enable in LSPosed and reboot.");
        }
    }

    private void checkRoot() {
        new Thread(() -> {
            boolean hasRoot = false;
            try {
                Process su = Runtime.getRuntime().exec("su -c id");
                su.waitFor();
                hasRoot = (su.exitValue() == 0);
            } catch (Exception e) {
                hasRoot = false;
            }
            
            final boolean finalHasRoot = hasRoot;
            runOnUiThread(() -> {
                if (finalHasRoot) {
                    iconRoot.setText("✅");
                    textRoot.setText("Root access granted. Quick-restart enabled.");
                } else {
                    iconRoot.setText("⚠️");
                    textRoot.setText("Root denied. Quick-restart will not work.");
                }
            });
        }).start();
    }
}
