# ── IntelliJ Platform ────────────────────────────────────────────────────────
# The platform loads these by fully-qualified name from plugin.xml, so they
# must not be renamed or removed.
#
# ProGuard's { *; } wildcard covers fields and methods but NOT constructors
# (<init>). IntelliJ instantiates plugin entry points via reflection by trying
# specific constructor signatures, so we must preserve them explicitly.
-keepclassmembers class com.llamatik.** {
    <init>();
    <init>(...);
}

-keep class com.llamatik.teammate.ui.toolwindow.LlamatikToolWindowFactory { *; }

# Theme and string constants — HTML label strings reference these by direct call;
# obfuscating them causes colours and labels to disappear at runtime.
-keep class com.llamatik.teammate.ui.LlamatikTheme { *; }
-keep class com.llamatik.teammate.ui.LlamatikTheme$* { *; }
-keep class com.llamatik.teammate.ui.Strings { *; }
-keep class com.llamatik.teammate.ui.Strings$* { *; }

-keep class com.llamatik.teammate.settings.LlamatikAppSettings { *; }
-keep class com.llamatik.teammate.settings.DownloadedModelService { *; }
-keep class com.llamatik.teammate.settings.LlamatikSettingsConfigurable { *; }
-keep class com.llamatik.teammate.settings.LlamatikSettingsConfigurable$* { *; }
-keep class com.llamatik.teammate.settings.InferenceSettingsConfigurable { *; }
-keep class com.llamatik.teammate.settings.InferenceSettingsConfigurable$* { *; }
-keep class com.llamatik.teammate.settings.InferenceSettingsPanel { *; }

-keep class com.llamatik.teammate.llamatik.LlamatikBackendService { *; }
-keep class com.llamatik.teammate.llamatik.MultimodalBackendService { *; }
-keep class com.llamatik.teammate.llamatik.PluginPromptRenderer { *; }
-keep class com.llamatik.teammate.licensing.TrialService { *; }
-keep class com.llamatik.teammate.licensing.LicenseChecker { *; }
-keep class com.llamatik.teammate.infrastructure.ModelDownloadManager { *; }
-keep class com.llamatik.teammate.infrastructure.ModelRuntimeTracker { *; }
-keep class com.llamatik.teammate.infrastructure.HardwareDetector { *; }
-keep class com.llamatik.teammate.skills.SkillService { *; }
-keep class com.llamatik.teammate.persistence.LlamatikProjectState { *; }
-keep class com.llamatik.teammate.persistence.ChatSessionStore { *; }
-keep class com.llamatik.teammate.agent.AgentOrchestrator { *; }
-keep class com.llamatik.teammate.tools.ToolRegistry { *; }
-keep class com.llamatik.teammate.diff.DiffPreviewService { *; }
-keep class com.llamatik.teammate.agent.BuildVerificationService { *; }
-keep class com.llamatik.teammate.context.ProjectIndexingState { *; }
-keep class com.llamatik.teammate.ui.onboarding.OnboardingDialog { *; }
-keep class com.llamatik.teammate.ui.onboarding.OnboardingDialog$* { *; }
-keep class com.llamatik.teammate.ui.onboarding.OnboardingLauncher { *; }
-keep class com.llamatik.teammate.ui.models.LoadModelFromFileAction { *; }
-keep class com.llamatik.teammate.ui.toolwindow.ToolWindowAccess { *; }

-keep class com.llamatik.teammate.git.GitContextService { *; }
-keep class com.llamatik.teammate.psi.PsiCodeInsightService { *; }
-keep class com.llamatik.teammate.index.ProjectIndexService { *; }
-keep class com.llamatik.teammate.run.RunConfigService { *; }
-keep class com.llamatik.teammate.rules.CodebaseRulesService { *; }
-keep class com.llamatik.teammate.terminal.TerminalService { *; }
-keep class com.llamatik.teammate.analysis.StaticAnalysisService { *; }
-keep class com.llamatik.teammate.deps.DependencyService { *; }
-keep class com.llamatik.teammate.build.BuildFailureService { *; }
-keep class com.llamatik.teammate.changelog.ChangelogService { *; }
-keep class com.llamatik.teammate.plan.MultiFilePlanService { *; }
-keep class com.llamatik.teammate.memory.ProjectMemoryService { *; }
-keep class com.llamatik.teammate.ui_repair.UiRepairService { *; }
-keep class com.llamatik.teammate.diagnostics.DiagnosticsService { *; }
-keep class com.llamatik.teammate.benchmark.ModelBenchmarkService { *; }

-keep class com.llamatik.teammate.actions.BasePromptAction { *; }
-keep class com.llamatik.teammate.actions.AskAboutSelectionAction { *; }
-keep class com.llamatik.teammate.actions.ExplainFileAction { *; }
-keep class com.llamatik.teammate.actions.RefactorSelectionAction { *; }
-keep class com.llamatik.teammate.actions.FixSelectedCodeAction { *; }
-keep class com.llamatik.teammate.actions.GenerateTestsAction { *; }
-keep class com.llamatik.teammate.actions.AddDocumentationAction { *; }
-keep class com.llamatik.teammate.actions.EditWithPromptAction { *; }
-keep class com.llamatik.teammate.actions.ExplainFailingBuildAction { *; }
-keep class com.llamatik.teammate.actions.RunDiagnosticsAction { *; }
-keep class com.llamatik.teammate.actions.ExplainCurrentChangesAction { *; }

# ── Kotlin enums ─────────────────────────────────────────────────────────────
# ordinal(), name(), hashCode(), values() are called by compiler-generated
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
# IntelliJ XmlSerializer persists state by field name — renaming fields breaks
# settings persistence.
-keepclassmembers class * implements com.intellij.openapi.components.PersistentStateComponent {
    public *;
    <fields>;
}

# XmlSerializer also serialises nested state data classes (inner classes of
# PersistentStateComponent implementations) by field name via reflection.
# The rule above only guards the outer component class; the inner State /
# StoredXxx classes must be kept separately or their fields get renamed and
# the XML round-trip silently drops all values (empty settings / proposals).
-keep class com.llamatik.teammate.settings.LlamatikAppSettings$State { *; }
-keep class com.llamatik.teammate.persistence.LlamatikProjectState$State { *; }
-keep class com.llamatik.teammate.persistence.LlamatikProjectState$StoredSession { *; }
-keep class com.llamatik.teammate.persistence.LlamatikProjectState$StoredMessage { *; }
-keep class com.llamatik.teammate.benchmark.ModelBenchmarkService$State { *; }
-keep class com.llamatik.teammate.benchmark.ModelBenchmarkService$BenchmarkRun { *; }
-keep class com.llamatik.teammate.plan.MultiFilePlanService$State { *; }
-keep class com.llamatik.teammate.plan.MultiFilePlanService$AgentPlan { *; }
-keep class com.llamatik.teammate.plan.MultiFilePlanService$PlannedFile { *; }
-keep class com.llamatik.teammate.memory.ProjectMemoryService$State { *; }
-keep class com.llamatik.teammate.memory.ProjectMemoryService$MemoryEntry { *; }
-keep class com.llamatik.teammate.rules.CodebaseRulesService$State { *; }
-keep class com.llamatik.teammate.rules.CodebaseRulesService$Rule { *; }

# ── Swing / UI components ────────────────────────────────────────────────────
# Single * intentionally does not cross package separators — this avoids
# triggering ProGuard's class hierarchy check for platform types absent from
# libraryjars (JDK modules, IntelliJ platform).  Full-package classes are
# covered by the keepclassmembers rules below.
-keep class * extends javax.swing.JPanel { *; }
-keep class * extends javax.swing.JComponent { *; }
-keep class * extends javax.swing.JButton { *; }
-keep class * extends com.intellij.openapi.ui.DialogWrapper { *; }
-keep class * extends com.intellij.openapi.ui.DialogBuilder { *; }
-keep class * extends com.intellij.ui.components.JBTextArea { *; }
-keep class * extends com.intellij.ui.components.JBLabel { *; }
-keep class * extends javax.swing.plaf.basic.BasicTabbedPaneUI { *; }

# ── Abstract method implementations ─────────────────────────────────────────
# These methods are called via interface/abstract dispatch, so ProGuard's
# reachability analysis does not see the call and would shrink them away.
# Using keepclassmembers with exact signatures is safe regardless of libraryjars
# because it does NOT trigger a class-hierarchy traversal.
#
# Comparator.compare() — anonymous lambdas from sortedByDescending/sortedWith are
# obfuscated to inner classes (e.g. UiRepairService.b) and ProGuard strips compare()
# because the call goes through the stdlib, which is not in libraryjars.
-keepclassmembers class ** {
    int compare(java.lang.Object, java.lang.Object);
}
# PersistentStateComponent.getState()/loadState() — IntelliJ platform classes are
# absent from libraryjars, so the implements-based rule above cannot resolve the
# hierarchy.  Keeping by exact signature avoids the class-hierarchy traversal.
-keepclassmembers class ** {
    java.lang.Object getState();
    void loadState(java.lang.Object);
}
#
# DialogWrapper.createCenterPanel()
-keepclassmembers class ** {
    javax.swing.JComponent createCenterPanel();
}
# ActionListener.actionPerformed()
-keepclassmembers class ** {
    void actionPerformed(java.awt.event.ActionEvent);
}
# Swing custom painting — called by RepaintManager via virtual dispatch.
# Anonymous JPanel/JButton/JToggleButton subclasses that override these
# lose their color/shape rendering if ProGuard renames or removes them.
-keepclassmembers class ** {
    protected void paintComponent(java.awt.Graphics);
    protected void paintBorder(java.awt.Graphics);
    public void paintComponent(java.awt.Graphics);
    public void paintBorder(java.awt.Graphics);
}
# JLayeredPane.doLayout() override — called by AWT layout manager via virtual dispatch.
# Anonymous subclasses that override doLayout() to set child bounds lose their layout
# if ProGuard renames the method, making the scroll pane inside invisible (zero bounds).
-keepclassmembers class ** {
    public void doLayout();
}
# DropTargetListener (image drag-and-drop in ImageAttachmentBar)
-keepclassmembers class ** {
    void drop(java.awt.dnd.DropTargetDropEvent);
    void dragEnter(java.awt.dnd.DropTargetDragEvent);
    void dragOver(java.awt.dnd.DropTargetDragEvent);
    void dropActionChanged(java.awt.dnd.DropTargetDragEvent);
    void dragExit(java.awt.dnd.DropTargetEvent);
}
# DocumentListener (AtMentionHandler, search fields)
-keepclassmembers class ** {
    void insertUpdate(javax.swing.event.DocumentEvent);
    void removeUpdate(javax.swing.event.DocumentEvent);
    void changedUpdate(javax.swing.event.DocumentEvent);
}
# DocumentAdapter (IntelliJ platform convenience class used in SkillLibraryDialog)
-keepclassmembers class ** {
    void textChanged(javax.swing.event.DocumentEvent);
}
# FocusAdapter — composer input placeholder repaint on focus gain/loss
-keepclassmembers class ** {
    void focusGained(java.awt.event.FocusEvent);
    void focusLost(java.awt.event.FocusEvent);
}
# MouseAdapter — click-to-select in AtMentionHandler popup list
-keepclassmembers class ** {
    void mousePressed(java.awt.event.MouseEvent);
    void mouseReleased(java.awt.event.MouseEvent);
    void mouseClicked(java.awt.event.MouseEvent);
}
# KeyAdapter — arrow/Enter/Escape navigation in AtMentionHandler popup
-keepclassmembers class ** {
    void keyPressed(java.awt.event.KeyEvent);
    void keyReleased(java.awt.event.KeyEvent);
    void keyTyped(java.awt.event.KeyEvent);
}
# ChangeListener / AdjustmentListener — tab switching and scroll-to-bottom button
-keepclassmembers class ** {
    void stateChanged(javax.swing.event.ChangeEvent);
    void adjustmentValueChanged(java.awt.event.AdjustmentEvent);
}
# JWindow subclass in AtMentionHandler — must return false to stay non-focusable
-keepclassmembers class ** {
    public boolean getFocusableWindowState();
}
# BasicTabbedPaneUI anonymous subclass — custom rounded-tab painting in ChatTabPanel
-keepclassmembers class ** {
    protected void paintTabBorder(java.awt.Graphics, int, int, int, int, int, int, boolean);
    protected void paintTabBackground(java.awt.Graphics, int, int, int, int, int, int, boolean);
    protected void paintFocusIndicator(java.awt.Graphics, int, java.awt.Rectangle[], int, java.awt.Rectangle, java.awt.Rectangle, boolean);
    protected void paintContentBorder(java.awt.Graphics, int, int);
    protected void installDefaults();
}

# ── JetBrains Marketplace recommendations ────────────────────────────────────
# https://plugins.jetbrains.com/docs/marketplace/obfuscate-the-plugin.html
#
# 1. Aggressive overloading: reuse the same short names (a, b …) for methods
#    with different signatures, maximising name collision and making
#    decompiled output harder to follow.
#    NOTE: disabled for Kotlin — the Kotlin compiler generates overloads
#    (e.g. default-argument bridges) that share parameter counts, which
#    can clash under -overloadaggressively and cause VerifyErrors at runtime.
#    Re-enable only if the plugin is ever converted to pure Java.
# -overloadaggressively
#
# 2. Optimisation: the doc says to enable all four default operations (shrink,
#    optimise, obfuscate, preverify).  ProGuard's bytecode optimiser can
#    corrupt Kotlin metadata in some edge cases, so we disable it here and
#    rely on shrinking + obfuscation + preverification for a safe result.
-dontoptimize
#
# 3. Preverification: disabled because IntelliJ platform classes are not in
#    libraryjars, so ProGuard cannot resolve the full class hierarchy required
#    to generate correct StackMapTable attributes.  The JDK and IntelliJ runtime
#    already have correct stack maps from the Kotlin compiler — re-preverifying
#    without the full platform classpath produces "incomplete class hierarchy" errors.
-dontpreverify

# ── General safety ───────────────────────────────────────────────────────────
# Suppress warnings — cross-class references within our own code are safe,
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

# Keep source file and line numbers so stack traces in crash reports remain
# readable even after obfuscation.  The mapping file written by ProGuard
# (proguard-mapping.txt) lets you de-obfuscate them offline.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
