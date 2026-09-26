package com.bomo.oplusmedialimited;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 模块状态页：仅用于 LSPosed Manager 列出本模块（需 launcher activity），
 * 并显示版本与生效说明。
 *
 * @author bomo
 */
public class StatusActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setTextColor(Color.parseColor("#222222"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        tv.setPadding(pad, pad, pad, pad);
        tv.setText("ColorOS 有限访问全开 v0\n\n"
                + "作用域：com.android.permissioncontroller\n"
                + "功能：照片/视频权限设置页对任意 targetSdk 应用显示「允许有限访问」。\n"
                + "生效条件：LSPosed 中启用模块并勾选上述作用域，force-stop 权限控制器后重开设置页。");
        ScrollView sc = new ScrollView(this);
        sc.addView(tv);
        setContentView(sc);
    }
}
