package com.hermes.agent.util

import java.util.UUID

/**
 * Tiny id generator kept behind an object so tests can swap deterministic ids
 * in if needed.
 */
object IdGenerator {
    fun newId(): String = UUID.randomUUID().toString()
}
