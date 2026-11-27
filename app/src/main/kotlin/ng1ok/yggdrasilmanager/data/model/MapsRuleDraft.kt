package ng1ok.yggdrasilmanager.data.model

/** Text-backed policy input kept independent of Compose and Android types. */
data class MapsRuleDraft(
    val action: Int,
    val matchKind: Int,
    val pattern: String = "",
    val startAddress: String = "",
    val endAddress: String = "",
    val matchPerms: String = "",
    val setPerms: String = "",
)
