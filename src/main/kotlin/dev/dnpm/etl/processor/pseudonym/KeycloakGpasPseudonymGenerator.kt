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

import dev.dnpm.etl.processor.config.GPasConfigProperties
import dev.dnpm.etl.processor.config.GpasKeycloakConfigProperties
import org.slf4j.LoggerFactory
import org.springframework.retry.support.RetryTemplate

/**
 * Two-step, Keycloak-secured gPAS pseudonymization used at LMU.
 *
 * Unlike upstream's [GpasSoapPseudonymGenerator] (single gPAS domain call per pseudonym),
 * this generator chains two gPAS domains to arrive at the final PatID pseudonym:
 *
 * 1. FallnummerMV -> gPAS domain 'arbeitsnummer' -> Arbeitsnummer (stable per patient)
 * 2. Arbeitsnummer -> gPAS domain 'vorgangsnummer' -> Vorgangsnummer (final pseudonym, sequential
 *    per Arbeitsnummer)
 *
 * The incoming id is expected to be purely numeric (the FallnummerMV/case id as delivered by
 * Onkostar); this is *not* a mandatory field in Onkostar's "DNPM Klinik/Anamnese" form, so an
 * empty or non-numeric value is treated as a hard, clearly-reported error rather than silently
 * producing a bogus pseudonym or throwing an opaque NPE deep inside the SOAP call.
 */
class KeycloakGpasPseudonymGenerator(
    private val gpasCfg: GPasConfigProperties,
    private val keycloakCfg: GpasKeycloakConfigProperties,
    private val retryTemplate: RetryTemplate,
    private val gpasSoapService: GpasSoapService,
) : Generator {
    private val logger = LoggerFactory.getLogger(KeycloakGpasPseudonymGenerator::class.java)

    override fun generate(id: String): String =
        retryTemplate.execute<String, Exception> {
            val caseId = requireNumericCaseId(id)
            val arbeitsnummer =
                gpasSoapService.getOrCreatePseudonymFor(caseId, keycloakCfg.arbeitsnummerDomain)
            val vorgangsnummer =
                gpasSoapService.getOrCreatePseudonymFor(
                    arbeitsnummer,
                    keycloakCfg.vorgangsnummerDomain,
                )
            logger.debug("Resolved PatID pseudonym via arbeitsnummer/vorgangsnummer chain")
            vorgangsnummer
        }

    override fun generateGenomDeTan(id: String): String =
        retryTemplate.execute<String, Exception> {
            gpasSoapService.createPseudonymsFor(id, gpasCfg.genomDeTanDomain, 1).first()
        }

    /**
     * gPAS expects the case id prefixed for the 'arbeitsnummer' domain (prefix added if not
     * already present) and requires it to be numeric.
     */
    private fun requireNumericCaseId(id: String): String {
        require(id.isNotBlank()) {
            "Cannot pseudonymize a blank/missing FallnummerMV - check the incoming Mtb file"
        }
        val prefixed =
            if (id.startsWith(keycloakCfg.arbeitsnummerPrefix)) {
                id
            } else {
                "${keycloakCfg.arbeitsnummerPrefix}$id"
            }
        require(prefixed.all { it.isDigit() }) {
            // deliberately not including the raw id value here - it's an unpseudonymized case
            // identifier and this message can end up in application logs
            "FallnummerMV is not numeric (length ${id.length}) - gPAS 'arbeitsnummer' domain requires a numeric case id"
        }
        return prefixed
    }
}
