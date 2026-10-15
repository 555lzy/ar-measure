package com.qh.armeasure;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import com.google.ar.core.Anchor;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Pose;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.Color;
import com.google.ar.sceneform.rendering.Material;
import com.google.ar.sceneform.rendering.MaterialFactory;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.rendering.ShapeFactory;
import com.google.ar.sceneform.rendering.ViewRenderable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 核心测距 Activity —— 复刻 com.qh.measure(扫描测量全能王)的「测距」功能:
 *
 *  1. ARCore 开启平面/特征点追踪(对应原版 libarcore_sdk_c.so / libarcore_sdk_jni.so 的能力)
 *  2. 用户点击屏幕上的平面 → frame.hitTest 得到 3D 空间 Pose → 建立锚点(AnchorNode)
 *  3. 相邻两个锚点之间:
 *       - 画一个小球标记点("钉子")
 *       - 用 1cm × 1cm × L 的细长立方体连接两点,长度即两点直线距离
 *       - 中间挂 ViewRenderable 文字标签,实时显示 m/cm/ft/in
 *  4. 支持多点连续测量(折线总长 + 首尾直线距离显示在 HUD),支持切换单位、清空重测
 */
public class ArMeasureActivity extends AppCompatActivity {

    private enum Unit { CENTIMETER, METER, FOOT, INCH }

    private MeasureArFragment arFragment;
    private TextView hudText;
    private Unit unit = Unit.CENTIMETER;

    private final List<AnchorNode> anchorNodes = new ArrayList<>();
    private final List<Node> markers = new ArrayList<>();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ar_measure);
        hudText = findViewById(R.id.txt_hud);
        Button btnClear = findViewById(R.id.btn_clear);
        Button btnUnit = findViewById(R.id.btn_unit);
        btnClear.setOnClickListener(v -> clearAll());
        btnUnit.setOnClickListener(v -> showUnitDialog());

        FragmentManager fm = getSupportFragmentManager();
        arFragment = (MeasureArFragment) fm.findFragmentById(R.id.ar_fragment);
        if (arFragment == null) {
            finish();
            return;
        }

        // 点击平面 → 落点测量(与原版"对准平面后点一下"的交互一致)。
        // 平面检测模式/聚焦模式见 MeasureArFragment.getSessionConfiguration
        arFragment.setOnTapArPlaneListener(this::onTapPlane);
    }

    private void onTapPlane(HitResult hitResult, Plane plane, MotionEvent motionEvent) {
        if (busy.get() || hitResult == null) {
            return;
        }
        com.google.ar.core.Trackable trackable = hitResult.getTrackable();
        if (trackable == null || trackable.getTrackingState() != com.google.ar.core.TrackingState.TRACKING) {
            return;
        }
        busy.set(true);
        try {
            Anchor anchor = hitResult.createAnchor();
            Pose pose = hitResult.getHitPose();

            // 1. AnchorNode:让测量点固定在物理世界中
            AnchorNode newAnchorNode = new AnchorNode(anchor);
            newAnchorNode.setParent(arFragment.getArSceneView().getScene());
            newAnchorNode.setWorldPosition(new Vector3(pose.tx(), pose.ty(), pose.tz()));

            // 2. 球形标记"钉子"
            MaterialFactory.makeOpaqueWithColor(this, rgb(0xFF, 0x44, 0x36))
                    .thenAccept(material ->
                            addMarker(newAnchorNode, newAnchorNode.getWorldPosition(), material));

            // 3. 与上一个点连线 + 距离标签
            int n = anchorNodes.size();
            if (n > 0) {
                AnchorNode prevNode = anchorNodes.get(n - 1);
                Vector3 prevPos = prevNode.getWorldPosition();
                Vector3 currPos = newAnchorNode.getWorldPosition();
                float distMeters = Vector3.subtract(prevPos, currPos).length();
                if (distMeters >= 0.005f) {
                    MaterialFactory.makeOpaqueWithColor(this, rgb(0xFF, 0xEB, 0x3B))
                            .thenAccept(material ->
                                    addLineSegment(prevNode, newAnchorNode, material, distMeters));
                }
            }
            anchorNodes.add(newAnchorNode);
        } finally {
            busy.set(false);
        }
        updateHud();
    }

    private void addMarker(AnchorNode parent, Vector3 pos, Material material) {
        ModelRenderable sphere = ShapeFactory.makeSphere(0.008f, pos, material);
        Node marker = new Node();
        marker.setParent(parent);
        marker.setLocalPosition(Vector3.zero());
        marker.setRenderable(sphere);
        markers.add(marker);
    }

    /** 两个锚点之间画一根"尺条",末点挂距离标签 */
    private void addLineSegment(AnchorNode prevNode, AnchorNode currNode,
                                Material material, float distMeters) {
        Vector3 start = prevNode.getWorldPosition();
        Vector3 end = currNode.getWorldPosition();
        Vector3 diff = Vector3.subtract(end, start);
        if (diff.length() < 0.01f) {
            return; // 两点几乎重合,不画
        }
        Vector3 center = Vector3.add(start, end).scaled(0.5f);
        Vector3 size = new Vector3(0.010f, 0.010f, diff.length());
        ModelRenderable lineRenderable = ShapeFactory.makeCube(size, center, material);
        lineRenderable.setShadowReceiver(false);
        lineRenderable.setShadowCaster(false);

        // makeCube 沿 Z 轴生成,把节点旋转对齐 start→end 方向
        Node lineNode = new Node();
        lineNode.setParent(prevNode);
        lineNode.setWorldPosition(center);
        Vector3 direction = diff.normalized();
        lineNode.setLocalRotation(Quaternion.rotationBetweenVectors(
                new Vector3(0f, 0f, 1f), direction));
        lineNode.setRenderable(lineRenderable);

        // 距离标签(浮在末点上方,始终朝向镜头)
        ViewRenderable.builder()
                .setView(this, R.layout.view_distance_label)
                .build()
                .thenAccept(labelRenderable -> {
                    Node labelNode = new Node();
                    labelNode.setParent(currNode);
                    labelNode.setLocalPosition(new Vector3(0f, 0.05f, 0f));
                    labelNode.setRenderable(labelRenderable);
                    View v = labelRenderable.getView();
                    if (v instanceof TextView) {
                        ((TextView) v).setText(formatDistance(distMeters));
                    }
                });
    }

    // ---------- HUD / 单位 / 清空 ----------

    private void updateHud() {
        if (hudText == null) {
            return;
        }
        int n = anchorNodes.size();
        if (n < 2) {
            hudText.setText(getString(R.string.hud_hint, n));
            return;
        }
        float total = 0f;
        for (int i = 1; i < n; i++) {
            total += Vector3.subtract(anchorNodes.get(i - 1).getWorldPosition(),
                    anchorNodes.get(i).getWorldPosition()).length();
        }
        if (n == 2) {
            hudText.setText(getString(R.string.hud_result_line, formatDistance(total)));
        } else {
            float straight = Vector3.subtract(anchorNodes.get(0).getWorldPosition(),
                    anchorNodes.get(n - 1).getWorldPosition()).length();
            hudText.setText(getString(R.string.hud_result_poly,
                    formatDistance(total), formatDistance(straight)));
        }
    }

    private String formatDistance(float meters) {
        switch (unit) {
            case METER:      return String.format("%.2f m", meters);
            case CENTIMETER: return String.format("%.1f cm", meters * 100f);
            case FOOT:       return String.format("%.2f ft", meters * 3.28084f);
            case INCH:       return String.format("%.1f in", meters * 39.3701f);
            default:         return String.format("%.2f m", meters);
        }
    }

    private void showUnitDialog() {
        String[] names = getResources().getStringArray(R.array.units);
        new AlertDialog.Builder(this)
                .setTitle(R.string.pick_unit)
                .setItems(names, (dialog, which) -> {
                    unit = Unit.values()[which];
                    updateHud();
                })
                .show();
    }

    private void clearAll() {
        for (AnchorNode an : anchorNodes) {
            Anchor anchor = an.getAnchor();
            if (anchor != null) {
                anchor.detach();
            }
            an.setParent(null);
        }
        anchorNodes.clear();
        markers.clear();
        updateHud();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Sceneform 1.17 渲染资源随 View 回收,这里只清引用
        arFragment = null;
    }

    private static Color rgb(int r, int g, int b) {
        return new Color(r / 255f, g / 255f, b / 255f, 1f);
    }
}
