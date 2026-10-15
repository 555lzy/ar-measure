package com.qh.armeasure;

import com.google.ar.core.Config;
import com.google.ar.core.Session;
import com.google.ar.sceneform.ux.ArFragment;

/**
 * 自定义 ArFragment:
 *  - 水平 + 垂直平面都检测(地面/桌面/墙面都可测)
 *  - 打开聚焦模式(AUTO),提高测距精度
 *
 * 基类钩子 getSessionConfiguration 在 Session 创建时被调用(见 BaseArFragment)。
 */
public class MeasureArFragment extends ArFragment {

    @Override
    protected Config getSessionConfiguration(Session session) {
        Config config = new Config(session);
        config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
        config.setFocusMode(Config.FocusMode.AUTO);
        return config;
    }
}
