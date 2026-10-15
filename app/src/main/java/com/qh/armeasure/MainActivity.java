package com.qh.armeasure;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.exceptions.UnavailableException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

/**
 * 入口:检查相机权限 + ARCore (Google Play Services for AR) 可用性,
 * 通过后跳到 {@link ArMeasureActivity} 进行 AR 测距。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 1;
    private boolean arCoreInstallRequested;

    private TextView statusText;
    private Button startButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusText = findViewById(R.id.txt_status);
        startButton = findViewById(R.id.btn_start);
        startButton.setOnClickListener(v -> tryStartMeasure());
        refreshUi();
    }

    private void refreshUi() {
        boolean camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        startButton.setEnabled(camOk);
        statusText.setText(camOk
                ? getString(R.string.msg_ready)
                : getString(R.string.msg_need_camera));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            refreshUi();
        }
    }

    private void tryStartMeasure() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        try {
            ArCoreApk.Availability aw = ArCoreApk.getInstance().checkAvailability(this);
            if (!aw.isSupported()) {
                statusText.setText(R.string.msg_no_arcore);
                return;
            }
            // ARCore 必装/升级时先弹系统安装流程,装好后再进测量页
            ArCoreApk.InstallStatus status =
                    ArCoreApk.getInstance().requestInstall(this, !arCoreInstallRequested);
            if (status == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                // 安装/升级流程已弹起,装完回来用户会再点"开始测距"
                arCoreInstallRequested = true;
                return;
            }
            startActivity(new Intent(this, ArMeasureActivity.class));
        } catch (UnavailableUserDeclinedInstallationException e) {
            statusText.setText(R.string.msg_declined_install);
        } catch (UnavailableException e) {
            statusText.setText(getString(R.string.msg_ar_unavailable, e.getClass().getSimpleName()));
        }
    }
}
