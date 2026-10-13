# 家庭打印助手 · Release 混淆规则
# 2026-10-08：开启 minifyEnabled，反编译后不再直接暴露服务器地址与内部逻辑

# Activity 由 Manifest 声明，aapt 规则会自动保留；显式保留更稳妥
-keep public class * extends android.app.Activity

# FileProvider 通过字符串 authority 反射查找，必须保留
-keep class androidx.core.content.FileProvider { *; }

# 打印任务模型类，字段被 Gson 之外的手动 JSON 组装使用，保留字段名
-keep class com.family.print.PageGroup { *; }

# Release 去掉调试日志（APP开发完整规范：Release 不得输出调试日志）
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
