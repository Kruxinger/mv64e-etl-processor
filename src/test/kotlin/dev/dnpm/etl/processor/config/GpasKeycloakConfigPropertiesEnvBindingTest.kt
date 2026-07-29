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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * Verifies that env-var names as documented in examples/deploy/env-sample.lmu.env actually
 * bind to [GpasKeycloakConfigProperties] via Spring's relaxed binding - so a typo here is
 * caught by CI instead of silently producing a null config on the target system later.
 */
class GpasKeycloakConfigPropertiesEnvBindingTest {
    private fun binderFor(vararg env: Pair<String, String>): Binder {
        val environment = StandardEnvironment()
        // must be an actual SystemEnvironmentPropertySource (not just a MapPropertySource with
        // that name) - only that type gets the underscore/case relaxed env-var matching applied
        environment.propertySources.addFirst(
            SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                env.toMap(),
            ),
        )
        return Binder.get(environment)
    }

    @Test
    fun shouldBindGpasKeycloakConfigFromEnvVars() {
        val binder =
            binderFor(
                "APP_PSEUDONYMIZE_GPAS_KEYCLOAK_TOKENURI" to "http://kc/token",
                "APP_PSEUDONYMIZE_GPAS_KEYCLOAK_CLIENTID" to "etl-processor",
                "APP_PSEUDONYMIZE_GPAS_KEYCLOAK_CLIENTSECRET" to "secret",
                "APP_PSEUDONYMIZE_GPAS_SOAP_ENDPOINT" to "http://gpas/soap",
            )

        val keycloakCfg =
            binder
                .bind("app.pseudonymize.gpas.keycloak", GpasKeycloakConfigProperties::class.java)
                .get()
        assertThat(keycloakCfg.tokenUri).isEqualTo("http://kc/token")
        assertThat(keycloakCfg.clientId).isEqualTo("etl-processor")
        assertThat(keycloakCfg.clientSecret).isEqualTo("secret")

        val gpasCfg = binder.bind("app.pseudonymize.gpas", GPasConfigProperties::class.java).get()
        assertThat(gpasCfg.soapEndpoint).isEqualTo("http://gpas/soap")
    }

    @Test
    fun shouldBindPseudonymGeneratorEnumFromEnvVar() {
        val binder = binderFor("APP_PSEUDONYMIZE_GENERATOR" to "GPAS_KEYCLOAK")

        val pseudonymizeCfg =
            binder.bind("app.pseudonymize", PseudonymizeConfigProperties::class.java).get()
        assertThat(pseudonymizeCfg.generator).isEqualTo(PseudonymGenerator.GPAS_KEYCLOAK)
    }
}
