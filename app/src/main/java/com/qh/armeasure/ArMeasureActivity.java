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
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.InstantPlacementPoint;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Pose;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.Color;
import com.google.ar.sceneform.rendering.Material;
import com.google.ar.sceneform.rendering.MaterialFactory;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.rendering.ShapeFactory;
import com.google.ar.sceneform.rendering.ViewRenderable;
import com.google.ar.sceneform.rendering.ViewSizer;

import java.util.ArrayList;
import java.util.List;

/**
 * AR 测距 v4 —— 交互手感对齐原版:
 *
 *  1. 点哪测哪:IPP(即时落点)命中最高优先,无需等待平面识别,无需晃手机。
 *  2. 落点即焊死:命中瞬间 createAnchor,数字立刻稳定,不再随 IPP 精化漂移。
 *  3. 实时预览:落过 1 个点后,准星与上一点之间实时显示预览尺条 + 距离,
 *     按下「落点」前就能看到将要测出的值。
 *  4. 性能:标签 ViewRenderable 固定世界尺寸(10cm 宽,远近一致),
 *     HUD 更新按时间节流,每帧零对象分配热点。
 *  5. 标签沿尺条法线偏移并朝向相机,垂直面测量不再飘。
 */
public class ArMeasureActivity extends AppCompatActivity {

    private enum Unit { CENTIMETER, METER, FOOT, INCH }

    private static final float POINT_RADIUS = 0.010f;   // 落点半径 1cm
    private static final float LINE_THICKNESS = 0.008f; // 尺条粗细 8mm
    private static final long HUD_INTERVAL_MS = 150;    // HUD 刷新节流

    private MeasureArFragment arFragment;
    private TextView hudText;
    private TextView tipText;
    private Unit unit = Unit.CENTIMETER;

    /** 测量点:落点瞬间焊死在固定 Anchor 上 */
    private static final class MeasurePoint {
        Node marker;
        AnchorNode fixedNode;
        volatile boolean removed;   // undo 后抑制异步回调再上屏
    }

    private final List<MeasurePoint> points = new ArrayList<>();
    private final List<MeasureSegment> segments = new ArrayList<>();

    /** 两点间一段尺条 + 距离标签 */
    private static final class MeasureSegment {
        MeasurePoint start;
        MeasurePoint end;
        Node lineNode;
        Node labelNode;
        TextView labelView;
    }

    /** 准星实时预览:上一点 → 当前准星指向 */
    private Node previewLine;
    private Node previewLabel;
    private TextView previewView;
    private Material lineMaterial;
    private Material pointMaterial;

    private long lastHudUpdateAt;

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ar_measure);
        hudText = findViewById(R.id.txt_hud);
        tipText = findViewById(R.id.txt_tip);
        Button btnPlace = findViewById(R.id.btn_place);
        Button btnUndo = findViewById(R.id.btn_undo);
        Button btnClear = findViewById(R.id.btn_clear);
        Button btnUnit = findViewById(R.id.btn_unit);
        btnPlace.setOnClickListener(v -> placeAtScreenCenter());
        btnUndo.setOnClickListener(v -> undoLast());
        btnClear.setOnClickListener(v -> clearAll());
        btnUnit.setOnClickListener(v -> showUnitDialog());

        FragmentManager fm = getSupportFragmentManager();
        arFragment = (MeasureArFragment) fm.findFragmentById(R.id.ar_fragment);
        if (arFragment == null) {
            finish();
            return;
        }

        // 点屏幕任意处落点
        arFragment.getArSceneView().getScene().setOnTouchListener((hitTestResult, motionEvent) -> {
            if (motionEvent.getAction() != MotionEvent.ACTION_UP) {
                return false;
            }
            handleTap(motionEvent);
            return true;
        });

        // 预渲染两份材质,落点时零等待
        MaterialFactory.makeOpaqueWithColor(this, rgb(0xFF, 0x3B, 0x30))
                .thenAccept(m -> pointMaterial = m);
        MaterialFactory.makeOpaqueWithColor(this, rgb(0xFF, 0xC1, 0x07))
                .thenAccept(m -> lineMaterial = m);

        arFragment.getArSceneView().getScene().addOnUpdateListener(this::onSceneUpdate);
    }

    // ---------------- 落点 ----------------

    private void placeAtScreenCenter() {
        View arView = arFragment == null ? null : arFragment.getView();
        if (arView == null) return;
        MotionEvent center = MotionEvent.obtain(
                0, 0, MotionEvent.ACTION_DOWN,
                arView.getWidth() / 2f, arView.getHeight() / 2f, 0);
        handleTap(center);
        center.recycle();
    }

    private void handleTap(MotionEvent motionEvent) {
        Frame frame = arFragment.getArSceneView().getArFrame();
        if (frame == null || frame.getCamera().getTrackingState() != TrackingState.TRACKING) {
            showTip(getString(R.string.tip_locating));
            return;
        }
        HitResult chosen = chooseBestHit(frame.hitTest(motionEvent));
        if (chosen == null) {
            showTip(getString(R.string.tip_no_surface));
            return;
        }
        placePoint(chosen);
    }

    /** IPP 即时落点 > 平面(多边形内) > 平面(扩展) > 特征点 —— 点哪测哪,不等平面 */
    private static HitResult chooseBestHit(List<HitResult> hits) {
        if (hits == null) return null;
        HitResult ippHit = null, planeInPoly = null, planeAny = null, pointHit = null;
        for (HitResult h : hits) {
            if (h == null || h.getTrackable() == null
                    || h.getTrackable().getTrackingState() != TrackingState.TRACKING) {
                continue;
            }
            Trackable t = h.getTrackable();
            if (t instanceof InstantPlacementPoint) {
                if (ippHit == null) ippHit = h;
            } else if (t instanceof Plane) {
                if (planeInPoly == null && ((Plane) t).isPoseInPolygon(h.getHitPose())) {
                    planeInPoly = h;
                } else if (planeAny == null) {
                    planeAny = h;
                }
            } else if (t instanceof Point && pointHit == null) {
                pointHit = h;
            }
        }
        if (ippHit != null) return ippHit;
        if (planeInPoly != null) return planeInPoly;
        if (planeAny != null) return planeAny;
        return pointHit;
    }

    private void placePoint(HitResult chosen) {
        if (pointMaterial == null) {
            return; // 材质未就绪(开机后头一两秒),下一帧再点即可
        }
        try {
            // 命中瞬间建固定锚 —— 数字从这一刻起不再漂移
            Anchor anchor = chosen.createAnchor();
            Pose pose = anchor.getPose();

            final MeasurePoint mp = new MeasurePoint();
            AnchorNode node = new AnchorNode(anchor);
            node.setParent(arFragment.getArSceneView().getScene());
            mp.fixedNode = node;

            ModelRenderable sphere = ShapeFactory.makeSphere(
                    POINT_RADIUS, Vector3.zero(), pointMaterial);
            Node marker = new Node();
            marker.setParent(node);
            marker.setLocalPosition(Vector3.zero());
            marker.setRenderable(sphere);
            mp.marker = marker;

            int n = points.size();
            if (n > 0) {
                addSegment(points.get(n - 1), mp);
            }
            points.add(mp);
            updateHudNow();
        } catch (Exception e) {
            showTip(getString(R.string.tip_place_failed));
        }
    }

    private void addSegment(MeasurePoint start, MeasurePoint end) {
        final MeasureSegment seg = new MeasureSegment();
        seg.start = start;
        seg.end = end;

        ModelRenderable lineR = ShapeFactory.makeCube(
                new Vector3(LINE_THICKNESS, LINE_THICKNESS, 1.0f), Vector3.zero(), lineMaterial);
        lineR.setShadowReceiver(false);
        lineR.setShadowCaster(false);
        Node lineNode = new Node();
        lineNode.setParent(arFragment.getArSceneView().getScene());
        lineNode.setRenderable(lineR);
        seg.lineNode = lineNode;

        // 标签固定世界尺寸:宽 10cm 高 2.6cm,远近大小一致,不再糊脸/缩成蚂蚁
        ViewRenderable.builder()
                .setView(this, R.layout.view_distance_label)
                .setSizer((ViewSizer) view -> new Vector3(0.10f, 0.026f, 1f))
                .build()
                .thenAccept(labelR -> {
                    if (seg.start.removed || seg.end.removed) return;
                    Node labelNode = new Node();
                    labelNode.setParent(arFragment.getArSceneView().getScene());
                    labelNode.setRenderable(labelR);
                    seg.labelNode = labelNode;
                    seg.labelView = (TextView) labelR.getView();
                });
        segments.add(seg);
    }

    // ---------------- 每帧更新 ----------------

    private void onSceneUpdate(FrameTime frameTime) {
        updateSegments();
        updatePreview();
        long now = System.currentTimeMillis();
        if (now - lastHudUpdateAt >= HUD_INTERVAL_MS) {
            lastHudUpdateAt = now;
            updateHudNow();
        }
    }

    private void updateSegments() {
        Vector3 camPos = arFragment.getArSceneView().getScene().getCamera().getWorldPosition();
        for (int i = 0; i < segments.size(); i++) {
            MeasureSegment seg = segments.get(i);
            if (seg.lineNode == null || seg.start == null || seg.end == null) continue;
            Vector3 p0 = seg.start.marker.getWorldPosition();
            Vector3 p1 = seg.end.marker.getWorldPosition();
            float len = distance(p0, p1);
            if (len < 0.005f) {
                seg.lineNode.setEnabled(false);
                if (seg.labelNode != null) seg.labelNode.setEnabled(false);
                continue;
            }
            Vector3 diff = Vector3.subtract(p1, p0);
            Vector3 center = Vector3.add(p0, p1).scaled(0.5f);
            seg.lineNode.setEnabled(true);
            seg.lineNode.setWorldPosition(center);
            seg.lineNode.setLocalRotation(Quaternion.rotationBetweenVectors(
                    new Vector3(0f, 0f, 1f), diff.normalized()));
            seg.lineNode.setWorldScale(new Vector3(1f, 1f, len));
            placeLabel(seg.labelNode, seg.labelView, center, diff, camPos, len);
        }
    }

    /** 准星实时预览:上一点 → 当前准星指向的虚拟尺条 */
    private void updatePreview() {
        if (points.isEmpty()) {
            setPreviewVisible(false);
            return;
        }
        Frame frame = arFragment.getArSceneView().getArFrame();
        if (frame == null || frame.getCamera().getTrackingState() != TrackingState.TRACKING) {
            setPreviewVisible(false);
            return;
        }
        View arView = arFragment.getView();
        if (arView == null) {
            setPreviewVisible(false);
            return;
        }
        List<HitResult> hits = frame.hitTest(
                arView.getWidth() / 2f, arView.getHeight() / 2f);
        HitResult chosen = chooseBestHit(hits);
        if (chosen == null) {
            setPreviewVisible(false);
            return;
        }
        Pose hitPose = chosen.getHitPose();
        Vector3 target = new Vector3(hitPose.tx(), hitPose.ty(), hitPose.tz());

        MeasurePoint last = points.get(points.size() - 1);
        Vector3 p0 = last.marker.getWorldPosition();
        float len = distance(p0, target);
        if (len < 0.005f) {
            setPreviewVisible(false);
            return;
        }

        if (previewLine == null) {
            createPreviewNodes();
            if (previewLine == null) return;
        }

        Vector3 diff = Vector3.subtract(target, p0);
        Vector3 center = Vector3.add(p0, target).scaled(0.5f);
        Vector3 camPos = arFragment.getArSceneView().getScene().getCamera().getWorldPosition();

        previewLine.setEnabled(true);
        previewLine.setWorldPosition(center);
        previewLine.setLocalRotation(Quaternion.rotationBetweenVectors(
                new Vector3(0f, 0f, 1f), diff.normalized()));
        previewLine.setWorldScale(new Vector3(1f, 1f, len));
        placeLabel(previewLabel, previewView, center, diff, camPos, len);
    }

    private void createPreviewNodes() {
        if (lineMaterial == null) return;
        ModelRenderable lineR = ShapeFactory.makeCube(
                new Vector3(LINE_THICKNESS * 0.6f, LINE_THICKNESS * 0.6f, 1.0f),
                Vector3.zero(), lineMaterial);
        lineR.setShadowReceiver(false);
        lineR.setShadowCaster(false);
        previewLine = new Node();
        previewLine.setParent(arFragment.getArSceneView().getScene());
        previewLine.setRenderable(lineR);

        ViewRenderable.builder()
                .setView(this, R.layout.view_distance_label)
                .setSizer((ViewSizer) view -> new Vector3(0.10f, 0.026f, 1f))
                .build()
                .thenAccept(labelR -> {
                    previewLabel = new Node();
                    previewLabel.setParent(arFragment.getArSceneView().getScene());
                    previewLabel.setRenderable(labelR);
                    previewView = (TextView) labelR.getView();
                });
    }

    private void setPreviewVisible(boolean visible) {
        if (previewLine != null) previewLine.setEnabled(visible);
        if (previewLabel != null) previewLabel.setEnabled(visible);
    }

    /**
     * 标签放在尺条中点旁:沿「尺条方向 × 相机方向」的法线偏移 4cm,
     * 再朝向相机 —— 水平/垂直面都不飘、不遮挡尺条。
     */
    private void placeLabel(Node labelNode, TextView labelView,
                            Vector3 center, Vector3 dir, Vector3 camPos, float len) {
        if (labelNode == null) return;
        Vector3 toCam = Vector3.subtract(camPos, center).normalized();
        // 手写 cross(Sceneform Vector3 无 cross):dir × toCam
        Vector3 perp = new Vector3(
                dir.y * toCam.z - dir.z * toCam.y,
                dir.z * toCam.x - dir.x * toCam.z,
                dir.x * toCam.y - dir.y * toCam.x);
        perp = perp.normalized();
        if (Float.isNaN(perp.x)) perp = Vector3.up();
        Vector3 labelPos = Vector3.add(center, perp.scaled(0.04f));
        labelNode.setEnabled(true);
        labelNode.setWorldPosition(labelPos);
        labelNode.setLookDirection(
                Vector3.subtract(camPos, labelPos).normalized(), Vector3.up());
        if (labelView != null) {
            labelView.setText(formatDistance(len));
        }
    }

    // ---------------- HUD / 撤销 / 单位 / 清空 ----------------

    private void updateHudNow() {
        if (hudText == null || arFragment == null) return;
        Frame frame = arFragment.getArSceneView().getArFrame();
        if (frame == null || frame.getCamera().getTrackingState() != TrackingState.TRACKING) {
            hudText.setText(R.string.hud_locating);
            return;
        }
        int n = points.size();
        if (n == 0) {
            hudText.setText(R.string.hud_start);
            return;
        }
        if (n == 1) {
            hudText.setText(R.string.hud_one_point);
            return;
        }
        float total = 0f;
        for (int i = 0; i < segments.size(); i++) {
            MeasureSegment seg = segments.get(i);
            if (seg.start != null && seg.end != null) {
                total += distance(seg.start.marker.getWorldPosition(),
                        seg.end.marker.getWorldPosition());
            }
        }
        if (n == 2) {
            hudText.setText(getString(R.string.hud_result_line, formatDistance(total)));
        } else {
            float straight = distance(points.get(0).marker.getWorldPosition(),
                    points.get(n - 1).marker.getWorldPosition());
            hudText.setText(getString(R.string.hud_result_poly,
                    formatDistance(total), formatDistance(straight)));
        }
    }

    private void showTip(CharSequence text) {
        if (tipText != null) {
            tipText.setText(text);
            tipText.setVisibility(View.VISIBLE);
            tipText.postDelayed(() -> {
                if (tipText != null) tipText.setVisibility(View.GONE);
            }, 1500);
        }
    }

    private void undoLast() {
        int n = points.size();
        if (n == 0) return;
        MeasurePoint last = points.remove(n - 1);
        last.removed = true;
        detachPoint(last);
        if (!segments.isEmpty()) {
            MeasureSegment seg = segments.remove(segments.size() - 1);
            if (seg.lineNode != null) seg.lineNode.setParent(null);
            if (seg.labelNode != null) seg.labelNode.setParent(null);
        }
        updateHudNow();
    }

    private void clearAll() {
        for (int i = 0; i < points.size(); i++) {
            MeasurePoint mp = points.get(i);
            mp.removed = true;
            detachPoint(mp);
        }
        for (int i = 0; i < segments.size(); i++) {
            MeasureSegment seg = segments.get(i);
            if (seg.lineNode != null) seg.lineNode.setParent(null);
            if (seg.labelNode != null) seg.labelNode.setParent(null);
        }
        points.clear();
        segments.clear();
        setPreviewVisible(false);
        updateHudNow();
    }

    private void detachPoint(MeasurePoint mp) {
        if (mp.fixedNode != null) {
            Anchor a = mp.fixedNode.getAnchor();
            if (a != null) a.detach();
            mp.fixedNode.setParent(null);
        }
        if (mp.marker != null) mp.marker.setParent(null);
    }

    private void showUnitDialog() {
        String[] names = getResources().getStringArray(R.array.units);
        new AlertDialog.Builder(this)
                .setTitle(R.string.pick_unit)
                .setItems(names, (dialog, which) -> {
                    unit = Unit.values()[which];
                    updateHudNow();
                })
                .show();
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

    private static float distance(Vector3 a, Vector3 b) {
        float dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clearAll();
        arFragment = null;
    }

    private static Color rgb(int r, int g, int b) {
        return new Color(r / 255f, g / 255f, b / 255f, 1f);
    }
}
