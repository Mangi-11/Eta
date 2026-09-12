package io.github.mangi.eta.agent.roleplay

internal class CharacterCardException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
