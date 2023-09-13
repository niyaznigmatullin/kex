package org.vorpal.research.kex.symgraph

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class, InternalSerializationApi::class, ExperimentalSerializationApi::class,
    DelicateCoroutinesApi::class
)
class StringsGraphTest: GraphBuildingTest("strings-graph-test") {

    @Test
    @Ignore
    fun stringContainerGraphTest() {
        val builder = buildGraph(setOf(cm["org/vorpal/research/kex/test/symgraph/strings/StringContainer"]), 3)
        assertEquals(3, builder.allStates.size)
    }

    @Test
    @Ignore
    fun stringContainerGraphTestCoverage() {
        assertCoverage(cm["org/vorpal/research/kex/test/symgraph/strings/StringContainer"], 1.0, 0.5)
    }
}