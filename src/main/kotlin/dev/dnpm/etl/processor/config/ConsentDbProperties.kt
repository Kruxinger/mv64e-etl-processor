package dev.dnpm.etl.processor.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "app.consentdb")
data class ConsentDbProperties(
    var url: String = "",
    var user: String = "",
    var password: String = ""
)
