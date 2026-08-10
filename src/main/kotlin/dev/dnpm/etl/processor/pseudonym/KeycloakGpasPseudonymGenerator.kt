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
 * this generator chains two gPAS domains, but the two results serve two *different* purposes:
 *
 * 1. FallnummerMV -> gPAS domain 'arbeitsnummer' -> Arbeitsnummer (stable per patient) via
 *    [GpasSoapService.getPseudonymFor] - looks up only, does *not* create. If the Arbeitsnummer
 *    was never registered for this case elsewhere, this fails loudly rather than silently
 *    creating one - verified against a working reference implementation; if that assumption
 *    turns out wrong in practice (i.e. Arbeitsnummer *should* be auto-created here), switch
 *    this call to [GpasSoapService.getOrCreatePseudonymFor] instead. **This is the PatID
 *    pseudonym** ([generate]) - stable across resubmissions of the same patient, which is what
 *    dedup/submission-type detection in `RequestProcessor` (keyed off `patientPseudonym`)
 *    requires.
 * 2. Arbeitsnummer -> gPAS domain 'vorgangsnummer' -> Vorgangsnummer via
 *    [GpasSoapService.createPseudonymFor] - creates a fresh one every time, i.e. every
 *    processing run gets its own Vorgangsnummer rather than reusing one across resubmissions.
 *    **This is the genomDE transfer TAN**, never the PatID pseudonym - see [generateVorgangsnummer]
 *    and [PseudonymizeService.genomDeTan]; a fresh, per-submission value is exactly what a
 *    transfer TAN needs, and it is still traceable back to the patient via the Arbeitsnummer it
 *    was created from.
 *
 * The incoming id is expected to be purely numeric (the FallnummerMV/case id as delivered by
 * Onkostar); this is *not* a mandatory field in Onkostar's "DNPM Klinik/Anamnese" form, so an
 * empty or non-numeric value is treated as a hard, clearly-reported error rather than silently
 * producing a bogus pseudonym or throwing an opaque NPE deep inside the SOAP call.
 */
class KeycloakGpasPseudonymGenerator(
    private val keycloakCfg: GpasKeycloakConfigProperties,
    private val retryTemplate: RetryTemplate,
    private val gpasSoapService: GpasSoapService,
) : Generator {
    private val logger = LoggerFactory.getLogger(KeycloakGpasPseudonymGenerator::class.java)

    /** Resolves the Arbeitsnummer for [id] - this is the PatID pseudonym, stable per patient. */
    override fun generate(id: String): String =
        retryTemplate.execute<String, Exception> {
            val caseId = requireNumericCaseId(id)
            val arbeitsnummer =
                gpasSoapService.getPseudonymFor(caseId, keycloakCfg.arbeitsnummerDomain)
            logger.debug("Resolved PatID pseudonym via arbeitsnummer domain")
            arbeitsnummer
        }

    /**
     * Not used - [PseudonymizeService.genomDeTan] special-cases [KeycloakGpasPseudonymGenerator]
     * and calls [generateVorgangsnummer] with the already-resolved Arbeitsnummer instead, to
     * avoid resolving it twice (see the class KDoc). Throws rather than silently falling back to
     * upstream's separate, unconfigured genomDE-TAN domain if this is ever invoked directly.
     */
    override fun generateGenomDeTan(id: String): String =
        throw UnsupportedOperationException(
            "KeycloakGpasPseudonymGenerator does not generate a genomDE transfer TAN from a raw id - " +
                "PseudonymizeService.genomDeTan() calls generateVorgangsnummer() with the Arbeitsnummer instead",
        )

    /**
     * Creates a fresh Vorgangsnummer for the given, already-resolved [arbeitsnummer] - this is
     * the genomDE transfer TAN, not the PatID pseudonym (see the class KDoc).
     */
    fun generateVorgangsnummer(arbeitsnummer: String): String =
        retryTemplate.execute<String, Exception> {
            gpasSoapService.createPseudonymFor(arbeitsnummer, keycloakCfg.vorgangsnummerDomain)
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
