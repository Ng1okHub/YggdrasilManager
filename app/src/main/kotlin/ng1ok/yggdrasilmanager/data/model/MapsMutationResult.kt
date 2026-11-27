package ng1ok.yggdrasilmanager.data.model

/**
 * A successful policy change with the outcome of its subsequent [readback].
 * A failed readback does not undo the change and must not prompt a mutation retry.
 */
internal data class MapsMutationResult(val readback: Result<MapsScope>)
