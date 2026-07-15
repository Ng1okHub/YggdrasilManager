package ng1ok.yggdrasilmanager.data.hidedbg

data class HideDbgConfig(
    val globalEnabled: Boolean = false,
    val enabledUids: Set<Int> = emptySet()
)

interface HideDbgConfigStore {
    val current: HideDbgConfig
    val loadWarning: String?

    suspend fun save(config: HideDbgConfig): Result<Unit>
}
