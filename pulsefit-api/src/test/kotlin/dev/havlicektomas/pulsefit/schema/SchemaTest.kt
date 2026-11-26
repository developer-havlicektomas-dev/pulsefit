package dev.havlicektomas.pulsefit.schema

import dev.havlicektomas.pulsefit.IntegrationTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The Flyway migrations produce the schema of backend-spec §5. Each test rolls back. */
@Transactional
class SchemaTest : IntegrationTest() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val userId = UUID.randomUUID()
    private val activityId = UUID.randomUUID()

    @Test
    fun `tables exist in schema app with row level security and no policies`() {
        val tables = jdbc.queryForList(
            """
            select c.relname as name, c.relrowsecurity as rls
            from pg_class c join pg_namespace n on n.oid = c.relnamespace
            where n.nspname = 'app' and c.relkind = 'r' and c.relname <> 'flyway_schema_history'
            order by c.relname
            """.trimIndent(),
        ).associate { it["name"] as String to it["rls"] as Boolean }

        assertEquals(mapOf("activities" to true, "profiles" to true, "track_points" to true), tables)
        assertEquals(0, jdbc.queryForObject("select count(*) from pg_policies where schemaname = 'app'", Int::class.java))
    }

    @Test
    fun `nothing is created in schema public`() {
        val count = jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_schema = 'public'",
            Int::class.java,
        )
        assertEquals(0, count)
    }

    @Test
    fun `sync and list indexes exist`() {
        val indexes = jdbc.queryForList(
            "select indexname, indexdef from pg_indexes where schemaname = 'app' and tablename = 'activities'",
        ).associate { it["indexname"] as String to it["indexdef"] as String }

        assertTrue(indexes["activities_sync_idx"]!!.endsWith("(user_id, updated_at, id)"))
        assertTrue(indexes["activities_list_idx"]!!.endsWith("(user_id, started_at DESC) WHERE (deleted_at IS NULL)"))
    }

    @Test
    fun `a valid activity with points is accepted and defaults are applied`() {
        insertActivity()
        insertPoint()

        val row = jdbc.queryForMap(
            "select version, time_slow_ms, created_at, updated_at, deleted_at from app.activities where id = ?",
            activityId,
        )
        assertEquals(1L, row["version"])
        assertEquals(0L, row["time_slow_ms"])
        assertTrue(row["created_at"] != null && row["updated_at"] != null)
        assertEquals(null, row["deleted_at"])
    }

    @Test
    fun `deleting a profile cascades to activities and points`() {
        insertActivity()
        insertPoint()

        jdbc.update("delete from app.profiles where id = ?", userId)

        assertEquals(0, jdbc.queryForObject("select count(*) from app.activities", Int::class.java))
        assertEquals(0, jdbc.queryForObject("select count(*) from app.track_points", Int::class.java))
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("badActivityValues")
    fun `activity check constraint rejects a bad value`(column: String, value: String) {
        assertCheckViolation { insertActivity(column to value) }
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("badPointValues")
    fun `track point check constraint rejects a bad value`(column: String, value: String) {
        insertActivity()
        assertCheckViolation { insertPoint(column to value) }
    }

    private fun assertCheckViolation(block: () -> Unit) {
        val e = assertFailsWith<DataIntegrityViolationException> { block() }
        assertEquals(CHECK_VIOLATION, (e.mostSpecificCause as SQLException).sqlState)
    }

    /** Inserts the profile and a valid activity, with [override] replacing one column's SQL literal. */
    private fun insertActivity(override: Pair<String, String>? = null) {
        jdbc.update("insert into app.profiles (id) values (?)", userId)
        val values = linkedMapOf(
            "id" to "'$activityId'",
            "user_id" to "'$userId'",
            "type" to "'RUNNING'",
            "title" to "'Morning Run'",
            "started_at" to "'2026-11-16T06:30:00Z'",
            "ended_at" to "'2026-11-16T07:05:12Z'",
            "zone_id" to "'Europe/Prague'",
            "active_duration_ms" to "2010000",
            "elapsed_duration_ms" to "2112000",
            "distance_m" to "5234.7",
            "avg_speed_mps" to "2.6",
            "max_speed_mps" to "4.12",
            "source" to "'PHONE'",
            "preview_polylines" to "array['_p~iF~ps|U_ulLnnqC_mqNvxq`@']",
        )
        override?.let { (column, value) -> values[column] = value }
        jdbc.update("insert into app.activities (${values.keys.joinToString()}) values (${values.values.joinToString()})")
    }

    private fun insertPoint(override: Pair<String, String>? = null) {
        val values = linkedMapOf(
            "activity_id" to "'$activityId'",
            "seq" to "0",
            "segment" to "0",
            "lat" to "50.0875",
            "lng" to "14.4213",
            "accuracy_m" to "6.0",
            "recorded_at" to "'2026-11-16T06:30:02Z'",
        )
        override?.let { (column, value) -> values[column] = value }
        jdbc.update("insert into app.track_points (${values.keys.joinToString()}) values (${values.values.joinToString()})")
    }

    companion object {
        private const val CHECK_VIOLATION = "23514"

        @JvmStatic
        fun badActivityValues() = listOf(
            Arguments.of("type", "'SWIMMING'"),
            Arguments.of("title", "''"),
            Arguments.of("title", "'${"x".repeat(81)}'"),
            Arguments.of("ended_at", "'2026-11-16T06:29:59Z'"),
            Arguments.of("active_duration_ms", "-1"),
            Arguments.of("elapsed_duration_ms", "2009999"),
            Arguments.of("distance_m", "-0.1"),
            Arguments.of("avg_speed_mps", "-0.1"),
            Arguments.of("max_speed_mps", "-0.1"),
            Arguments.of("source", "'TABLET'"),
        )

        @JvmStatic
        fun badPointValues() = listOf(
            Arguments.of("lat", "90.0001"),
            Arguments.of("lat", "-90.0001"),
            Arguments.of("lng", "180.0001"),
            Arguments.of("lng", "-180.0001"),
        )
    }
}
