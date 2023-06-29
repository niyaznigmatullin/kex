package org.vorpal.research.kex.concolic

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.junit.Test
import kotlin.time.ExperimentalTime

@ExperimentalTime
@ExperimentalSerializationApi
@InternalSerializationApi
@DelicateCoroutinesApi
class PrimitiveConcolicLongTest : ConcolicTest("primitive-concolic") {
    @Test
    fun primitiveConcolicTest() {
        assertCoverage(cm["org/vorpal/research/kex/test/concolic/PrimitiveConcolicTests"], 1.0, 0.5)
    }
}
