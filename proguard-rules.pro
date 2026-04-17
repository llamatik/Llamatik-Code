# ── IntelliJ Platform ────────────────────────────────────────────────────────
# The platform loads these by fully-qualified name from plugin.xml, so they
# must not be renamed or removed.

-keep class com.llamatik.teammate.ui.toolwindow.LlamatikToolWindowFactory { *; }

-keep class com.llamatik.teammate.settings.LlamatikAppSettings { *; }
-keep class com.llamatik.teammate.settings.DownloadedModelService { *; }
-keep class com.llamatik.teammate.settings.LlamatikSettingsConfigurable { *; }
-keep class com.llamatik.teammate.settings.InferenceSettingsConfigurable { *; }

-keep class com.llamatik.teammate.llamatik.LlamatikBackendService { *; }
-keep class com.llamatik.teammate.licensing.TrialService { *; }
-keep class com.llamatik.teammate.infrastructure.ModelDownloadManager { *; }
-keep class com.llamatik.teammate.infrastructure.ModelRuntimeTracker { *; }
-keep class com.llamatik.teammate.skills.SkillService { *; }
-keep class com.llamatik.teammate.persistence.LlamatikProjectState { *; }
-keep class com.llamatik.teammate.persistence.ChatSessionStore { *; }
-keep class com.llamatik.teammate.agent.AgentOrchestrator { *; }
-keep class com.llamatik.teammate.tools.ToolRegistry { *; }
-keep class com.llamatik.teammate.diff.DiffPreviewService { *; }
-keep class com.llamatik.teammate.agent.BuildVerificationService { *; }
-keep class com.llamatik.teammate.context.ProjectIndexingState { *; }

-keep class com.llamatik.teammate.actions.AskAboutSelectionAction { *; }
-keep class com.llamatik.teammate.actions.ExplainFileAction { *; }
-keep class com.llamatik.teammate.actions.RefactorSelectionAction { *; }
-keep class com.llamatik.teammate.actions.FixSelectedCodeAction { *; }
-keep class com.llamatik.teammate.actions.GenerateTestsAction { *; }
-keep class com.llamatik.teammate.actions.AddDocumentationAction { *; }
-keep class com.llamatik.teammate.actions.EditWithPromptAction { *; }

# ── Kotlin enums ─────────────────────────────────────────────────────────────
# ordinal(), name(), hashCode(), values() are called by the compiler-generated
# when-mapping tables and must be preserved on all enums.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public final int ordinal();
    public final java.lang.String name();
    public int hashCode();
    public boolean equals(java.lang.Object);
    *;
}

# ── Kotlin metadata ──────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes Signature
-keepattributes Exceptions

-keep class kotlin.Metadata { *; }

# Kotlin companion objects and object singletons
-keepclassmembers class **$Companion { *; }
-keepclassmembers class ** {
    public static ** INSTANCE;
    public static ** Companion;
}

# ── Serialisation (PersistentStateComponent) ─────────────────────────────────
# IntelliJ uses XmlSerializer to persist state by field name.
-keepclassmembers class * implements com.intellij.openapi.components.PersistentStateComponent {
    public *;
    <fields>;
}

# ── Swing / UI components ────────────────────────────────────────────────────
# Preserve all members of anonymous JPanel/JButton subclasses used for custom painting.
-keepclassmembers class * extends javax.swing.JPanel { *; }
-keepclassmembers class * extends javax.swing.JComponent { *; }
-keepclassmembers class * extends javax.swing.JButton { *; }
-keepclassmembers class * extends com.intellij.openapi.ui.DialogWrapper { *; }
-keepclassmembers class * extends com.intellij.openapi.ui.DialogBuilder { *; }

# ── General safety ───────────────────────────────────────────────────────────
-dontoptimize
-dontpreverify

# Suppress all warnings — cross-class references within our own code are safe,
# and IntelliJ platform classes are provided at runtime but not in libraryjars.
-ignorewarnings

-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn org.jetbrains.**
-dontwarn com.intellij.**
-dontwarn javax.**
-dontwarn java.**
-dontwarn com.sun.**
-dontwarn com.llamatik.**

# Keep source file and line numbers for readable stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
