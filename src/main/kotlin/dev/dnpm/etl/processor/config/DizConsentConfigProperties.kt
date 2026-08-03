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
 * Config for the LMU Broad-Consent lookup against DIZ.
 *
 * Verified against the real system (see handoff notes / PoC): DIZ's endpoint is a plain
 * `GET [uri]<patientId>` (patient id appended directly, no FHIR search params) - so [uri] is
 * expected to already include everything up to the point where the id gets appended, e.g.
 * `https://bc.diz.med.uni-muenchen.de/fhir/Consent?patient=`. This differs from Pauls's
 * upstream [GIcsGetBroadConsentService][dev.dnpm.etl.processor.consent.GicsGetBroadConsentService],
 * which does a real FHIR search - only the *response* shape (MII Broad Consent Bundle) and the
 * resulting policy codes are the same, hence [broadConsentPolicyCode] etc. still mirroring
 * [GIcsConfigProperties]'s field names/defaults.
 *
 * Auth also differs from plain client-credentials: DIZ's Keycloak realm requires the
 * resource-owner-password grant, i.e. [keycloakUsername]/[keycloakPassword] of a service
 * account *in addition to* client id/secret - see [dev.dnpm.etl.processor.keycloak.KeycloakTokenProvider].
 */
@ConfigurationProperties(DizConsentConfigProperties.NAME)
data class DizConsentConfigProperties
    @JvmOverloads
    constructor(
        /** Everything up to (and usually including) the query param the patient id gets appended to,
         * e.g. `https://bc.diz.med.uni-muenchen.de/fhir/Consent?patient=` */
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
        /** Service-account username for DIZ's resource-owner-password Keycloak grant */
        val keycloakUsername: String? = null,
        /** Service-account password for DIZ's resource-owner-password Keycloak grant */
        val keycloakPassword: String? = null,
    ) {
        companion object {
            const val NAME = "app.consent.diz"
        }
    }
