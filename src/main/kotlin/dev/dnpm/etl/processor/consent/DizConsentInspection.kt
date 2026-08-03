/*
 * This file is part of ETL-Processor
 *
 * Copyright (c) 2026  LMU Klinikum, Datenintegrationszentrum
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.dnpm.etl.processor.consent

import dev.dnpm.etl.processor.monitoring.ConnectionCheckResult
import java.time.Instant
import reactor.core.publisher.Sinks

/**
 * Records the outcome of the most recent DIZ Broad-Consent lookup performed by
 * [KeycloakDizConsentService], so the monitoring UI can show not just whether DIZ is generally
 * reachable, but what the last actual per-patient check returned - reached? consent found? the
 * raw response? Useful for verifying the diz_keycloak request shape (see bindings/README.md,
 * examples/dev/testservice) against a mock before real DIZ access is available.
 *
 * Reuses the same [ConnectionCheckResult] stream as gPAS/gICS/output so it shows up in the
 * monitoring UI's SSE-driven config grid without a second event mechanism.
 *
 * @since LMU fork
 */
class DizConsentInspection(
    private val connectionCheckUpdateProducer: Sinks.Many<ConnectionCheckResult>,
) {

    @Volatile
    private var current: ConnectionCheckResult.DizConsentCheckResult =
        ConnectionCheckResult.DizConsentCheckResult(
            available = false,
            timestamp = Instant.now(),
            lastChange = Instant.now(),
            personIdentifierValue = null,
            consentFound = false,
            rawResponse = null,
        )

    fun record(
        reached: Boolean,
        personIdentifierValue: String,
        consentFound: Boolean,
        rawResponse: String?,
    ) {
        val now = Instant.now()
        current =
            ConnectionCheckResult.DizConsentCheckResult(
                available = reached,
                timestamp = now,
                lastChange = if (current.available == reached) current.lastChange else now,
                personIdentifierValue = personIdentifierValue,
                consentFound = consentFound,
                rawResponse = rawResponse,
            )
        connectionCheckUpdateProducer.emitNext(current, Sinks.EmitFailureHandler.FAIL_FAST)
    }

    fun current(): ConnectionCheckResult.DizConsentCheckResult = current
}
