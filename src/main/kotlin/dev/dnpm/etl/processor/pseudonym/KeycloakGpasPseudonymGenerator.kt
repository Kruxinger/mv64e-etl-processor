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

import dev.dnpm.etl.processor.config.GpasKeycloakConfigProperties
import org.slf4j.LoggerFactory
import org.springframework.retry.support.RetryTemplate

/**
 * Two-step, Keycloak-secured gPAS pseudonymization used at LMU.
 *
 * Unlike upstream's [GpasSoapPseudonymGenerator] (single gPAS domain call per pseudonym),
 * this generator chains two gPAS domains to arrive at the final PatID pseudonym:
 *
 * 1. FallnummerMV -> gPAS domain 'arbeitsnummer' -> Arbeitsnummer (stable per patient) via
 *    [GpasSoapService.getPseudonymFor] - looks up only, does *not* create. If the Arbeitsnummer
 *    was never registered for this case elsewhere, this fails loudly rather than silently
 *    creating one - verified against a working reference implementation; if that assumption
 *    turns out wrong in practice (i.e. Arbeitsnummer *should* be auto-created here), switch
 *    this call to [GpasSoapService.getOrCreatePseudonymFor] instead.
 * 2. Arbeitsnummer -> gPAS domain 'vorgangsnummer' -> Vorgangsnummer (final pseudonym) via
 *    [GpasSoapService.createPseudonymFor] - creates a fresh one every time, i.e. every
 *    processing run gets its own Vorgangsnummer rather than reusing one across resubmissions.
 *
 * The incoming id is expected to be purely numeric (the FallnummerMV/case id as delivered by
 * Onkostar); this is *not* a mandatory field in Onkostar's "DNPM Klinik/Anamnese" form, so an
 * empty or non-numeric value is treated as a hard, clearly-reported error rather than silently
 * producing a bogus pseudonym or throwing an opaque NPE deep inside the SOAP call.
 *
 * The Vorgangsnummer produced by [generate] is also reused as the genomDE transfer TAN - see
 * [generateGenomDeTan] and [PseudonymizeService.genomDeTan] - instead of requesting a second,
 * separate pseudonym from upstream's
 * [dev.dnpm.etl.processor.config.GPasConfigProperties.genomDeTanDomain] multi-pseudonym domain:
 * it is already exactly what a transfer TAN needs (fresh per submission, traceable back to the
 * patient via the 'arbeitsnummer'), and LMU's gPAS instance has no domain provisioned for that
 * separate upstream mechanism.
 */
class KeycloakGpasPseudonymGenerator(
    private val keycloakCfg: GpasKeycloakConfigProperties,
    private val retryTemplate: RetryTemplate,
    private val gpasSoapService: GpasSoapService,
) : Generator {
    private val logger = LoggerFactory.getLogger(KeycloakGpasPseudonymGenerator::class.java)

    override fun generate(id: String): String =
        retryTemplate.execute<String, Exception> {
            val caseId = requireNumericCaseId(id)
            val arbeitsnummer =
                gpasSoapService.getPseudonymFor(caseId, keycloakCfg.arbeitsnummerDomain)
            val vorgangsnummer =
                gpasSoapService.createPseudonymFor(
                    arbeitsnummer,
                    keycloakCfg.vorgangsnummerDomain,
                )
            logger.debug("Resolved PatID pseudonym via arbeitsnummer/vorgangsnummer chain")
            vorgangsnummer
        }

    /**
     * Not used - [PseudonymizeService.genomDeTan] special-cases [KeycloakGpasPseudonymGenerator]
     * and reuses the Vorgangsnummer from [generate] as the transfer TAN instead (see the class
     * KDoc). Throws rather than silently falling back to upstream's separate, unconfigured
     * genomDE-TAN domain if this is ever invoked directly.
     */
    override fun generateGenomDeTan(id: String): String =
        throw UnsupportedOperationException(
            "KeycloakGpasPseudonymGenerator does not generate a separate genomDE transfer TAN - " +
                "PseudonymizeService.genomDeTan() reuses the Vorgangsnummer from generate() instead",
        )

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
