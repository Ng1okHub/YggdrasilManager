package ng1ok.yggdrasilmanager.data

import ng1ok.yggdrasilmanager.data.model.MapsPolicy
import ng1ok.yggdrasilmanager.data.model.MapsRule
import ng1ok.yggdrasilmanager.data.model.MapsScope
import ng1ok.yggdrasilmanager.native.YggdrasilNativeApi

/** JNI-backed maps operations used by tests and non-root callers. */
internal class LocalMapsOperations(
    private val native: YggdrasilNativeApi,
) : MapsOperations {
    override suspend fun addRule(pid: Int?, rule: MapsRule): Int = native.mapsAddRule(
        hasPid = pid != null,
        pid = pid ?: 0,
        action = rule.action,
        matchKind = rule.matchKind,
        ruleFlags = if (rule.matchesPermissions) MapsPolicy.RULE_F_MATCH_PERMS else 0,
        matchPerms = rule.matchPerms.orEmpty(),
        setPerms = rule.setPerms.orEmpty(),
        addrStart = rule.addrStart,
        addrEnd = rule.addrEnd,
        pattern = rule.pattern,
    )

    override suspend fun removeRule(pid: Int?, rule: MapsRule): Int = native.mapsRemoveRule(
        hasPid = pid != null,
        pid = pid ?: 0,
        action = rule.action,
        matchKind = rule.matchKind,
        ruleFlags = if (rule.matchesPermissions) MapsPolicy.RULE_F_MATCH_PERMS else 0,
        matchPerms = rule.matchPerms.orEmpty(),
        setPerms = rule.setPerms.orEmpty(),
        addrStart = rule.addrStart,
        addrEnd = rule.addrEnd,
        pattern = rule.pattern,
    )

    override suspend fun clear(pid: Int?, allScopes: Boolean): Int = native.mapsClear(
        hasPid = pid != null,
        pid = pid ?: 0,
        allScopes = allScopes,
    )

    override suspend fun get(pid: Int?): MapsScope? = MapsDumpCodec.decode(native.mapsGet(
        hasPid = pid != null,
        pid = pid ?: 0,
    ))
}
