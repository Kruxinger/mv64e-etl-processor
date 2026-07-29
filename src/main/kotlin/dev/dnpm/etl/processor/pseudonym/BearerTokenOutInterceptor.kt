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

package dev.dnpm.etl.processor.pseudonym

import dev.dnpm.etl.processor.keycloak.KeycloakTokenProvider
import org.apache.cxf.interceptor.Fault
import org.apache.cxf.message.Message
import org.apache.cxf.phase.AbstractPhaseInterceptor
import org.apache.cxf.phase.Phase

/**
 * Adds a Keycloak `Authorization: Bearer <token>` header to every outgoing CXF SOAP call. gPAS
 * itself sits behind a gateway that expects this header alongside the plain SOAP body - the SOAP
 * payload/WSDL contract is unchanged, only transport-level auth differs from upstream's
 * Basic-Auth-based [dev.dnpm.etl.processor.pseudonym.GpasSoapPseudonymGenerator].
 */
class BearerTokenOutInterceptor(
    private val tokenProvider: KeycloakTokenProvider,
) : AbstractPhaseInterceptor<Message>(Phase.PRE_STREAM) {
    @Suppress("UNCHECKED_CAST")
    override fun handleMessage(message: Message) {
        try {
            val headers =
                message[Message.PROTOCOL_HEADERS] as? MutableMap<String, MutableList<String>>
                    ?: HashMap<String, MutableList<String>>().also {
                        message[Message.PROTOCOL_HEADERS] = it
                    }
            headers["Authorization"] = mutableListOf("Bearer ${tokenProvider.getAccessToken()}")
        } catch (e: Exception) {
            throw Fault(e)
        }
    }
}
