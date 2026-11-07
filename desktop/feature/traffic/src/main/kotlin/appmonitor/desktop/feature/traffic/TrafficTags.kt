package appmonitor.desktop.feature.traffic

const val REQUEST_LIST = "requests"

const val DETAIL_SEARCH = "detail-search"

// The detail bar's search icon, which shows or hides the search field.
const val DETAIL_SEARCH_OPEN = "detail-search-open"

const val DETAIL_URL = "detail-url"

const val DETAIL_META = "detail-meta"

const val MOCKED_BY = "mocked-by"

// The detail bar's "⋯" and the actions of its menu.
const val MORE_ACTIONS = "more-actions"

const val COPY_CURL_ACTION = "copy-curl-action"

const val SAVE_AS_ISSUE_ACTION = "save-as-issue-action"

const val DETAIL_MATCHES = "detail-matches"

const val NEXT_MATCH = "next-match"

const val PREVIOUS_MATCH = "previous-match"

const val DEVICES_TOGGLE = "devices-toggle"

const val DEVICES_DIVIDER = "devices-divider"

const val REQUESTS_DIVIDER = "requests-divider"

const val RUN_NAME = "run-name"

const val SEARCH = "search"

const val ISSUES = "issues"

const val MOCKS = "mocks"

const val BREAKPOINTS = "breakpoints"

const val BENCHMARKS = "benchmarks"

const val EXPORT_HAR = "export-har"

const val SAVE_AS_ISSUE = "save-as-issue"

const val MARK = "mark-"

const val MARKED_BAR = "marked-bar"

const val COMPARE_RUNS = "compare-runs"

const val MOCKED = "mocked-"

const val REPLAYED = "replayed-"

const val MOCK_THIS = "mock-this"

// A detail copy action's tag is this prefix and its DetailPart's name.
const val COPY = "copy-"

const val COPY_NOTE = "copy-note"

const val CURL = "curl"

const val SEND_AGAIN = "send-again"

const val REPLAY_NOTE = "replay-note"

const val OPEN_REPLAYED = "open-replayed"

const val SHOW_ORIGINAL = "show-original"

const val CONNECTION_BYTES = "connection-bytes"

const val CONFIRM_REPLAY = "confirm-replay"

const val REFUSE_REPLAY = "refuse-replay"

const val CLEAR = "clear"

const val RECORD = "record"

const val ALL_DEVICES = "all-devices"

const val SAVE = "save"

const val SAVE_NOTE = "save-note"

const val EXPORT_NOTE = "export-note"

const val SAVED_RUN = "saved-run"

const val SAVE_RUN_NAME = "save-run-name"

const val DISCONNECT = "disconnect-"

const val DROPS = "drops-"

const val RECONNECT = "reconnect-"

const val FILTERS = "filters"

// The title bar's "Actions ⌘K", the sidebar's gear and its runs (RUN is a prefix with the run's id).
const val ACTIONS = "actions"

// The palette (ADR-0206): an entry's tag is PALETTE and the tag of the button it stands for, REQUEST or DEVICE with an id.
const val PALETTE = "palette-"

const val PALETTE_OPEN = "palette-open"

const val REQUEST = "request-"

const val DEVICE = "device-"

const val SETTINGS = "settings"

const val SETTINGS_GEAR = "settings-gear"

const val LIVE_RUN = "live-run"

const val RUN = "run-"

const val RENAME_RUN = "rename-run"

const val STATUS_BAR = "status-bar"

// A status choice's tag is this prefix and the class's first code, `all` or `failed`, as `status-filter-400`.
const val STATUS_FILTER = "status-filter-"

// A host row's tag is this prefix and the host.
const val HOST_FILTER = "host-filter-"

// HOSTS' button that hides every host or shows them all again.
const val EVERY_HOST = "every-host"

// A title bar tool's tag is this prefix and its palette tag (`save`, `issues`…), ADR-0210.
const val TOOL = "tool-"

// The title bar's "⋯" holding the tools under a narrow window.
const val TOOLS_MENU = "tools-menu"
