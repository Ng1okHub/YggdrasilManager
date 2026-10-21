package ng1ok.yggdrasilmanager.data.selftest

/**
 * One section of the bundled Yggdrasil KPM self-test.
 *
 * [id] is the exact section name printed by yggdrasil_test.cpp and the value
 * passed to its argv[1] substring filter, so the Manager can run sections
 * individually.
 */
enum class SelfTestFeature(
    val id: String,
    val label: String,
    val description: String,
    val needsRoot: Boolean,
) {
    Core("core", "核心", "模組可用性與版本 ABI", false),
    Memfd("memfd", "Memfd", "具名 memfd 建立與旗標驗證", false),
    Merge("merge", "VMA 合併", "合併 /proc maps 檢視", false),
    Maps("maps", "Maps 政策", "隱藏或改寫記憶體映射", false),
    Dbg("dbg", "Hide DBG", "停止與追蹤狀態改寫", false),
    Ptrace("ptrace", "Ptrace 偽裝", "硬體除錯 regset 偽裝", false),
    Label("label", "SELinux 標籤", "標籤偽裝與查詢機制", false),
    Hwbp("hwbp", "硬體斷點", "附加、取代與事件觀察", false),
    Syscall("syscall", "系統呼叫", "攔截、改寫與引數擷取", true),
    Stramp("stramp", "Syscall Trampoline", "使用者空間 trampoline", true),
    Freeze("freeze", "執行緒凍結", "凍結與解凍執行緒群組", true),
    ;

    companion object {
        fun fromId(id: String): SelfTestFeature? = entries.firstOrNull { it.id == id }
    }
}
