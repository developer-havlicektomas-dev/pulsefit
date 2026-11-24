package dev.havlicektomas.pulsefit

import org.junit.Assert.assertEquals
import org.junit.Test

// Deliberately failing test for the HAV-169 CI check; reverted in the next commit.
class CiFailureCheckTest {
    @Test
    fun failsOnPurpose() {
        assertEquals(5, 2 + 2)
    }
}
