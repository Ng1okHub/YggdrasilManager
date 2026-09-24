package ng1ok.yggdrasilmanager.data

import ng1ok.yggdrasilmanager.data.model.MapsRule
import ng1ok.yggdrasilmanager.data.model.MapsScope

/** Process boundary used by the maps policy repository. */
interface MapsOperations {
    /** Adds [rule] to the global scope or the selected process scope. */
    suspend fun addRule(pid: Int?, rule: MapsRule): Int
    /** Removes rules equal to [rule] from the selected scope. */
    suspend fun removeRule(pid: Int?, rule: MapsRule): Int
    /** Clears one scope, or all scopes when [allScopes] is true. */
    suspend fun clear(pid: Int?, allScopes: Boolean): Int
    /** Reads the current policy scope, returning null when the ABI query fails. */
    suspend fun get(pid: Int?): MapsScope?
}
