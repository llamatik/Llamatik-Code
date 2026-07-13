package com.llamatik.teammate.ui

/**
 * All user-visible strings in the Llamatik plugin, grouped by screen/component.
 * Extract translations by replacing the values here with resource-bundle lookups.
 */
object Strings {

    // ── App / shared ──────────────────────────────────────────────────────────
    object App {
        const val NAME = "Llamatik Code"
        const val DISCLAIMER = "AI can make mistakes, so double-check it"
        const val DISMISS = "Dismiss"
        const val RETRY = "Retry"
        const val BACK = "Back"
        const val COPY = "Copy"
        const val COPY_ALL = "Copy All"
        const val SELECT_ALL = "Select All"
        const val CLOSE = "Close"
        const val VIEW_DIFF = "View Diff"
        const val APPLY_EDIT = "Apply Edit"
        const val NO_MODEL_CONFIGURED_MSG =
            "No model is configured. Go to Settings → Llamatik Code and set a model path."
        const val NO_MODEL_CONFIGURED_TITLE = "No model loaded"
        const val TRIAL_EXPIRED_MSG = "Upgrade to Pro to unlock advanced Agent features."
        const val BASE_MODEL_WARNING =
            "Base model detected. For best results, use an instruction-tuned model (e.g. Qwen3, Gemma-it, Phi3-instruct)."
        fun trialDaysLeft(days: Int) = "$days days left in trial. Upgrade to Pro to keep Agent features."
    }

    // ── Composer / tool window ────────────────────────────────────────────────
    object Composer {
        const val SEND_TOOLTIP = "Send"
        const val STOP_TOOLTIP = "Stop generation"
        const val CONTEXT_PILL = "Context (1)"
        const val PROFILE_PILL = "Default"
        const val SETTINGS_PILL = "⚙"
        const val INPUT_TOOLTIP_DEFAULT =
            "Run a local coding mission, use @filename to attach source files, or describe a task"
        const val INPUT_PLACEHOLDER = "Describe a mission or use @filename to attach a file"
        const val INPUT_TOOLTIP_AGENT = "Run a local coding mission: plan, patch, review, and verify locally"
        const val INPUT_TOOLTIP_ASK = "Ask a focused question about the current file or project"
        const val INPUT_TOOLTIP_NO_MODEL = "Configure a model in Settings → Llamatik Code to start"
    }

    // ── Mode toggles ──────────────────────────────────────────────────────────
    object Mode {
        const val AGENT = "Agent"
        const val ASK = "Ask"
    }

    // ── Agent tab ─────────────────────────────────────────────────────────────
    object Agent {
        const val HERO_TITLE = "Autonomous Local Development"
        const val HERO_SUBTITLE =
            "Plan, patch, review, and verify code locally — your source code never leaves your machine."
        const val WORKFLOW_TITLE = "Local coding missions"
        const val WORKFLOW_BODY =
            "Run guided missions: Review → Plan → Patch → Verify → Audit. All context stays private on your device."
        const val PROMPTS_TITLE = "Start a local coding mission"
        const val APPROVE = "Approve all changes"
        const val REJECT = "Reject"
        const val THINKING_COLLAPSED = "▶ Thinking"
        const val THINKING_EXPANDED = "▼ Thinking"
        const val BACK = "← Back"
        const val STATUS_ANALYZING = "Analyzing…"
        const val TASK_HEADER = "Agent task"
        const val OVERLAY_TRIAL_EXPIRED = "Upgrade to Pro to unlock advanced Agent features."
        const val OVERLAY_NO_MODEL = "Configure a model to use Agent mode."
        const val OVERLAY_LOADING_MODEL = "Loading model…"
        const val OVERLAY_DOWNLOADING_TITLE = "Downloading model"
        const val OVERLAY_DOWNLOADING_CANCEL = "Cancel"
        const val REASONING_STREAMING = "▼ Reasoning…"
        const val REASONING_PREFIX_COLLAPSED = "▶ Reasoned for "
        const val REASONING_PREFIX_EXPANDED = "▼ Reasoned for "
        const val TOOL_CALL_ICON = "⚙"
        const val AUTO_APPROVE_NEXT = "Auto-apply edits on next run"
        const val AUTO_BUILD_NEXT = "Auto-build after applying edits"
        const val AUTO_BUILD_IDE_NEXT = "  Build in IDE (incremental)"
        const val AUTO_BUILD_BG_NEXT = "  Build in background (Gradle)"
        const val AUTO_FIX_NEXT = "Auto-fix build errors"
        const val AGGRESSIVE_MODE_NEXT = "Aggressive mode"
        const val RUN_OPTIONS_PRO_REQUIRED = "Requires Pro"
        const val SCROLL_TO_BOTTOM = "↓  Scroll to bottom"
        fun brewedFor(elapsed: String) = "brewed for $elapsed"

        const val TRACE_COLLAPSED = "▶ What I tried"
        const val TRACE_EXPANDED  = "▼ What I tried"
        const val RESPONSE_ACTION_FIX_IT      = "Fix it automatically"
        const val RESPONSE_ACTION_CREATE_PLAN = "Create plan"
        const val RESPONSE_ACTION_EXPLAIN_MORE = "Explain more"
        const val AGGRESSIVE_MODE_LABEL = "Aggressive mode"
        const val AGGRESSIVE_MODE_TOOLTIP = "Agent tries multiple fallback strategies before giving up"

        val SUGGESTIONS = listOf(
            "Add input validation to the login form and show inline error messages",
            "The null pointer crash on line 42 of UserRepository — find the root cause and fix it",
            "Review my staged changes for bugs, security issues, and missing error handling",
            "Extract the duplicated pagination logic into a shared utility class",
            "Write unit tests covering edge cases for the payment calculation module",
            "The Gradle build is failing with an unresolved reference — diagnose and fix it",
            "Explain how data flows through the authentication module end to end",
            "Replace all usages of the deprecated Handler class with coroutines"
        )

        // Mission workflow cards
        val MISSION_CARDS = listOf(
            MissionCard("Implement a feature locally", "FEATURE_IMPLEMENTATION",
                "You are running Offline Enterprise Pair Programmer mode.\n\nMission:\nImplement a new feature in this project.\n\nRequired workflow:\n1. Call enterprise_mission_create.\n2. Gather project context before editing.\n3. Explain risks.\n4. Create a plan.\n5. Apply small, reviewable patches.\n6. Show diff.\n7. Verify when possible.\n8. Produce enterprise_mission_report.\n9. Include rollback instructions.\n10. Do not use external knowledge unless explicitly allowed."),
            MissionCard("Fix a bug with project context", "BUG_FIX",
                "You are running Offline Enterprise Pair Programmer mode.\n\nMission:\nFix the reported bug using full project context.\n\nRequired workflow:\n1. Call enterprise_mission_create.\n2. Gather project context before editing.\n3. Explain risks.\n4. Create a plan.\n5. Apply small, reviewable patches.\n6. Show diff.\n7. Verify when possible.\n8. Produce enterprise_mission_report.\n9. Include rollback instructions.\n10. Do not use external knowledge unless explicitly allowed."),
            MissionCard("Review current changes before commit", "CODE_REVIEW",
                "You are running Offline Enterprise Pair Programmer mode.\n\nMission:\nReview the current git diff for quality, security, and correctness before committing.\n\nRequired workflow:\n1. Call enterprise_mission_create.\n2. Gather project context before editing.\n3. Explain risks.\n4. Create a plan.\n5. Apply small, reviewable patches.\n6. Show diff.\n7. Verify when possible.\n8. Produce enterprise_mission_report.\n9. Include rollback instructions.\n10. Do not use external knowledge unless explicitly allowed."),
            MissionCard("Safely refactor current file", "SAFE_REFACTOR",
                "You are running Offline Enterprise Pair Programmer mode.\n\nMission:\nSafely refactor the current file without changing behavior.\n\nRequired workflow:\n1. Call enterprise_mission_create.\n2. Gather project context before editing.\n3. Explain risks.\n4. Create a plan.\n5. Apply small, reviewable patches.\n6. Show diff.\n7. Verify when possible.\n8. Produce enterprise_mission_report.\n9. Include rollback instructions.\n10. Do not use external knowledge unless explicitly allowed."),
            MissionCard("Generate tests for this file", "TEST_GENERATION",
                "You are running Offline Enterprise Pair Programmer mode.\n\nMission:\nGenerate comprehensive tests for the current file.\n\nRequired workflow:\n1. Call enterprise_mission_create.\n2. Gather project context before editing.\n3. Explain risks.\n4. Create a plan.\n5. Apply small, reviewable patches.\n6. Show diff.\n7. Verify when possible.\n8. Produce enterprise_mission_report.\n9. Include rollback instructions.\n10. Do not use external knowledge unless explicitly allowed."),
            MissionCard("Repair build failure", "BUILD_FAILURE_REPAIR",
                "You are running Offline Enterprise Pair Programmer mode.\n\nMission:\nRepair the current build failure using available diagnostics.\n\nRequired workflow:\n1. Call enterprise_mission_create.\n2. Gather project context before editing.\n3. Explain risks.\n4. Create a plan.\n5. Apply small, reviewable patches.\n6. Show diff.\n7. Verify when possible.\n8. Produce enterprise_mission_report.\n9. Include rollback instructions.\n10. Do not use external knowledge unless explicitly allowed."),
            MissionCard("Explain this module architecture", "ARCHITECTURE_EXPLANATION",
                "You are running Offline Enterprise Pair Programmer mode.\n\nMission:\nExplain the architecture, responsibilities, and design of the current module.\n\nRequired workflow:\n1. Call enterprise_mission_create.\n2. Gather project context before editing.\n3. Explain risks.\n4. Create a plan.\n5. Apply small, reviewable patches.\n6. Show diff.\n7. Verify when possible.\n8. Produce enterprise_mission_report.\n9. Include rollback instructions.\n10. Do not use external knowledge unless explicitly allowed."),
            MissionCard("Modernize deprecated APIs", "MIGRATION",
                "You are running Offline Enterprise Pair Programmer mode.\n\nMission:\nModernize deprecated or legacy APIs in this project.\n\nRequired workflow:\n1. Call enterprise_mission_create.\n2. Gather project context before editing.\n3. Explain risks.\n4. Create a plan.\n5. Apply small, reviewable patches.\n6. Show diff.\n7. Verify when possible.\n8. Produce enterprise_mission_report.\n9. Include rollback instructions.\n10. Do not use external knowledge unless explicitly allowed.")
        )
    }

    data class MissionCard(val title: String, val missionType: String, val prompt: String)

    // ── Chat / Ask tab ────────────────────────────────────────────────────────
    object Chat {
        const val NEW_CHAT = "+ New Chat"
        const val CLEAR = "Clear"
        const val PROMPTS_TITLE = "Prompts to try"
        const val TAB_CLOSE_TOOLTIP = "Close"
        const val TAB_CLOSE_ICON = "×"

        // Chat mode welcome
        const val CHAT_SUBTITLE = "What can I help you build today?"
        const val CHAT_INFO_TITLE = "Use a Llamatik model for private local assistance"
        const val CHAT_INFO_BODY =
            "Stream responses locally, keep your workflow private, and move into Agent mode when you want assisted multi-step execution."

        // Ask mode welcome
        const val ASK_HERO_TITLE = "Ask"
        const val ASK_SUBTITLE = "Ask a focused question about your current file or project"
        const val ASK_INFO_TITLE = "Use Ask for quick, scoped questions"
        const val ASK_INFO_BODY =
            "Ask keeps the same workflow, but presents a lighter question-first surface."

        val CHAT_SUGGESTIONS = listOf(
            "Find and resolve build errors in my project",
            "Add documentation to my current file",
            "Extract all hardcoded strings from this class and move them into strings.xml",
            "Fix the warnings and errors in the editor",
        )
        val ASK_SUGGESTIONS = listOf(
            "Explain this file and summarize its responsibilities",
            "What could break in this implementation?",
            "How should I refactor this class safely?",
            "Which tests are missing here?",
        )
    }

    // ── Apply edit dialog ─────────────────────────────────────────────────────
    object EditDialog {
        fun applyMsg(filePath: String) = "Apply proposed changes to $filePath?"
        const val APPLY_TITLE = "Apply Llamatik Edit"
        const val PROPOSED_CHANGES_HEADER = "Proposed changes"
        const val ACCEPT = "Accept"
        const val REJECT = "Reject"
        const val VIEW_DIFF = "Diff"
        const val ACCEPTED_LABEL = "✓ Applied"
        const val REJECTED_LABEL = "✕ Rejected"
        const val FREE_PLAN_LIMIT = "Free plan supports 1 file change. Upgrade to apply all."
    }

    // ── Diff viewer ───────────────────────────────────────────────────────────
    object Diff {
        const val WINDOW_TITLE = "Llamatik Code Diff"
        const val SIDE_ORIGINAL = "Original"
        const val SIDE_PROPOSED = "Proposed"
        fun errorNotFile(path: String) = "Cannot apply edit: '$path' is a directory, not a file."
        const val ERROR_TITLE = "Invalid Edit Proposal"
    }

    // ── Model card / library ──────────────────────────────────────────────────
    object ModelCard {
        const val STATUS_NOT_DOWNLOADED = "Not downloaded"
        const val STATUS_DOWNLOADING = "Downloading…"
        const val STATUS_PAUSED = "Paused"
        const val STATUS_FAILED = "Failed"
        const val STATUS_DOWNLOADED = "Downloaded"
        const val STATUS_LOADING = "Loading…"
        const val STATUS_LOADED = "Loaded"
        const val STATUS_UNLOADED = "Unloaded"
        const val STATUS_CORRUPTED = "Corrupted"
        const val ACTION_DOWNLOAD = "Download"
        const val ACTION_CANCEL = "Cancel"
        const val ACTION_LOAD = "Load"
        const val ACTION_DELETE = "Delete"
        const val ACTION_UNLOAD = "Unload"
        const val ACTION_SET_DEFAULT = "Set as default"
        const val COMING_SOON = "Coming in next release"
        const val HW_OK = "Your system meets the recommended requirements."
        const val HW_SLOW = "Your system meets minimum requirements but may run slowly."
        const val HW_INSUFFICIENT = "Insufficient system resources for this model."
        const val TIER_1 = "Tier 1 — Lightweight (up to 5 GB)"
        const val TIER_2 = "Tier 2 — Balanced (5–15 GB)"
        const val TIER_3 = "Tier 3 — High-performance (15 GB+)"
        const val TIER_BADGE_1 = "🟢 Best for quick tasks"
        const val TIER_BADGE_2 = "🟡 Best for real projects"
        const val TIER_BADGE_3 = "🔴 Best for large codebases (Pro)"
        const val TIER_3_PAYWALL_TITLE = "Pro model"
    }

    // ── Empty state panel ─────────────────────────────────────────────────────
    object EmptyState {
        const val TITLE = "No model loaded"
        const val SUBTITLE = "Your code never leaves your machine — all AI runs locally on-device."
        const val DOWNLOAD_RECOMMENDED = "Download recommended model"
        const val DOWNLOAD_DESC = "Qwen3-14B-Q4_K_M · 9 GB · requires 16 GB RAM"
        const val BROWSE_LIBRARY = "Browse model library"
        const val BROWSE_DESC = "Pick from lightweight to high-performance models"
        const val LOAD_FROM_FILE = "Load model from file"
        const val LOAD_FROM_FILE_DESC = "Point to an existing .gguf file on your disk"
    }

    // ── Loading state panel ───────────────────────────────────────────────────
    object LoadingState {
        const val TITLE = "Loading model…"
        const val HINT = "This may take a moment depending on model size."
    }

    // ── Load model from file action ───────────────────────────────────────────
    object LoadFromFile {
        const val DIALOG_TITLE = "Select Local Model File (.gguf)"
        const val FILE_FILTER_DESC = "GGUF Model Files"
        const val ERROR_NOT_EXISTS_TITLE = "Invalid Model File"
        const val ERROR_UNSUPPORTED_TITLE = "Unsupported Format"
        const val SUCCESS_TITLE = "Model Loaded"
        fun errorNotExists(path: String) = "File does not exist: $path"
        fun errorCannotRead(path: String) = "Cannot read file: $path"
        fun errorNotGguf(name: String) = "File must be a .gguf model: $name"
        fun successMsg(name: String, path: String) = "Model registered: $name\nPath: $path"
    }

    // ── Settings ──────────────────────────────────────────────────────────────
    object Settings {
        const val PAGE_NAME = "Llamatik Code"
        const val AUTO_APPROVE = "Auto-apply edits without confirmation"
        const val AUTO_BUILD = "Auto-build after applying edits"
        const val AUTO_FIX = "Auto-fix build errors"
        const val BROWSE = "Browse…"
        const val FILE_CHOOSER_TITLE = "Select local .gguf model file"
        const val REGISTER = "Register"
        const val REGISTER_TOOLTIP = "Register the path above as a known model"
        const val REGISTER_MMPROJ_TOOLTIP = "Register the mmproj path above as a known mmproj"
        const val USE_SELECTED = "Use selected"
        const val USE_SELECTED_TOOLTIP = "Set the selected model as active model path"
        const val USE_SELECTED_MMPROJ_TOOLTIP = "Set the selected mmproj as active mmproj path"
        const val DELETE_SELECTED = "Delete selected"
        const val DELETE_SELECTED_TOOLTIP = "Remove the selected entry from the registry"
        const val SUCCESS_REGISTERED_MMPROJ_MSG = "Mmproj registered successfully."
        const val BROWSE_CATALOG = "Browse model catalog…"
        const val MODEL_LIBRARY_TITLE = "Llamatik Code — Model Library"
        const val RESET_ONBOARDING = "Reset onboarding"
        const val RESET_ONBOARDING_TOOLTIP =
            "Shows the welcome dialog again next time the tool window is opened"
        const val SECTION_ACTIVE_MODEL = "<html><b>Active model path</b></html>"
        const val SECTION_REGISTERED_MODELS = "<html><b>Registered models</b></html>"
        const val SECTION_ACTIVE_MMPROJ = "<html><b>Active mmproj path</b></html>"
        const val SECTION_REGISTERED_MMPROJS = "<html><b>Registered mmprojs</b></html>"
        const val SECTION_BEHAVIOUR = "<html><b>Run Options</b></html>"
        const val SECTION_CONTEXT = "<html><b>Context</b></html>"
        const val FIELD_IGNORED_DIRS = "Ignored directories (comma-separated):"
        const val FIELD_INSTRUCTIONS = "Project rules / instructions:"
        const val MODEL_PATH_TOOLTIP = "Path to the .gguf model file used for inference"
        const val ERROR_NO_PATH_MSG = "Enter a model path first."
        const val ERROR_NO_PATH_TITLE = "No Path"
        const val ERROR_INVALID_PATH_TITLE = "Invalid Model Path"
        const val SUCCESS_REGISTERED_MSG = "Model registered successfully."
        const val ERROR_NO_SELECTION_MSG = "Select a model from the list first."
        const val ERROR_NO_SELECTION_TITLE = "No Selection"
        const val CONFIRM_DELETE_TITLE = "Confirm Remove"
        const val ONBOARDING_RESET_MSG =
            "Onboarding will appear the next time you open the Llamatik Code panel."
        const val ONBOARDING_RESET_TITLE = "Onboarding Reset"
        fun confirmDeleteMsg(name: String) =
            "Remove \"$name\" from the registry? The file will not be deleted from disk."
        const val VALIDATION_NOT_VALID = "Not a valid file path."
        fun validationNotExists(path: String) = "File does not exist: $path"
        fun validationNotFile(path: String) = "Path is not a file: $path"
        const val VALIDATION_NOT_GGUF = "File must end with .gguf"
        const val SECTION_AGENT_MODE = "<html><b>Agent Mode</b></html>"
        const val AGGRESSIVE_MODE_SETTING = "Aggressive agent mode (try fallbacks before giving up)"
        const val SECTION_AUTONOMOUS = "<html><b>Autonomous Local Development</b></html>"
        const val ENTERPRISE_MODE_ENABLED = "Enable Offline Enterprise Pair Programmer mode"
        const val ENTERPRISE_DEFAULT_SAFETY = "Default safety level:"
        const val ENTERPRISE_ALLOW_EXTERNAL = "Allow external knowledge in enterprise missions"
        const val ENTERPRISE_REQUIRE_PLAN = "Require plan before writing files"
        const val ENTERPRISE_REQUIRE_DIFF = "Require diff preview before applying changes"
        const val ENTERPRISE_REQUIRE_AUDIT = "Require audit report at mission end"
        const val SECTION_INLINE_COMPLETION = "<html><b>Inline Completions</b></html>"
        const val INLINE_COMPLETION_ENABLED = "Enable inline code completions (experimental)"
        const val INLINE_COMPLETION_DEBOUNCE = "Debounce delay (ms):"
        const val INLINE_COMPLETION_MAX_CONTEXT = "Max context characters:"
        const val INLINE_COMPLETION_MAX_TOKENS = "Max completion tokens:"
        const val INLINE_COMPLETION_NOTE = "Inline completions are disabled by default. Enable only when the model is fast enough to avoid blocking typing."
    }

    // ── Inference settings ────────────────────────────────────────────────────
    object Inference {
        const val BUTTON_TOOLTIP       = "Inference settings"
        const val DIALOG_TITLE         = "Inference Settings"
        const val SECTION_GENERATION   = "<html><b>Generation</b></html>"
        const val SECTION_CONTEXT      = "<html><b>Context</b></html>"
        const val SECTION_RUNTIME      = "<html><b>Runtime</b></html>"
        const val TEMPERATURE          = "Temperature:"
        const val TEMPERATURE_TOOLTIP  = "Sampling temperature (0 = deterministic, higher = more creative)"
        const val MAX_TOKENS               = "Max tokens:"
        const val MAX_TOKENS_TOOLTIP       = "Maximum number of tokens to generate per response"
        const val MAX_TOKENS_FREE_TOOLTIP  = "Maximum tokens per response (free tier capped at 4096 — upgrade for higher limits)"
        const val PRO_REQUIRED             = "Requires Pro"
        const val CONTEXT_SIZE         = "Context size (tokens):"
        const val CONTEXT_SIZE_TOOLTIP = "KV cache size — larger allows longer conversations but uses more VRAM"
        const val BATCH_SIZE           = "Batch size:"
        const val BATCH_SIZE_TOOLTIP   = "Prompt evaluation batch size — must be ≥ prompt token count"
        const val NUM_THREADS          = "CPU threads:"
        const val NUM_THREADS_TOOLTIP  = "Number of CPU threads used for inference"
        const val SECTION_RESOURCES    = "<html><b>Resource usage</b></html>"
        const val RESOURCE_MODE        = "Resource mode:"
        const val RESOURCE_MODE_TOOLTIP = "Controls how aggressively the plugin uses CPU while generating. Low priority yields CPU to other apps. Full speed uses all allocated threads at normal priority."
        const val RESOURCE_MODE_BALANCED = "Balanced (recommended)"
        const val RESOURCE_MODE_LOW     = "Low priority (background)"
        const val RESOURCE_MODE_FULL    = "Full speed"
        const val RESOURCE_MODE_BALANCED_DESC = "Uses half your CPU cores at normal priority — leaves headroom for the IDE and other apps"
        const val RESOURCE_MODE_LOW_DESC      = "Runs inference threads at minimum OS priority — slowest but least intrusive"
        const val RESOURCE_MODE_FULL_DESC     = "Uses all allocated threads at normal priority — fastest but may affect IDE responsiveness"
        const val USE_MMAP             = "Use memory-mapped I/O (mmap)"
        const val USE_MMAP_TOOLTIP     = "Load model weights via mmap — faster startup, shares memory across processes"
        const val FLASH_ATTENTION      = "Enable Flash Attention"
        const val FLASH_ATTENTION_TOOLTIP = "Use Flash Attention kernel — reduces VRAM usage on supported hardware"
        const val GPU_LAYERS           = "GPU offload (all layers)"
        const val GPU_LAYERS_TOOLTIP   = "Offload all model layers to the GPU (sets n_gpu_layers = -1). Faster inference but requires enough VRAM to fit the model. Leave off to run on CPU only."
        const val RESET_DEFAULTS       = "Reset to defaults"
        const val UPGRADE_REQUIRED     = "Context size requires a Pro licence. Upgrade to unlock advanced settings."
        const val UPGRADE_BUTTON       = "Upgrade"
    }

    // ── Onboarding ────────────────────────────────────────────────────────────
    object Onboarding {
        const val DIALOG_TITLE = "Welcome to Llamatik Code"
        const val STEP1_HEADING = "Autonomous local pair programmer"
        const val STEP1_BODY1 =
            "Llamatik Code runs <b>entirely on your device</b>. Build, refactor, review, and verify code locally — your source code never leaves your machine."
        const val STEP1_BODY2 =
            "Powered by local GGUF models. Run guided coding missions: Review → Plan → Patch → Verify → Audit — completely offline."
        const val STEP1_BODY3 = "<b>Privacy guarantee:</b> Your code stays on your hardware, always. No cloud, no telemetry, no source upload."
        const val STEP1_NEXT = "I just want it to work →"
        // Setup presets
        const val PRESET_FAST = "Fast local coding"
        const val PRESET_BALANCED = "Balanced local agent"
        const val PRESET_QUALITY = "Best local quality"
        const val PRESET_LOW_MEM = "Low-memory machine"
        const val PRESET_ENTERPRISE = "Enterprise / private mode"
        const val PRESET_FAST_DESC = "Devstral Small 1.1 — agentic coding, ~16 GB RAM"
        const val PRESET_BALANCED_DESC = "Qwen3 14B — strong reasoning and coding (~16 GB RAM)"
        const val PRESET_QUALITY_DESC = "DeepSeek R1 Distill 32B — best quality, needs 32 GB+ RAM"
        const val PRESET_LOW_MEM_DESC = "Gemma 4 E4B — runs on 8 GB or less"
        const val PRESET_ENTERPRISE_DESC = "Offline, no external connections, approval required on every change"
        const val STEP1_ADVANCED_MODELS = "Advanced: choose specific model…"
        const val READY_TITLE = "Ready for local coding missions"
        const val READY_BODY = "Your model is loaded. Start your first private coding mission below."
        const val START_FIRST_MISSION = "Start your first local coding mission"
        const val STEP2_HEADING = "Set up your model"
        const val STEP2_RECOMMENDED_LABEL = "<b>Recommended:</b> Qwen3-14B-Q4_K_M"
        const val STEP2_RECOMMENDED_DETAILS = "~9 GB disk | 16 GB RAM recommended"
        const val STEP2_RECOMMENDED_DESC =
            "Excellent reasoning, great for complex refactoring and architecture tasks."
        const val STEP2_DOWNLOAD_RECOMMENDED = "Download recommended model (Qwen3-14B)"
        const val STEP2_CHOOSE_ANOTHER = "Choose another model…"
        const val STEP2_LOAD_FROM_FILE = "Load model from file (.gguf)"
        const val STEP2_SKIP = "Skip for now"
        const val STEP2_BACK = "Back"
        const val STEP3_HEADING = "Downloading model…"
        const val STEP3_HEADING_DONE = "Model ready!"
        const val STEP3_BODY_DONE = "The model has been downloaded and registered. You can start using Llamatik Code now."
        const val STEP3_OPEN_AGENT = "Open Agent"
        const val STEP3_FINISH = "Finish"
        const val MODEL_LIBRARY_TITLE = "Model Library"
    }

    // ── Chat session ──────────────────────────────────────────────────────────
    object Session {
        const val DEFAULT_TITLE = "New Chat"
        const val INITIAL_TITLE = "Chat 1"
        fun numberedTitle(n: Int) = "Chat $n"
    }

    // ── Backend / model labels ────────────────────────────────────────────────
    object Backend {
        const val LABEL_NATIVE_UNAVAILABLE = "Native lib unavailable"
        const val LABEL_NO_MODEL = "No model loaded"
        fun errorNativeLib(err: String) =
            "Llamatik native library could not be loaded: $err\n\nTry reinstalling the plugin. If the problem persists, please report it at github.com/ferranpons/Llamatik-Code-Issue-Tracker"
    }

    // ── Download / model setup ────────────────────────────────────────────────
    object ModelSetup {
        const val DIALOG_TITLE = "How to add a local model"
        const val INTRO = "This plugin currently supports local GGUF files only."
        const val NEXT_STEPS = "Next steps:"
        const val STEP1 =
            "1. Download the GGUF model manually from your preferred source (e.g. Hugging Face)."
        const val STEP2 =
            "2. In Settings → Llamatik Code, browse to the .gguf file and click Register."
        fun recommendedModel(name: String) = "Recommended model: $name"
        fun source(src: String) = "Source: $src"
        const val SOURCE_MANUAL = "Manual local download"
    }

    // ── Skills ────────────────────────────────────────────────────────────────
    object Skills {
        const val SECTION_TITLE = "<html><b>Agent Skills</b></html>"
        const val LIBRARY_TITLE = "Skill Library"
        const val OPEN_LIBRARY = "Browse Skill Library"
        const val SEARCH_PLACEHOLDER = "Search skills…"
        const val NO_RESULTS = "No skills match your search."
        const val ACTIVE_LABEL = "Active skills:"
        const val NONE_ACTIVE = "None"
        const val STATUS_NOT_DOWNLOADED = "Not downloaded"
        const val STATUS_DOWNLOADED = "Downloaded"
        const val STATUS_ACTIVE = "Active"
        const val ACTION_DOWNLOAD = "Download"
        const val ACTION_ACTIVATE = "Activate"
        const val ACTION_DEACTIVATE = "Deactivate"
        const val ACTION_DELETE = "Delete"
        const val SECTION_SETTINGS = "<html><b>Skills</b></html>"
        fun activeCount(n: Int) = if (n == 0) "No skills active" else "$n skill${if (n == 1) "" else "s"} active"
        const val FREE_PLAN_SKILL_LIMIT = "Free plan supports 3 active skills. Upgrade to activate all."
        const val ADD_SKILL_BUTTON = "+ Add Skill"
        const val ADD_SKILL_DIALOG_TITLE = "Add Custom Skill"
        const val ADD_SKILL_TAB_PASTE = "Paste Markdown"
        const val ADD_SKILL_TAB_URL = "Download from URL"
        const val ADD_SKILL_NAME_LABEL = "Skill name:"
        const val ADD_SKILL_NAME_PLACEHOLDER = "e.g. My Kotlin Rules"
        const val ADD_SKILL_CONTENT_LABEL = "Markdown content:"
        const val ADD_SKILL_CONTENT_PLACEHOLDER = "Paste your skill markdown here…"
        const val ADD_SKILL_URL_LABEL = "URL to .md file:"
        const val ADD_SKILL_URL_PLACEHOLDER = "https://example.com/my-skill.md"
        const val ADD_SKILL_ERROR_EMPTY_NAME = "Please enter a skill name."
        const val ADD_SKILL_ERROR_EMPTY_CONTENT = "Please paste markdown content."
        const val ADD_SKILL_ERROR_EMPTY_URL = "Please enter a URL."
        const val ADD_SKILL_ERROR_DOWNLOAD = "Failed to download the file. Check the URL and try again."
        const val DELETE_SKILL_CONFIRM_TITLE = "Delete Custom Skill"
        const val DELETE_SKILL_CONFIRM_MSG = "Delete this skill and remove its file from disk?"
        const val CUSTOM_BADGE = "Custom"
    }

    // ── Build verification ────────────────────────────────────────────────────
    object Verification {
        const val BUILDING = "Building…"
        const val RUNNING_TESTS = "Running tests…"
        const val BUILD_OK = "Build OK"
        const val TESTS_PASSED = "Tests passed"
        const val BUILD_FAILED = "Build failed"
        const val TESTS_FAILED = "Tests failed"
        const val AUTO_FIX = "Auto-fix"
        const val CANCEL_BUILD = "Cancel build"
        const val RETRY_LIMIT = "Auto-fix limit reached — please fix manually"
    }

    // ── Upgrade ───────────────────────────────────────────────────────────────
    object Upgrade {
        const val UNLOCK_PRO_BUTTON = "Unlock PRO"
        const val UNLOCK_PRO_TOOLTIP = "Upgrade to Llamatik Code Pro"
    }

    // ── Task queue panel ──────────────────────────────────────────────────────
    object TaskQueue {
        const val PANEL_TITLE = "Tasks"
        const val SECTION_RUNNING = "Running"
        const val SECTION_DONE = "Done"
        const val EMPTY_HINT = "No tasks yet"
        const val STATUS_RUNNING = "Running…"
        const val STATUS_DONE = "Done"
        const val STATUS_HALTED = "Halted"
        const val STATUS_ERROR = "Failed"
        fun statusDoneChanges(n: Int) = "Done · $n change${if (n == 1) "" else "s"}"
        const val NEW_TASK_BUTTON = "+ New"
        const val NEW_TASK_TOOLTIP = "Start a new task"
        const val RERUN_TOOLTIP = "Re-run task"
        const val DELETE_TOOLTIP = "Remove task"
        const val TOGGLE_TOOLTIP = "Task queue"
        const val PRO_REQUIRED_TITLE = "Pro feature"
        const val PRO_REQUIRED_MSG =
            "Running multiple tasks in parallel requires a Llamatik Code Pro subscription."
        const val BUSY_TITLE = "Task in progress"
        const val BUSY_MSG =
            "A task is already running. Upgrade to Pro to run multiple tasks in parallel."
    }

    // ── Chat list panel ───────────────────────────────────────────────────────
    object ChatList {
        const val PANEL_TITLE = "Chats"
        const val EMPTY_HINT = "No chats yet"
        const val NEW_CHAT_TOOLTIP = "Start a new chat"
        const val DELETE_TOOLTIP = "Remove chat"
        const val TOGGLE_TOOLTIP = "Chat list"
    }

    // ── About ─────────────────────────────────────────────────────────────────
    object About {
        const val BUTTON = "About Llamatik Code"
        const val DIALOG_TITLE = "About"
        const val TOOL_NAME = "Llamatik Code"
        const val DEVELOPED_BY = "Developed by Llamatik AI"
        const val POWERED_BY = "Powered by Llamatik and llama.cpp"
        const val COPYRIGHT = "Copyright 2026"
        const val DISCORD = "Join Discord"
        const val DISCORD_URL = "https://discord.gg/bNpzawZqq"
        const val X_TWITTER = "Follow on X"
        const val X_TWITTER_URL = "https://twitter.com/LlamatikAI"
    }

    // ── Multimodal / image attachment ─────────────────────────────────────────
    object Multimodal {
        const val ATTACH_IMAGE_TOOLTIP = "Attach image"
        const val REMOVE_IMAGE_TOOLTIP = "Remove"
        const val FILE_CHOOSER_TITLE = "Select Image"
        const val FILE_FILTER_DESC = "Image files (PNG, JPG, BMP, WebP, GIF)"
        const val UPGRADE_MESSAGE =
            "Image attachments require a Llamatik Code Pro licence. Upgrade to attach images and use multimodal models."
        const val NO_VLM_CONFIGURED =
            "Multimodal inference requires a vision model (VLM). Configure the model and mmproj paths in Settings → Llamatik Code."
        const val IMAGES_LABEL = "Attached images"
        const val MMPROJ_PATH_LABEL = "Multimodal projector (mmproj) path:"
        const val MMPROJ_PATH_TOOLTIP =
            "Path to the companion .gguf mmproj file required for vision/multimodal inference"
    }

    // ── Trial / Licensing ─────────────────────────────────────────────────────
    object Trial {
        const val STATUS_LICENSED = "Licensed"
        const val STATUS_ACTIVE = "Trial active"
        const val STATUS_FREE = "Free"
        const val REQUEST_LICENSE_MESSAGE = "Upgrade to Llamatik Code Pro to unlock advanced features."
    }

    // ── External Knowledge ────────────────────────────────────────────────
    object ExternalKnowledge {
        const val SECTION_TITLE = "<html><b>External Knowledge</b></html>"
        const val ENABLE_RESEARCH = "Enable external research (searches docs and web)"
        const val SEARCH_PROVIDER = "Search provider:"
        const val PROVIDER_DEFAULT = "DuckDuckGo (default)"
        const val AUTO_USE = "Allow agent to use web automatically"
        const val AUTO_USE_PRO_ONLY = "Auto-use requires Pro"
        const val PREFERRED_SOURCES = "Preferred sources (domains, comma-separated):"
        const val BLOCKED_DOMAINS = "Blocked domains (comma-separated):"
        const val SHOW_CITATIONS = "Always show sources and citations"
        const val DAILY_LIMIT = "Daily search limit:"
        const val PAGES_PER_TASK = "Pages per research task:"
        const val SECTION_DOCS_SOURCES = "<html><b>Docs Sources</b></html>"
        const val ADD_DOCS_SOURCE = "Add docs source"
        const val REMOVE_DOCS_SOURCE = "Remove selected"
        const val REFRESH_DOCS_SOURCE = "Re-index selected"
        const val DOCS_SOURCE_LIMIT_FREE = "Free plan: 1 docs source. Upgrade to add more."
        const val PRIVACY_NOTICE = "External research sends queries and URLs to the configured search provider. No source files are sent."
        const val DISABLED_MSG = "External research is disabled. Enable it in Settings → Llamatik Code → External Knowledge."
        const val DAILY_LIMIT_MSG = "Daily search limit reached. Resets tomorrow."
        const val SEARCH_DOCS_WEB_ACTION = "Search Docs & Web"
        fun dailyLimitStatus(used: Int, max: Int) = "Searches today: $used / $max"
        fun pagesPerTaskLabel(n: Int) = "Up to $n pages per research task"
        const val CONFIDENCE_HIGH = "Official docs (high confidence)"
        const val CONFIDENCE_MEDIUM = "Community/maintainer (medium confidence)"
        const val CONFIDENCE_LOW = "Community (lower confidence)"
        const val SOURCES_HEADER = "Sources:"
        const val NO_RELIABLE_SOURCE = "No reliable source found for this claim."
        const val PRO_REQUIRED_AUTO_USE = "Requires Pro subscription"
    }

    // ── MCP ───────────────────────────────────────────────────────────────────
    object Mcp {
        const val SETTINGS_PAGE_NAME = "MCP Servers"
        const val SECTION_MCP_SERVERS = "Configured MCP servers"
        const val ADD_SERVER = "Add server"
        const val EDIT_SERVER = "Edit server"
        const val REMOVE_SERVER = "Remove server"
        const val TEST_CONNECTION = "Test connection"
        const val REFRESH_TOOLS = "Refresh tools"
        const val VIEW_LOGS = "View logs"
        const val TESTING = "Testing…"
        const val FREE_TIER_NOTE = "Free plan: only one active server allowed. Upgrade to Pro for multiple servers and auto-approval."
        const val AUTO_APPROVAL_PRO_REQUIRED = "Auto-approval rules require Pro."
        const val SECURITY_NOTE = "MCP servers are local processes but may access external services."
        const val FIELD_NAME = "Name:"
        const val FIELD_COMMAND = "Command:"
        const val FIELD_ARGS = "Arguments (one per line):"
        const val FIELD_ENV = "Environment variables (KEY=value, one per line):"
        const val FIELD_WORKING_DIR = "Working directory (optional):"
        const val FIELD_TRUST_LEVEL = "Trust level:"
        const val ENABLED = "Enabled (active server)"
        const val AUTO_START = "Auto-start when project opens"
        const val ARGS_TOOLTIP = "One argument per line, e.g. --port\n8080"
        const val ENV_TOOLTIP = "One variable per line, e.g. API_KEY=abc123"
        const val VALIDATION_NAME_REQUIRED = "Server name is required."
        const val VALIDATION_COMMAND_REQUIRED = "Command is required."
        const val ERROR_SAVE_TITLE = "Could not save MCP server"
        const val TEST_SUCCESS_TITLE = "MCP connection successful"
        const val TEST_FAILURE_TITLE = "MCP connection failed"
        fun testSuccessMsg(tools: Int, resources: Int, prompts: Int) =
            "Connected successfully.\nDiscovered: $tools tool(s), $resources resource(s), $prompts prompt(s)."
        fun testFailureMsg(error: String) = "Could not connect to server:\n$error"
        fun confirmRemoveMsg(name: String) = "Remove MCP server '$name'?"
        fun logsTitle(name: String) = "Logs: $name"

        // Approval dialog
        const val APPROVAL_TITLE = "MCP Tool Approval Required"
        const val APPROVAL_ALLOW_ONCE = "Allow once"
        const val APPROVAL_DENY = "Deny"
        const val APPROVAL_ALLOW_ALWAYS_TOOL = "Allow always for this tool (Pro)"
        const val APPROVAL_ALLOW_ALWAYS_SERVER = "Allow always for this server (Pro)"
        const val APPROVAL_PRO_HINT = "Auto-approval requires Pro."
    }

    // ── Review / Feedback ─────────────────────────────────────────────────────
    object ReviewFeedback {
        const val REVIEW_DIALOG_TITLE = "Enjoying Llamatik Code?"
        const val REVIEW_MESSAGE = "Are you enjoying the plugin? Help us by making a review!"
        const val REVIEW_BUTTON = "Leave a Review ⭐"
        const val REVIEW_URL = "https://plugins.jetbrains.com/plugin/31304-llamatik-code/reviews"
        const val REVIEW_LATER = "Maybe Later"
        const val REVIEW_NO_THANKS = "No, Thanks"
        const val FEEDBACK_BUTTON = "Give Feedback"
        const val FEEDBACK_URL = "https://github.com/Llamatik-AI/Llamatik-Code-Issue-Tracker/issues"
    }

    // ── Code Health Monitor ───────────────────────────────────────────────────
    object CodeHealth {
        const val FEATURE_NAME = "Code Health Monitor"
        const val DISABLED_MSG =
            "Code Health Monitor is not enabled. Enable it in Settings → Llamatik Code → Code Health Monitor."
        const val ENABLED_LABEL = "Enable Code Health Monitor"
        const val MODE_LABEL = "Analysis mode"
        const val RUN_ON_LABEL = "Run analysis"
        const val REFRESH = "Refresh"
        const val INSIGHTS_EMPTY = "No insights yet. Run an analysis to get started."
        const val EXPLAIN = "Explain"
        const val CREATE_FIX_PLAN = "Create Fix Plan"
        const val IGNORE = "Ignore"
        const val PRO_REQUIRED = "Pro subscription required for this mode"
        const val BEFORE_COMMIT_TITLE = "Before-Commit Review"
        const val WATCH_ADD = "Watch This Area"
        const val WATCH_REMOVE = "Stop Watching"
        const val DASHBOARD_TITLE = "Code Health"
        const val HIGH_PRIORITY = "High Priority Insights"
        const val MISSING_TESTS_SECTION = "Missing Tests"
        const val COMPLEXITY_SECTION = "Complexity Hotspots"
        const val REGRESSION_SECTION = "Regression Risks"
        const val WATCHED_SECTION = "Watched Areas"
        const val LAST_ANALYSIS = "Last analysis"
        const val NEVER_RUN = "Never run"
    }
}
