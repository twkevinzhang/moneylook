package tw.kevinzhang.moneylook.schedule

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class CronNextExecutionTest {

    private val parser = CronParser(
        CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)
    )

    @Test
    fun `daily cron next execution is in the future`() {
        val cron = parser.parse("0 8 * * *")
        val next = ExecutionTime.forCron(cron)
            .nextExecution(ZonedDateTime.now())
            .orElse(null)
        assertTrue("next execution should be in the future", next != null && next.isAfter(ZonedDateTime.now()))
    }

    @Test
    fun `next execution delay is positive milliseconds`() {
        val cron = parser.parse("0 8 * * *")
        val now = ZonedDateTime.now()
        val next = ExecutionTime.forCron(cron).nextExecution(now).orElse(null)!!
        val delayMs = java.time.Duration.between(now, next).toMillis()
        assertTrue("delay should be > 0", delayMs > 0)
        assertTrue("delay should be <= 24h in ms", delayMs <= 86_400_000L)
    }
}
