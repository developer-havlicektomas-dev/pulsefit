package dev.havlicektomas.pulsefit

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

// Deliberately failing test for the HAV-169 CI check; reverted in the next commit.
class CiFailureCheckTest {
    @Test
    fun failsOnPurpose() {
        assertEquals(5, 2 + 2)
    }
}
