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
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Pose;
import com.google.ar.core.TrackingState;
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

/**
 * AR 测距 v2 —— 针对"难用"的重做版:
 *
 *  1. 点击更灵:不再限定"必须是已识别的平面内部",
 *     命中策略 平面(多边形内)→ 平面(扩展) → 特征点(PointCloud) 三级回退,几乎点哪都有响应;
 *     完全落空时给出明确提示。
 *  2. 尺条实时贴点:每帧用锚点最新位置重算尺条的两端、长度缩放和标签,
 *     彻底缓解"画完之后线飘了"的观感(锚点自身会随跟踪更新)。
 *  3. 明确的状态提示:
 *       - ~定位中~:像拍照一样慢速移动手机
 *       - 已落点 N:还需点 1 个点 / 直线距离 / 折线总长+首尾直线
 *  4. 界面:三个大按钮(撤销上一个点 / 单位 / 清空),HUD 大字显示。
 */
public class ArMeasureActivity extends AppCompatActivity {

    private enum Unit { CENTIMETER, METER, FOOT, INCH }

    private MeasureArFragment arFragment;
    private TextView hudText;
    private TextView tipText;
    private Unit unit = Unit.CENTIMETER;

    /** 依次落下的测量点 */
    private final List<AnchorNode> anchorNodes = new ArrayList<>();
    /** 每个点的小球标记 */
    private final List<Node> markers = new ArrayList<>();
    /** 相邻两点的连线段(每帧动态贴点) */
    private final List<MeasureSegment> segments = new ArrayList<>();

    /** 一段尺条 + 居中的距离标签 */
    private static final class MeasureSegment {
        Node lineNode;
        Node labelNode;
        TextView labelView;
        AnchorNode start;
        AnchorNode end;
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ar_measure);
        hudText = findViewById(R.id.txt_hud);
        tipText = findViewById(R.id.txt_tip);
        Button btnUndo = findViewById(R.id.btn_undo);
        Button btnClear = findViewById(R.id.btn_clear);
        Button btnUnit = findViewById(R.id.btn_unit);
        btnUndo.setOnClickListener(v -> undoLast());
        btnClear.setOnClickListener(v -> clearAll());
        btnUnit.setOnClickListener(v -> showUnitDialog());

        FragmentManager fm = getSupportFragmentManager();
        arFragment = (MeasureArFragment) fm.findFragmentById(R.id.ar_fragment);
        if (arFragment == null) {
            finish();
            return;
        }

        // 自接管点击:三级命中回退,响应度远高于默认的"平面内点击"
        arFragment.getArSceneView().getScene().setOnTouchListener(((hitTestResult, motionEvent) -> {
            if (motionEvent.getAction() != MotionEvent.ACTION_UP) {
                return false;
            }
            handleTap(motionEvent);
            return true;
        }));

        // 每帧重算:尺条跟随两个锚点的最新位置,标签跟随中点并朝向相机
        arFragment.getArSceneView().getScene().addOnUpdateListener(this::onSceneUpdate);
    }

    // ---------------- 命中与落点 ----------------

    private void handleTap(MotionEvent motionEvent) {
        com.google.ar.core.Camera camera =
                arFragment.getArSceneView().getArFrame() != null
                        ? arFragment.getArSceneView().getArFrame().getCamera() : null;
        if (camera == null || camera.getTrackingState() != TrackingState.TRACKING) {
            showTip(getString(R.string.tip_locating));
            return;
        }
        Frame frame = arFragment.getArSceneView().getArFrame();
        List<HitResult> hits = frame.hitTest(motionEvent);
        HitResult chosen = chooseBestHit(hits);
        if (chosen == null) {
            showTip(getString(R.string.tip_no_surface));
            return;
        }
        placePoint(chosen);
    }

    /** 平面(多边形内) > 平面(任意,扩展命中) > 特征点 */
    private static HitResult chooseBestHit(List<HitResult> hits) {
        if (hits == null) {
            return null;
        }
        HitResult planeInPoly = null, planeAny = null, pointHit = null;
        for (HitResult h : hits) {
            if (h == null || h.getTrackable() == null
                    || h.getTrackable().getTrackingState() != TrackingState.TRACKING) {
                continue;
            }
            com.google.ar.core.Trackable t = h.getTrackable();
            if (t instanceof Plane) {
                if (planeInPoly == null && ((Plane) t).isPoseInPolygon(h.getHitPose())) {
                    planeInPoly = h;
                } else if (planeAny == null) {
                    planeAny = h;
                }
            } else if (t instanceof Point && pointHit == null) {
                pointHit = h;
            }
        }
        if (planeInPoly != null) return planeInPoly;
        if (planeAny != null) return planeAny;
        return pointHit;
    }

    private void placePoint(HitResult chosen) {
        try {
            Anchor anchor = chosen.createAnchor();
            Pose pose = chosen.getHitPose();

            AnchorNode newAnchorNode = new AnchorNode(anchor);
            newAnchorNode.setParent(arFragment.getArSceneView().getScene());
            Vector3 pos = new Vector3(pose.tx(), pose.ty(), pose.tz());
            newAnchorNode.setWorldPosition(pos);

            // 小球"钉子"
            MaterialFactory.makeOpaqueWithColor(this, rgb(0xFF, 0x3B, 0x30))
                    .thenAccept(material -> {
                        ModelRenderable sphere = ShapeFactory.makeSphere(0.010f, pos, material);
                        Node marker = new Node();
                        marker.setParent(newAnchorNode);
                        marker.setLocalPosition(Vector3.zero());
                        marker.setRenderable(sphere);
                        markers.add(marker);
                    });

            int n = anchorNodes.size();
            if (n > 0) {
                addSegment(anchorNodes.get(n - 1), newAnchorNode);
            }
            anchorNodes.add(newAnchorNode);
            updateHud();
        } catch (Exception ignore) {
            // 锚点池满或会话异常,忽略本次点击
        }
    }

    /** 两点之间一段尺条:单位长度 1cm×1cm×1 的立方体,每帧按两点真实位置缩放/摆放 */
    private void addSegment(AnchorNode startNode, AnchorNode endNode) {
        MaterialFactory.makeOpaqueWithColor(this, rgb(0xFF, 0xC1, 0x07))
                .thenAccept(material -> {
                    ModelRenderable lineR = ShapeFactory.makeCube(
                            new Vector3(0.008f, 0.008f, 1.0f), Vector3.zero(), material);
                    lineR.setShadowReceiver(false);
                    lineR.setShadowCaster(false);

                    MaterialFactory.makeOpaqueWithColor(this, rgb(0x21, 0x21, 0x21))
                            .thenAccept(unused -> {
                                final MeasureSegment seg = new MeasureSegment();
                                seg.start = startNode;
                                seg.end = endNode;

                                Node lineNode = new Node();
                                lineNode.setParent(arFragment.getArSceneView().getScene());
                                lineNode.setRenderable(lineR);
                                seg.lineNode = lineNode;

                                // 距离标签
                                ViewRenderable.builder()
                                        .setView(this, R.layout.view_distance_label)
                                        .build()
                                        .thenAccept(labelR -> {
                                            Node labelNode = new Node();
                                            labelNode.setParent(arFragment.getArSceneView().getScene());
                                            labelNode.setRenderable(labelR);
                                            seg.labelNode = labelNode;
                                            seg.labelView = (TextView) labelR.getView();
                                        });
                                segments.add(seg);
                            });
                });
    }

    // ---------------- 每帧动态贴点(防漂移核心) ----------------

    private void onSceneUpdate(com.google.ar.sceneform.FrameTime frameTime) {
        updateHud();
        if (segments.isEmpty()) {
            return;
        }
        Vector3 camPos = arFragment.getArSceneView().getScene().getCamera().getWorldPosition();
        for (MeasureSegment seg : segments) {
            if (seg.lineNode == null || seg.start == null || seg.end == null) {
                continue;
            }
            Vector3 p0 = seg.start.getWorldPosition();
            Vector3 p1 = seg.end.getWorldPosition();
            Vector3 diff = Vector3.subtract(p1, p0);
            float len = diff.length();
            if (len < 0.005f) {
                seg.lineNode.setEnabled(false);
                if (seg.labelNode != null) seg.labelNode.setEnabled(false);
                continue;
            }
            Vector3 center = Vector3.add(p0, p1).scaled(0.5f);
            Vector3 pulse = diff.normalized();

            // 尺条:中点居中,长轴 = len
            seg.lineNode.setEnabled(true);
            seg.lineNode.setWorldPosition(center);
            seg.lineNode.setLocalRotation(Quaternion.rotationBetweenVectors(
                    new Vector3(0f, 0f, 1f), pulse));
            seg.lineNode.setWorldScale(new Vector3(1f, 1f, len));

            // 标签:悬浮在中点上方,始终朝向镜头位置
            if (seg.labelNode != null) {
                seg.labelNode.setEnabled(true);
                Vector3 labelPos = new Vector3(center.x, center.y + 0.06f, center.z);
                seg.labelNode.setWorldPosition(labelPos);
                Vector3 faceDir = Vector3.subtract(camPos, labelPos).normalized();
                seg.labelNode.setLookDirection(faceDir, Vector3.up());
                if (seg.labelView != null) {
                    seg.labelView.setText(formatDistance(len));
                }
            }
        }
    }

    // ---------------- HUD / 撤销 / 单位 / 清空 ----------------

    private void updateHud() {
        if (hudText == null) {
            return;
        }
        com.google.ar.core.Camera camera =
                arFragment != null && arFragment.getArSceneView().getArFrame() != null
                        ? arFragment.getArSceneView().getArFrame().getCamera() : null;
        if (camera != null && camera.getTrackingState() != TrackingState.TRACKING) {
            hudText.setText(R.string.hud_locating);
            return;
        }
        int n = anchorNodes.size();
        if (n < 2) {
            hudText.setText(getString(R.string.hud_hint, n));
            return;
        }
        float total = 0f;
        for (MeasureSegment seg : segments) {
            if (seg.start != null && seg.end != null) {
                total += Vector3.subtract(seg.start.getWorldPosition(),
                        seg.end.getWorldPosition()).length();
            }
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

    private void showTip(CharSequence text) {
        if (tipText != null) {
            tipText.setText(text);
            tipText.setVisibility(View.VISIBLE);
            tipText.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (tipText != null) tipText.setVisibility(View.GONE);
                }
            }, 1500);
        }
    }

    private void undoLast() {
        int n = anchorNodes.size();
        if (n == 0) {
            return;
        }
        AnchorNode last = anchorNodes.remove(n - 1);
        Anchor a = last.getAnchor();
        if (a != null) a.detach();
        for (Node m : markers) {
            if (m.getParent() == last) {
                m.setParent(null);
            }
        }
        markers.removeIf(m -> m.getParent() == null);
        if (!segments.isEmpty()) {
            MeasureSegment seg = segments.remove(segments.size() - 1);
            if (seg.lineNode != null) seg.lineNode.setParent(null);
            if (seg.labelNode != null) seg.labelNode.setParent(null);
        }
        updateHud();
    }

    private void clearAll() {
        for (AnchorNode an : anchorNodes) {
            Anchor a = an.getAnchor();
            if (a != null) a.detach();
            an.setParent(null);
        }
        for (MeasureSegment seg : segments) {
            if (seg.lineNode != null) seg.lineNode.setParent(null);
            if (seg.labelNode != null) seg.labelNode.setParent(null);
        }
        anchorNodes.clear();
        markers.clear();
        segments.clear();
        updateHud();
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

    private String formatDistance(float meters) {
        switch (unit) {
            case METER:      return String.format("%.2f m", meters);
            case CENTIMETER: return String.format("%.1f cm", meters * 100f);
            case FOOT:       return String.format("%.2f ft", meters * 3.28084f);
            case INCH:       return String.format("%.1f in", meters * 39.3701f);
            default:         return String.format("%.2f m", meters);
        }
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
