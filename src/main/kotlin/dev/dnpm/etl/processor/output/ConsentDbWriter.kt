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

    fun writeConsent(fallnummer: String, patientenId: String, broadConsentJson: String, mvConsentJson: String) {
        DriverManager.getConnection(props.url, props.user, props.password).use { conn ->
            conn.autoCommit = false

            val selectSql = """
            SELECT COUNT(*) 
            FROM genomseq_mapping 
            WHERE fallnummer = ? AND patienten_id = ?
        """.trimIndent()

            val exists = conn.prepareStatement(selectSql).use { stmt ->
                stmt.setString(1, fallnummer)
                stmt.setString(2, patientenId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }

            if (exists) {
                val updateSql = """
                UPDATE genomseq_mapping
                SET broad_consent = ?::json,
                    mv_consent = ?::json,
                    timestamp = ?
                WHERE fallnummer = ? AND patienten_id = ?
                """.trimIndent()
                conn.prepareStatement(updateSql).use { stmt ->
                    stmt.setString(1, broadConsentJson)
                    stmt.setString(2, mvConsentJson)
                    stmt.setTimestamp(3, Timestamp.from(Instant.now()))
                    stmt.setString(4, fallnummer)
                    stmt.setString(5, patientenId)
                    stmt.executeUpdate()
                }
            }
            else {
                val insertSql = """
                INSERT INTO genomseq_mapping
                (fallnummer, patienten_id, patient_id, timestamp, broad_consent, mv_consent)
                VALUES (?, ?, 0, ?, ?::json, ?::json)
            """.trimIndent()
                conn.prepareStatement(insertSql).use { stmt ->
                    stmt.setString(1, fallnummer)
                    stmt.setString(2, patientenId)
                    stmt.setTimestamp(3, Timestamp.from(Instant.now()))
                    stmt.setString(4, broadConsentJson)
                    stmt.setString(5, mvConsentJson)
                    stmt.executeUpdate()
                }
            }

            conn.commit()
        }
    }
}
