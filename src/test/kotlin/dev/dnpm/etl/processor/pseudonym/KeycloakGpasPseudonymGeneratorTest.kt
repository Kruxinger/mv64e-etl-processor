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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.retry.support.RetryTemplate

@ExtendWith(MockitoExtension::class)
class KeycloakGpasPseudonymGeneratorTest {
    private val gpasConfigProperties =
        GPasConfigProperties(
            uri = null,
            soapEndpoint = "http://localhost/gpas",
            pidDomain = null,
            username = null,
            password = null,
        )

    private val keycloakConfigProperties =
        GpasKeycloakConfigProperties(
            arbeitsnummerDomain = "arbeitsnummer",
            vorgangsnummerDomain = "vorgangsnummer",
            arbeitsnummerPrefix = "00",
        )

    private val retryTemplate = RetryTemplate.builder().maxAttempts(1).build()

    @Test
    fun shouldChainArbeitsnummerAndVorgangsnummer(
        @Mock gpasSoapService: GpasSoapService,
    ) {
        whenever(gpasSoapService.getOrCreatePseudonymFor("00123", "arbeitsnummer"))
            .thenReturn("ARB-1")
        whenever(gpasSoapService.getOrCreatePseudonymFor("ARB-1", "vorgangsnummer"))
            .thenReturn("VOR-1")

        val generator =
            KeycloakGpasPseudonymGenerator(
                gpasConfigProperties,
                keycloakConfigProperties,
                retryTemplate,
                gpasSoapService,
            )

        val result = generator.generate("123")

        assertThat(result).isEqualTo("VOR-1")
        verify(gpasSoapService).getOrCreatePseudonymFor("00123", "arbeitsnummer")
        verify(gpasSoapService).getOrCreatePseudonymFor("ARB-1", "vorgangsnummer")
    }

    @Test
    fun shouldNotDoublePrefixCaseIdThatAlreadyStartsWithPrefix(
        @Mock gpasSoapService: GpasSoapService,
    ) {
        whenever(gpasSoapService.getOrCreatePseudonymFor("00123", "arbeitsnummer"))
            .thenReturn("ARB-1")
        whenever(gpasSoapService.getOrCreatePseudonymFor("ARB-1", "vorgangsnummer"))
            .thenReturn("VOR-1")

        val generator =
            KeycloakGpasPseudonymGenerator(
                gpasConfigProperties,
                keycloakConfigProperties,
                retryTemplate,
                gpasSoapService,
            )

        generator.generate("00123")

        verify(gpasSoapService).getOrCreatePseudonymFor("00123", "arbeitsnummer")
    }

    @Test
    fun shouldRejectNonNumericCaseId(
        @Mock gpasSoapService: GpasSoapService,
    ) {
        val generator =
            KeycloakGpasPseudonymGenerator(
                gpasConfigProperties,
                keycloakConfigProperties,
                retryTemplate,
                gpasSoapService,
            )

        assertThatThrownBy { generator.generate("FALL-ABC") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun shouldRejectBlankCaseId(
        @Mock gpasSoapService: GpasSoapService,
    ) {
        val generator =
            KeycloakGpasPseudonymGenerator(
                gpasConfigProperties,
                keycloakConfigProperties,
                retryTemplate,
                gpasSoapService,
            )

        assertThatThrownBy { generator.generate("") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun shouldGenerateGenomDeTanIndependently(
        @Mock gpasSoapService: GpasSoapService,
    ) {
        whenever(gpasSoapService.createPseudonymsFor("123", "ccdn", 1)).thenReturn(listOf("TAN-1"))

        val generator =
            KeycloakGpasPseudonymGenerator(
                gpasConfigProperties,
                keycloakConfigProperties,
                retryTemplate,
                gpasSoapService,
            )

        val result = generator.generateGenomDeTan("123")

        assertThat(result).isEqualTo("TAN-1")
    }
}
