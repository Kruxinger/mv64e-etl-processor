package dev.dnpm.etl.processor.output

import dev.dnpm.etl.processor.services.RequestProcessor
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import dev.dnpm.etl.processor.config.ConsentDbProperties

@Service
class ConsentDbWriter(
    private val props: ConsentDbProperties
) {

    fun writeConsent(fallnummer: Long, patientenId: String, consentJson: String) {
        DriverManager.getConnection(props.url, props.user, props.password).use { conn ->
            conn.autoCommit = false

            val selectSql = """
                SELECT COUNT(*) 
                FROM genomseq_mapping 
                WHERE fallnummer = ? AND patienten_id = ?
            """.trimIndent()

            val exists = conn.prepareStatement(selectSql).use { stmt ->
                stmt.setLong(1, fallnummer)
                stmt.setString(2, patientenId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }

            if (exists) {
                val updateSql = """
                    UPDATE genomseq_mapping
                    SET broad_consent = ?::json
                    WHERE fallnummer = ? AND patienten_id = ?
                """.trimIndent()
                conn.prepareStatement(updateSql).use { stmt ->
                    stmt.setString(1, consentJson)
                    stmt.setLong(2, fallnummer)
                    stmt.setString(3, patientenId)
                    stmt.executeUpdate()
                }
            } else {
                val insertSql = """
                    INSERT INTO genomseq_mapping
                    (fallnummer, patienten_id, patient_id, timestamp, broad_consent)
                    VALUES (?, ?, 0, ?, ?::json)
                """.trimIndent()
                conn.prepareStatement(insertSql).use { stmt ->
                    stmt.setLong(1, fallnummer)
                    stmt.setString(2, patientenId)
                    stmt.setTimestamp(3, Timestamp.from(Instant.now()))
                    stmt.setString(4, consentJson)
                    stmt.executeUpdate()
                }
            }

            conn.commit()
        }
    }
}
