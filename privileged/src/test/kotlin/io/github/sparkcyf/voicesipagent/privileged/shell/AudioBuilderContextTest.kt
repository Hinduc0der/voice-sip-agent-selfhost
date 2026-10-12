package io.github.sparkcyf.voicesipagent.privileged.shell

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class AudioBuilderContextTest {
    class Context
    class WithContext {
        var supplied: Context? = null
        fun setContext(context: Context) { supplied = context }
    }
    class WithoutContext
    class BrokenContext {
        fun setContext(context: Context) { throw SecurityException("attribution denied") }
    }

    @Test fun explicitContextIsUsedWhenPresent() {
        val context = Context()
        val builder = WithContext()
        AudioBuilderContext.apply(builder, context, Context::class.java) { fail("unexpected fallback") }
        assertSame(context, builder.supplied)
    }

    @Test fun mixedRecordAndTrackApisPrepareDefaultsOnlyForTheMissingSetter() {
        val context = Context()
        var prepared = 0
        assertTrue(AudioBuilderContext.supports(WithContext::class.java, Context::class.java))
        assertFalse(AudioBuilderContext.supports(WithoutContext::class.java, Context::class.java))
        AudioBuilderContext.apply(WithContext(), context, Context::class.java) { prepared++ }
        AudioBuilderContext.apply(WithoutContext(), context, Context::class.java) { prepared++ }
        assertEquals(1, prepared)
    }

    @Test fun failedDefaultAttributionPreventsConstruction() {
        val error = IllegalStateException("default identity mismatch")
        try {
            AudioBuilderContext.apply(WithoutContext(), Context(), Context::class.java) { throw error }
            fail("unsafe default must be rejected")
        } catch (failure: IllegalStateException) {
            assertSame(error, failure)
        }
    }

    @Test fun invocationFailuresAreReportedWithoutSwitchingToDefaults() {
        try {
            AudioBuilderContext.apply(BrokenContext(), Context(), Context::class.java) { fail("unexpected fallback") }
            fail("setter failure must be reported")
        } catch (failure: InvocationTargetException) {
            assertTrue(failure.cause is SecurityException)
        }
    }
}
