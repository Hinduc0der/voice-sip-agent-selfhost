package io.github.sparkcyf.voicesipagent.privileged.shell

/**
 * setContext is documented from API 31 for AudioRecord.Builder and API 34 for AudioTrack.Builder.
 * Probe each builder independently so vendor API differences retain the attribution fallback.
 */
internal object AudioBuilderContext {
    fun supports(builderClass: Class<*>, contextClass: Class<*>): Boolean =
        try { builderClass.getMethod("setContext", contextClass); true } catch (_: NoSuchMethodException) { false }

    fun apply(builder: Any, context: Any, contextClass: Class<*>, prepareDefault: () -> Unit) {
        val setter = try {
            builder.javaClass.getMethod("setContext", contextClass)
        } catch (_: NoSuchMethodException) {
            prepareDefault()
            return
        }
        // A real invocation failure must remain visible; it is not evidence of an absent API.
        setter.invoke(builder, context)
    }
}
