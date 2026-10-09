# 反射调用点（保留 Activity / Service 的无参构造与 onCreate 等生命周期钩子）
-keep class com.wuming.screentrans.** { *; }
-keepattributes SourceFile,LineNumberTable

# org.json 是系统实现，不需要保留规则
