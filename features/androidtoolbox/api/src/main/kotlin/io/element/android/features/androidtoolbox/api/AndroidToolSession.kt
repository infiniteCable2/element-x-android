/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * One authenticated conversation/device binding, supplied by the transport owner.
 * Operation IDs must come from that owner's idempotency contract, never from recognised speech.
 * Retain reservations until this binding ends: evicting old IDs would permit duplicate side effects.
 */
class AndroidToolSession(
    private val toolbox: AndroidToolbox,
    private val launcher: AndroidToolLauncher,
    private val currentGrants: suspend () -> Set<String>,
) {
    private val mutex = Mutex()
    private val operations = mutableMapOf<String, Operation>()

    suspend fun catalog(): List<AndroidToolDefinition> = toolbox.catalog(launcher, currentGrants())

    suspend fun execute(operationId: String, command: String, params: JsonObject): AndroidToolResult = mutex.withLock {
        if (operationId.isBlank()) return@withLock failure("An operation ID is required.")
        val previous = operations[operationId]
        if (previous != null) {
            return@withLock if (previous.command == command && previous.params == params) previous.result else failure("The operation ID was already used.")
        }
        if (operations.size >= MAX_OPERATIONS) return@withLock failure("This assistant session has reached its operation limit.")
        // Reserve before suspending. Cancellation after an Android handoff must never cause a second launch on retry.
        val operation = Operation(command, params, failure("The operation was interrupted; do not retry it with a new ID."))
        operations[operationId] = operation
        try {
            toolbox.execute(command, params, launcher, currentGrants).also { operation.result = it }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            operation.result
        }
    }

    private data class Operation(val command: String, val params: JsonObject, var result: AndroidToolResult)

    private fun failure(message: String) = AndroidToolResult.Failure(AndroidToolResult.Reason.EXECUTION_FAILED, message)

    private companion object {
        const val MAX_OPERATIONS = 128
    }
}
