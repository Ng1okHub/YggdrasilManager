package ng1ok.yggdrasilmanager.data.model

/** A single maps presentation rule accepted by Yggdrasil's maps policy ABI. */
data class MapsRule(
    val action: Int,
    val matchKind: Int,
    val matchPerms: String? = null,
    val setPerms: String? = null,
    val addrStart: Long = 0L,
    val addrEnd: Long = 0L,
    val pattern: String = "",
) {
    val matchesPermissions: Boolean
        get() = matchPerms != null
}

/** The rules returned for one global or per-process scope. */
data class MapsScope(
    val global: Boolean,
    val rules: List<MapsRule>,
    val totalRules: Int = rules.size,
)

/** ABI constants used to construct and display maps policy rules. */
object MapsPolicy {
    const val ACTION_HIDE = 1
    const val ACTION_REWRITE_PERMS = 2

    const val MATCH_ANY = 0
    const val MATCH_PATH_SUBSTR = 1
    const val MATCH_PATH_PREFIX = 2
    const val MATCH_PATH_EXACT = 3
    const val MATCH_PATH_BASENAME = 4
    const val MATCH_ADDR_RANGE = 5

    const val RULE_F_MATCH_PERMS = 1
    const val MAX_RULES = 32
    const val MAX_PATTERN_LENGTH = 127
}
