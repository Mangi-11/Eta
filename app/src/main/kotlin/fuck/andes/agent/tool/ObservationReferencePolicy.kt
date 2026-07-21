package fuck.andes.agent.tool

internal object ObservationReferencePolicy {
    enum class Status {
        MATCH,
        NO_OBSERVATION,
        ID_REQUIRED,
        STALE,
    }

    fun validate(currentId: String?, requestedId: String?): Status = when {
        currentId == null -> Status.NO_OBSERVATION
        requestedId.isNullOrBlank() -> Status.ID_REQUIRED
        requestedId != currentId -> Status.STALE
        else -> Status.MATCH
    }
}
