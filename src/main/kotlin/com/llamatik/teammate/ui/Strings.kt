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
        const val CLOSE = "Close"
        const val VIEW_DIFF = "View Diff"
        const val APPLY_EDIT = "Apply Edit"
        const val NO_MODEL_CONFIGURED_MSG =
            "No model is configured. Go to Settings → Llamatik Code and set a model path."
        const val NO_MODEL_CONFIGURED_TITLE = "No model loaded"
        const val TRIAL_EXPIRED_MSG = "Trial expired — Agent requires a subscription."
        fun trialDaysLeft(days: Int) = "$days days left in trial. Agent features will be disabled after expiry."
    }

    // ── Composer / tool window ────────────────────────────────────────────────
    object Composer {
        const val SEND_TOOLTIP = "Send"
        const val STOP_TOOLTIP = "Stop generation"
        const val CONTEXT_PILL = "Context (1)"
        const val PROFILE_PILL = "Default"
        const val SETTINGS_PILL = "⚙"
        const val INPUT_TOOLTIP_DEFAULT =
            "Ask AI, use @filename to attach source files, use @prompt to recall saved prompts"
        const val INPUT_PLACEHOLDER = "Use @filename to attach source files"
        const val INPUT_TOOLTIP_AGENT = "Describe a task for the agent to inspect, plan, and execute"
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
        const val HERO_TITLE = "Agent"
        const val HERO_SUBTITLE =
            "I can inspect the codebase, plan a fix, and apply changes with approval."
        const val WORKFLOW_TITLE = "Agent workflow"
        const val WORKFLOW_BODY =
            "Analyze files, call tools, stream progress, preview diffs, and wait for your approval before applying edits."
        const val PROMPTS_TITLE = "Prompts to try"
        const val APPROVE = "Approve all changes"
        const val REJECT = "Reject"
        const val THINKING_COLLAPSED = "▶ Thinking"
        const val THINKING_EXPANDED = "▼ Thinking"
        const val BACK = "← Back"
        const val STATUS_ANALYZING = "Analyzing…"
        const val TASK_HEADER = "Agent task"
        const val OVERLAY_TRIAL_EXPIRED = "Trial expired — Agent requires a subscription."
        const val OVERLAY_NO_MODEL = "Configure a model to use Agent mode."
        const val OVERLAY_LOADING_MODEL = "Loading model…"
        const val REASONING_STREAMING = "▼ Reasoning…"
        const val REASONING_PREFIX_COLLAPSED = "▶ Reasoned for "
        const val REASONING_PREFIX_EXPANDED = "▼ Reasoned for "
        const val TOOL_CALL_ICON = "⚙"
        const val AUTO_APPROVE_NEXT = "Auto-apply edits on next run"
        const val AUTO_BUILD_NEXT = "Auto-build after applying edits"
        const val AUTO_FIX_NEXT = "Auto-fix build errors"
        const val SCROLL_TO_BOTTOM = "↓  Scroll to bottom"
        fun brewedFor(elapsed: String) = "brewed for $elapsed"

        val SUGGESTIONS = listOf(
            "Find and resolve build errors in my project",
            "Suggest cleaner structure and apply changes with approval",
            "Analyze the current file and propose a safer refactor",
            "Generate tests, show the diff, and let me approve",
            "Make a code review, suggest improvements and apply them"
        )
    }

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
        const val USE_SELECTED = "Use selected"
        const val USE_SELECTED_TOOLTIP = "Set the selected model as active model path"
        const val DELETE_SELECTED = "Delete selected"
        const val DELETE_SELECTED_TOOLTIP = "Remove the selected model from the registry"
        const val BROWSE_CATALOG = "Browse model catalog…"
        const val MODEL_LIBRARY_TITLE = "Llamatik Code — Model Library"
        const val RESET_ONBOARDING = "Reset onboarding"
        const val RESET_ONBOARDING_TOOLTIP =
            "Shows the welcome dialog again next time the tool window is opened"
        const val SECTION_ACTIVE_MODEL = "<html><b>Active model path</b></html>"
        const val SECTION_REGISTERED_MODELS = "<html><b>Registered models</b></html>"
        const val SECTION_BEHAVIOUR = "<html><b>Behaviour</b></html>"
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
        const val MAX_TOKENS           = "Max tokens:"
        const val MAX_TOKENS_TOOLTIP   = "Maximum number of tokens to generate per response"
        const val CONTEXT_SIZE         = "Context size (tokens):"
        const val CONTEXT_SIZE_TOOLTIP = "KV cache size — larger allows longer conversations but uses more VRAM"
        const val BATCH_SIZE           = "Batch size:"
        const val BATCH_SIZE_TOOLTIP   = "Prompt evaluation batch size — must be ≥ prompt token count"
        const val NUM_THREADS          = "CPU threads:"
        const val NUM_THREADS_TOOLTIP  = "Number of CPU threads used for inference"
        const val USE_MMAP             = "Use memory-mapped I/O (mmap)"
        const val USE_MMAP_TOOLTIP     = "Load model weights via mmap — faster startup, shares memory across processes"
        const val FLASH_ATTENTION      = "Enable Flash Attention"
        const val FLASH_ATTENTION_TOOLTIP = "Use Flash Attention kernel — reduces VRAM usage on supported hardware"
        const val RESET_DEFAULTS       = "Reset to defaults"
        const val UPGRADE_REQUIRED     = "Inference settings require a Llamatik Code licence. Upgrade to change these settings."
        const val UPGRADE_BUTTON       = "Upgrade"
    }

    // ── Onboarding ────────────────────────────────────────────────────────────
    object Onboarding {
        const val DIALOG_TITLE = "Welcome to Llamatik Code"
        const val STEP1_HEADING = "Local-first AI coding assistant"
        const val STEP1_BODY1 =
            "Llamatik Code runs <b>entirely on your device</b>. No code, prompts, or results ever leave your machine — no internet connection required after initial setup."
        const val STEP1_BODY2 =
            "Powered by local GGUF models via the Llamatik runtime library. You choose which model to use — from lightweight quick helpers to large reasoning models."
        const val STEP1_BODY3 = "<b>Privacy guarantee:</b> Your code stays on your hardware, always."
        const val STEP1_NEXT = "Next: Choose a model"
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
        const val STEP3_HEADING = "You're all set!"
        const val STEP3_BODY =
            "Your model selection has been noted. Automatic downloads will be available in a future release.<br><br>For now, download your chosen GGUF model manually and register it via <b>Settings &gt; Llamatik Code &gt; Model path</b>, or use the <b>Load model from file</b> option in the tool window."
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
            "Llamatik native library could not be loaded: $err\n\nEnsure the bundled native libraries are present and that macOS can load their dependencies."
        fun errorNativeLibCause(msg: String?) =
            "Llamatik native library could not be loaded.\n$msg\n\nEnsure the bundled native libraries are available for your current platform."
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
        const val RETRY_LIMIT = "Auto-fix limit reached — please fix manually"
    }

    // ── About ─────────────────────────────────────────────────────────────────
    object About {
        const val BUTTON = "About Llamatik Code"
        const val DIALOG_TITLE = "About"
        const val TOOL_NAME = "Llamatik Code"
        const val DEVELOPED_BY = "Developed by Llamatik AI"
        const val POWERED_BY = "Powered by Llamatik and llama.cpp"
        const val COPYRIGHT = "Copyright 2026"
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
        const val STATUS_EXPIRED = "Trial expired"
        const val REQUEST_LICENSE_MESSAGE = "Upgrade to Llamatik Code Pro to unlock Agent mode and advanced features."
    }
}
