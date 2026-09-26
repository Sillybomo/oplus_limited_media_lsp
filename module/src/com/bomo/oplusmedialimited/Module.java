package com.bomo.oplusmedialimited;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * ColorOS 17「照片和视频-允许有限访问」全量放开模块 v16。
 *
 * 四层结构：
 * 1) UI 层（permissioncontroller / securitypermission）：钩 d0()/f0.U()
 *    强制照片组设置页三态，复刻 vivo video_and_image_extension 开关；
 * 2) 弹窗层（permissioncontroller m4.g）：钩 k()/m() 使 legacy 应用发图走
 *    SELECT_PHOTOS 三态照片选择窗；钩 c() 把被拆分出的 STORAGE/AURAL 两态窗
 *    静默跳过（返回 Prompt.B），只保留 VISUAL 三态窗，对齐 vivo/QQ 单窗体验；
 * 3) 标记协议（Settings.Global "bomo_ml_<pkg>"）：
 *    ColorOS 会给所有历史授权老应用自动补发 VISUAL 权限（grandfather），
 *    权限位无法区分"真有限访问"与"正常全量"，因此以"授予 VISUAL + 撤销
 *    IMAGES/VIDEO/EXTERNAL"的组合动作作为真·有限访问的判据，命中即写标记；
 *    撤销 VISUAL 即清标记。PC 进程写，MediaProvider 进程读（带 2s 缓存）。
 * 4) 执行层（MediaProvider 进程内钩子）：
 *    - checkCallingPermissionUserSelected：标记应用在 VISUAL 授予时强制 true
 *      （绕开 PermissionUtils 对 targetSdk<=32 的短路）；
 *    - AccessChecker.hasAccessToCollection：标记应用的图片/视频集合强制 false，
 *      使查询落入 AOSP media_grants 过滤分支。
 *
 * 诊断依据与演进记录见 notes/01-诊断笔记.md。
 *
 * @author bomo
 */
public class Module implements IXposedHookLoadPackage {

    private static final String TAG = "OplusMediaLimited";

    private static final String PKG_SECPERM = "com.oplus.securitypermission";
    private static final String PKG_PC = "com.android.permissioncontroller";
    private static final String PKG_MP = "com.android.providers.media.module";

    private static final String VISUAL_GROUP = "android.permission-group.READ_MEDIA_VISUAL";
    /** 存储超级组（EXTERNAL 家族）；legacy 发图被拆出的两态存储窗即此组 */
    private static final String STORAGE_GROUP = "android.permission-group.STORAGE";
    /** 音频媒体组；legacy 请求 READ_EXTERNAL_STORAGE 被 split 出的音频两态窗 */
    private static final String AURAL_GROUP = "android.permission-group.READ_MEDIA_AURAL";
    private static final String VISUAL_PERM = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED";
    private static final String P_IMAGES = "android.permission.READ_MEDIA_IMAGES";
    private static final String P_VIDEO = "android.permission.READ_MEDIA_VIDEO";
    private static final String P_READ_EXT = "android.permission.READ_EXTERNAL_STORAGE";

    /** 有限访问持久标记 key 前缀（Settings.Global，PC 写、MediaProvider 读） */
    private static final String MARKER_PREFIX = "bomo_ml_";

    /** MediaProvider LocalCallingIdentity 权限位（反编译确认） */
    private static final int BIT_VIDEO = 131072;
    private static final int BIT_IMAGES = 262144;
    private static final int BIT_VISUAL = 134217728;

    /** 标记读缓存（pkg -> [value, expireAt]），MediaProvider 侧高频读降压 */
    private static final Map<String, Object[]> sMarkerCache =
            new ConcurrentHashMap<String, Object[]>();

    private static volatile boolean sHooked;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (PKG_SECPERM.equals(lpp.packageName)) {
            hookMethodShape(lpp,
                    "com.oplusos.securitypermission.permission.utils.f0",
                    "U", 2, true);
        } else if (PKG_PC.equals(lpp.packageName)) {
            hookMethodShape(lpp,
                    "com.android.permissioncontroller.permission.ui.model.AppPermissionViewModel",
                    "d0", 1, false);
            // v30：放开「运行时授权弹窗」的 targetSdk>=33 门槛。legacy 应用
            // （美团 targetSdk=30）发图时 g.c() 因 k()=false 落入
            // STORAGE_SUPERGROUP 两态存储窗；强制 g 的 k()/m() 对
            // READ_MEDIA_VISUAL 组返回 true，使 c() 改走 SELECT_PHOTOS /
            // NO_UI_PHOTO_PICKER 三态照片选择弹窗（对齐 QQ）。用户在弹窗选
            // 「选择照片」→ 系统仅授予 VISUAL、IMAGES/VIDEO 保持撤销 →
            // 设置页三态不回弹，过滤仍由 MediaProvider hook 保证。类/方法名
            // 为混淆名（当前 ROM：m4.g / k / m），随 ROM 版本可能变化，
            // 找不到即打日志、不影响其他 hook。
            hookMethodShape(lpp, "m4.g", "k", 1, false);
            hookMethodShape(lpp, "m4.g", "m", 1, false);
            hookPromptDiag(lpp);
            hookMarkerWriter();
        } else if (PKG_MP.equals(lpp.packageName)) {
            hookMediaProvider(lpp);
        }
    }

    /* ======================= 通用：按形状钩方法 ======================= */

    /**
     * 按"类名+方法名+参数个数+返回 boolean"定位并钩住门槛函数，
     * 命中照片组入参时强制返回 true。
     *
     * @param lpp        加载回调
     * @param className  目标类全名
     * @param methodName 混淆方法名（随 ROM 版本可能变化，找不到即打日志）
     * @param argc       参数个数（U=2 含 Context；d0=1 仅组对象）
     * @param groupAt1   true 时组对象在 args[1]（SecurityPermission f0.U）
     */
    private void hookMethodShape(XC_LoadPackage.LoadPackageParam lpp, String className,
                                 String methodName, int argc, final boolean groupAt1) {
        try {
            Class<?> cls = lpp.classLoader.loadClass(className);
            java.lang.reflect.Method gate = null;
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (methodName.equals(m.getName())
                        && m.getParameterTypes().length == argc
                        && m.getReturnType() == boolean.class) {
                    gate = m;
                    break;
                }
            }
            if (gate == null) {
                XposedBridge.log(TAG + ": gate " + className + "." + methodName + " not found");
                return;
            }
            gate.setAccessible(true);
            XposedBridge.hookMethod(gate, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(param.getResult())) {
                        return;
                    }
                    Object group = param.args[groupAt1 ? 1 : 0];
                    if (isVisualGroup(group)) {
                        param.setResult(true);
                        if (!sHooked) {
                            sHooked = true;
                            XposedBridge.log(TAG + ": forced partial-access ON (" + className + ")");
                        }
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked " + className + "." + methodName);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": init failed for " + className + ": " + t);
        }
    }

    /**
     * v31：钩 m4.g.c()（运行时弹窗类型决策），拦截 legacy 应用发图时被拆分
     * 出来的「存储超级组两态窗」和「音频组两态窗」，使其静默跳过，只保留
     * READ_MEDIA_VISUAL 三态照片选择窗（对齐 vivo/QQ 单窗体验）。
     *
     * 背景：夸克/美团（targetSdk&lt;33）一次请求全套存储权限，permissioncontroller
     * 把它拆成 STORAGE / READ_MEDIA_AURAL / READ_MEDIA_VISUAL 三组逐组调 c()
     * 生成 Prompt 弹窗。v30 已让 VISUAL 组走 SELECT_PHOTOS 三态窗，但 STORAGE/
     * AURAL 组仍返回 STORAGE_SUPERGROUP 两态窗且排在三态窗前面，用户先看到两态窗。
     *
     * 解法：对 STORAGE/AURAL 组的 c() 返回值改写为 Prompt.B(NO_UI_REJECT_THIS_GROUP)。
     * 依据 GrantPermissionsViewModel 弹窗列表生成逻辑：c()==Prompt.B 时该组仅上报
     * 不加入弹窗列表，故这两组不再弹窗，列表只剩 VISUAL 三态窗。存储/音频的实际
     * 读取由 MediaProvider 执行层 hook 按有限访问过滤，不依赖这两组授权。
     *
     * 防误伤：仅当权限组所属应用 targetSdk&lt;33（legacy）时拦截。targetSdk&gt;=33 的
     * 应用请求音频/存储走正常两态窗，不受影响。类/方法名为混淆名（m4.g / c），
     * 随 ROM 版本可能变化，找不到即打日志、不影响其他 hook。
     *
     * @param lpp 加载回调
     */
    private void hookPromptDiag(XC_LoadPackage.LoadPackageParam lpp) {
        final Object rejectThisGroup;
        Object tmp = null;
        try {
            Class<?> promptCls = lpp.classLoader.loadClass(
                    "com.android.permissioncontroller.permission.ui.model.Prompt");
            tmp = promptCls.getField("B").get(null);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": load Prompt.B failed: " + t);
        }
        rejectThisGroup = tmp;
        try {
            Class<?> cls = lpp.classLoader.loadClass("m4.g");
            for (final java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (!"c".equals(m.getName()) || m.getParameterTypes().length != 3) {
                    continue;
                }
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object group = param.args[0];
                            String gname = null;
                            try {
                                gname = (String) group.getClass()
                                        .getMethod("n").invoke(group);
                            } catch (Throwable ignored) {
                            }
                            if (rejectThisGroup != null
                                    && (STORAGE_GROUP.equals(gname)
                                        || AURAL_GROUP.equals(gname))
                                    && isLegacyTarget(group)) {
                                param.setResult(rejectThisGroup);
                                XposedBridge.log(TAG + ": [prompt] skip 2-state "
                                        + "window group=" + gname);
                                return;
                            }
                            XposedBridge.log(TAG + ": [prompt] group=" + gname
                                    + " -> " + param.getResult());
                        } catch (Throwable ignored) {
                        }
                    }
                });
                XposedBridge.log(TAG + ": hooked m4.g.c (redirect)");
                return;
            }
            XposedBridge.log(TAG + ": m4.g.c not found");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookPromptDiag failed: " + t);
        }
    }

    /**
     * 判断权限组所属应用是否为 legacy（targetSdk&lt;33）。混淆组对象 group.k()
     * 返回轻量包信息对象，其 o() 返回 targetSdkVersion（见 m4.g.c 内
     * {@code group.k().o() < 29} 用法）。取不到时保守返回 false（不拦）。
     *
     * @param group 权限组入参
     * @return true=targetSdk&lt;33 的 legacy 应用
     */
    private static boolean isLegacyTarget(Object group) {
        try {
            Object pkgInfo = group.getClass().getMethod("k").invoke(group);
            Object sdk = pkgInfo.getClass().getMethod("o").invoke(pkgInfo);
            if (sdk instanceof Integer) {
                return (Integer) sdk < 33;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 识别门槛函数的权限组入参（混淆类 da.c / z3.b）：m()/n() 取组名，
     * o() 取权限 Map（含 VISUAL_USER_SELECTED 即照片组）。任一命中即认定。
     *
     * @param group 门槛函数的组对象入参，可为 null
     * @return true=照片和视频权限组
     */
    private static boolean isVisualGroup(Object group) {
        if (group == null) {
            return false;
        }
        for (String nameGetter : new String[]{"m", "n"}) {
            try {
                Object name = group.getClass().getMethod(nameGetter).invoke(group);
                if (VISUAL_GROUP.equals(name)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Object map = group.getClass().getMethod("o").invoke(group);
            if (map instanceof Map) {
                return ((Map<?, ?>) map).containsKey(VISUAL_PERM);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /* ======================= 标记协议 ======================= */

    /**
     * 取应用 Context（currentApplication，null 时退 systemContext）。
     *
     * @return Context，失败 null
     */
    private static android.content.Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app != null) {
                return (android.content.Context) app;
            }
            if (thread != null) {
                return (android.content.Context) at.getMethod("getSystemContext")
                        .invoke(thread);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 读有限访问标记（带 2s 缓存，供 MediaProvider 高频路径）。
     *
     * @param pkg 包名
     * @return true=该包处于用户选择的有限访问态
     */
    private static boolean marker(String pkg) {
        if (pkg == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Object[] hit = sMarkerCache.get(pkg);
        if (hit != null && ((Long) hit[1]).longValue() > now) {
            return ((Boolean) hit[0]).booleanValue();
        }
        boolean v = false;
        try {
            android.content.Context ctx = appContext();
            if (ctx != null) {
                v = android.provider.Settings.Global.getInt(
                        ctx.getContentResolver(), MARKER_PREFIX + pkg, 0) == 1;
            }
        } catch (Throwable ignored) {
        }
        sMarkerCache.put(pkg, new Object[]{Boolean.valueOf(v), Long.valueOf(now + 2000)});
        return v;
    }

    /**
     * 写有限访问标记（仅 PC 进程调用；PC 持 WRITE_SECURE_SETTINGS）。
     *
     * @param pkg   包名
     * @param value true=置标记
     */
    private static void setMarker(String pkg, boolean value) {
        try {
            android.content.Context ctx = appContext();
            if (ctx == null) {
                return;
            }
            int cur = android.provider.Settings.Global.getInt(
                    ctx.getContentResolver(), MARKER_PREFIX + pkg, 0);
            int want = value ? 1 : 0;
            if (cur != want) {
                android.provider.Settings.Global.putInt(
                        ctx.getContentResolver(), MARKER_PREFIX + pkg, want);
                XposedBridge.log(TAG + ": marker " + pkg + " -> " + want);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setMarker err: " + t);
        }
    }

    /**
     * 标记判据（核心防误伤逻辑）：ColorOS grandfather 只补发 VISUAL 不动媒体权限，
     * 用户真·有限访问必然伴随"撤 IMAGES/VIDEO/EXTERNAL"或"VISUAL 授予时媒体已缺"。
     *
     * @param perm   本次变更权限
     * @param pkg    包名
     * @param mask   flag 掩码
     * @param fgs    flag 值
     * @param pm     PackageManager（查当前授予态）
     */
    private static void onFlagWrite(String perm, String pkg, int mask, int fgs,
                                    android.content.pm.PackageManager pm) {
        try {
            if (pkg == null || perm == null || (mask & 4) == 0) {
                return; // 未触及 GRANTED 位
            }
            boolean grant = (fgs & 4) != 0;
            if (VISUAL_PERM.equals(perm)) {
                if (!grant) {
                    setMarker(pkg, false);
                } else if (!granted(pm, P_IMAGES) || !granted(pm, P_VIDEO)) {
                    setMarker(pkg, true);
                }
            } else if (!grant && (P_IMAGES.equals(perm) || P_VIDEO.equals(perm)
                    || P_READ_EXT.equals(perm))) {
                if (granted(pm, VISUAL_PERM)) {
                    setMarker(pkg, true);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * @param pm   PackageManager
     * @param perm 权限名
     * @return true=该权限当前授予（checkPermission(perm,pkg) 语义为授予态）
     */
    private static boolean granted(android.content.pm.PackageManager pm, String perm) {
        try {
            String pkg = sFlagPkg;
            return pm.checkPermission(perm, pkg)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前 flag 事件所属包名（onFlagWrite 期间有效，granted() 借用） */
    static String sFlagPkg;

    /**
     * PC 进程钩框架权限写入 API，维护标记：
     * - PackageManager.updatePermissionFlags(String,String,int,int,UserHandle)
     * - PermissionManager.setPermissionFgs(...)（按参数形状提取 perm/pkg/mask/fgs）
     */
    private void hookMarkerWriter() {
        try {
            Class<?> pmCls = Class.forName("android.app.ApplicationPackageManager");
            for (java.lang.reflect.Method m : pmCls.getDeclaredMethods()) {
                if ("updatePermissionFlags".equals(m.getName())
                        && m.getParameterTypes().length == 5) {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sFlagPkg = (String) param.args[1];
                            onFlagWrite((String) param.args[0], (String) param.args[1],
                                    ((Integer) param.args[2]).intValue(),
                                    ((Integer) param.args[3]).intValue(),
                                    (android.content.pm.PackageManager) param.thisObject);
                        }
                    });
                    XposedBridge.log(TAG + ": hooked ApplicationPackageManager.updatePermissionFlags");
                }
            }
            Class<?> pmsCls = Class.forName("android.permission.PermissionManager");
            int n = 0;
            for (java.lang.reflect.Method m : pmsCls.getDeclaredMethods()) {
                if (!m.getName().equals("setPermissionFgs")
                        || m.getReturnType() != void.class) {
                    continue;
                }
                final Class<?>[] pts = m.getParameterTypes();
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String perm = null, pkg = null;
                        int mask = 0, fgs = 0;
                        int ii = 0;
                        for (int i = 0; i < pts.length; i++) {
                            Object a = param.args[i];
                            if (a instanceof String) {
                                if (perm == null) {
                                    perm = (String) a;
                                } else if (pkg == null) {
                                    pkg = (String) a;
                                }
                            } else if (a instanceof Integer) {
                                if (ii == 0) {
                                    mask = ((Integer) a).intValue();
                                } else {
                                    fgs = ((Integer) a).intValue();
                                }
                                ii++;
                            }
                        }
                        android.content.Context ctx = appContext();
                        if (ctx != null) {
                            sFlagPkg = pkg;
                            onFlagWrite(perm, pkg, mask, fgs, ctx.getPackageManager());
                        }
                    }
                });
                n++;
            }
            XposedBridge.log(TAG + ": hooked PermissionManager.setPermissionFgs x" + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": marker writer init failed: " + t);
        }
    }

    /* ======================= MediaProvider 执行层 ======================= */

    /**
     * MediaProvider 进程钩子：
     * 1) LocalCallingIdentity.checkCallingPermissionUserSelected：标记应用且
     *    VISUAL 授予时强制 true（绕开 PermissionUtils 对 targetSdk<=32 的短路）；
     * 2) AccessChecker.hasAccessToCollection：对标记应用的图片/视频集合强制 false，
     *    使查询落入 media_grants 过滤分支。
     *
     * @param lpp MediaProvider 加载回调
     */
    private void hookMediaProvider(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> lci = lpp.classLoader.loadClass(
                    "com.android.providers.media.LocalCallingIdentity");
            java.lang.reflect.Method sel = null;
            for (java.lang.reflect.Method m : lci.getDeclaredMethods()) {
                if ("checkCallingPermissionUserSelected".equals(m.getName())
                        && m.getParameterTypes().length == 1
                        && m.getReturnType() == boolean.class) {
                    sel = m;
                    break;
                }
            }
            if (sel == null) {
                XposedBridge.log(TAG + ": MP checkCallingPermissionUserSelected not found");
                return;
            }
            sel.setAccessible(true);
            XposedBridge.hookMethod(sel, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(param.getResult())) {
                        return;
                    }
                    try {
                        String pkg = (String) param.thisObject.getClass()
                                .getMethod("getPackageName").invoke(param.thisObject);
                        if (marker(pkg) && visualGranted(pkg)) {
                            param.setResult(true);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": MP sel hook err: " + t);
                    }
                }
            });
            Class<?> ac = lpp.classLoader.loadClass(
                    "com.android.providers.media.AccessChecker");
            java.lang.reflect.Method acc = null;
            for (java.lang.reflect.Method m : ac.getDeclaredMethods()) {
                if ("hasAccessToCollection".equals(m.getName())
                        && m.getParameterTypes().length == 3
                        && m.getReturnType() == boolean.class) {
                    acc = m;
                    break;
                }
            }
            if (acc == null) {
                XposedBridge.log(TAG + ": MP hasAccessToCollection not found");
                return;
            }
            acc.setAccessible(true);
            final java.lang.reflect.Method selFinal = sel;
            XposedBridge.hookMethod(acc, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!Boolean.TRUE.equals(param.getResult())) {
                        return;
                    }
                    int match = ((Integer) param.args[1]).intValue();
                    if (!isImageOrVideoMatch(match)) {
                        return;
                    }
                    try {
                        boolean fg = ((Boolean) param.args[2]).booleanValue();
                        if (Boolean.TRUE.equals(selFinal.invoke(param.args[0], fg))) {
                            param.setResult(false);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": MP acc hook err: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": MediaProvider hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": MP init failed: " + t);
        }
    }

    /**
     * 直查 PMS 的 VISUAL 权限位（绕开 PermissionUtils 的 targetSdk 短路）。
     *
     * @param pkg 目标应用包名
     * @return true=READ_MEDIA_VISUAL_USER_SELECTED 已授予
     */
    private static boolean visualGranted(String pkg) {
        if (pkg == null) {
            return false;
        }
        try {
            android.content.Context ctx = appContext();
            return ctx != null && ctx.getPackageManager().checkPermission(VISUAL_PERM, pkg)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 判断 MediaProvider 集合 match 码是否图片/视频类
     * （对应 AccessChecker 中 1/2/4/5/200/201/203/204）。
     *
     * @param match matchUri 结果码
     * @return true=图片/视频集合
     */
    private static boolean isImageOrVideoMatch(int match) {
        switch (match) {
            case 1: case 2: case 4: case 5:
            case 200: case 201: case 203: case 204:
                return true;
            default:
                return false;
        }
    }

    /**
     * @author bomo 占位防 lint：StatusActivity 依赖 xposedscope 展示
     */
}
