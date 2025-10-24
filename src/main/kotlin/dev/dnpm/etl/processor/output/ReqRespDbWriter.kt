package dev.dnpm.etl.processor.output

import org.springframework.stereotype.Service
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import dev.dnpm.etl.processor.config.ConsentDbProperties

@Service
class ReqRespDbWriter(
    private val props: ConsentDbProperties
) {

    fun writeReqResp(
        patId: String,
        caseId: String,
        requestJson: String,
        responseJson: String
    ) {
        DriverManager.getConnection(props.url, props.user, props.password).use { conn ->
            conn.autoCommit = false

            val insertSql = """
                INSERT INTO dip_req_resp
                (pat_id, case_id, request, response, timestamp)
                VALUES (?, ?, ?::json, ?::json, ?)
            """.trimIndent()

            conn.prepareStatement(insertSql).use { stmt ->
                stmt.setString(1, patId)
                stmt.setString(2, caseId)
                stmt.setString(3, requestJson)
                stmt.setString(4, responseJson)
                stmt.setTimestamp(5, Timestamp.from(Instant.now()))
                stmt.executeUpdate()
            }

            conn.commit()
        }
    }
}
