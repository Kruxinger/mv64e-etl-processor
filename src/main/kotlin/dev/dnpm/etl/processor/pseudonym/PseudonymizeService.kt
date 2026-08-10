/*
 * This file is part of ETL-Processor
 *
 * Copyright (c) 2023       Comprehensive Cancer Center Mainfranken
 * Copyright (c) 2023-2026  Paul-Christian Volkmer, Datenintegrationszentrum Philipps-Universität Marburg and Contributors
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

import dev.dnpm.etl.processor.PatientId
import dev.dnpm.etl.processor.PatientPseudonym
import dev.dnpm.etl.processor.config.PseudonymizeConfigProperties

class PseudonymizeService(
    private val generator: Generator,
    private val configProperties: PseudonymizeConfigProperties,
) {
    fun patientPseudonym(patientId: PatientId): PatientPseudonym =
        when (generator) {
            // LMU fork: KeycloakGpasPseudonymGenerator.generate() resolves the Arbeitsnummer,
            // which is already gPAS's own, final pseudonym (see its KDoc) - like
            // GpasPseudonymGenerator, it must not be prefixed again, otherwise DNPM:DIP would
            // receive a value gPAS never issued.
            is GpasPseudonymGenerator,
            is KeycloakGpasPseudonymGenerator,
            -> PatientPseudonym(generator.generate(patientId.value))
            else ->
                PatientPseudonym("${configProperties.prefix}_${generator.generate(patientId.value)}")
        }

    fun genomDeTan(
        patientId: PatientId,
        patientPseudonym: PatientPseudonym,
    ): String =
        when (generator) {
            // LMU fork: the genomDE transfer TAN for KeycloakGpasPseudonymGenerator is a fresh
            // Vorgangsnummer created from the already-resolved Arbeitsnummer (= patientPseudonym)
            // instead of minting a second, unrelated pseudonym from the separate multi-pseudonym
            // domain (APP_PSEUDONYMIZE_GPAS_GENOM_DE_TAN_DOMAIN, upstream default "ccdn") that
            // this two-step chain has no use for and that isn't provisioned at LMU.
            is KeycloakGpasPseudonymGenerator -> generator.generateVorgangsnummer(patientPseudonym.value)
            else -> generator.generateGenomDeTan(patientId.value)
        }

    fun prefix(): String = configProperties.prefix
}
