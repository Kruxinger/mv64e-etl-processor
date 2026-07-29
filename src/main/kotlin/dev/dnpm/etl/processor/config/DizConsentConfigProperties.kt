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

package dev.dnpm.etl.processor.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Config for the LMU Broad-Consent lookup against DIZ, which fronts a gICS instance with a
 * Keycloak-secured FHIR search endpoint (`GET [uri]/Consent?...`, Bearer auth) instead of
 * upstream's Basic-Auth-secured gICS ([GIcsConfigProperties]).
 *
 * Field names/defaults mirror [GIcsConfigProperties] deliberately, since the FHIR search
 * request shape and the MII Broad Consent policy codes are the same - only transport-level
 * auth and the base URI differ.
 */
@ConfigurationProperties(DizConsentConfigProperties.NAME)
data class DizConsentConfigProperties
    @JvmOverloads
    constructor(
        /** Base URL of the DIZ consent FHIR endpoint */
        val uri: String?,
        val personIdentifierSystem: String =
            "https://ths-greifswald.de/fhir/gics/identifiers/Patienten-ID",
        val broadConsentDomainName: String = "MII",
        val broadConsentPolicyCode: String = "2.16.840.1.113883.3.1937.777.24.5.3.6",
        val broadConsentPolicySystem: String = "urn:oid:2.16.840.1.113883.3.1937.777.24.5.3",
        val broadConsentPolicyUri: String = "urn:oid:2.16.840.1.113883.3.1937.777.24.2.1790",
        val keycloakTokenUri: String? = null,
        val keycloakClientId: String? = null,
        val keycloakClientSecret: String? = null,
    ) {
        companion object {
            const val NAME = "app.consent.diz"
        }
    }
