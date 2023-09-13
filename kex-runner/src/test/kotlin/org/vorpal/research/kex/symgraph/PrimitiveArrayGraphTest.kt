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
class PrimitiveArrayGraphTest: GraphBuildingTest("primitive-array-graph-test") {
    @Test
    fun primitiveArrayStackGraphTest() {
        val builder = buildGraph(setOf(cm["org/vorpal/research/kex/test/symgraph/primitivearray/ListStackOfArrays"]), 3)
        assertEquals(7, builder.allStates.size)
    }

    @Test
    fun primitiveArrayStackGraphCoverageTest() {
        assertCoverage(cm["org/vorpal/research/kex/test/symgraph/primitivearray/ListStackOfArrays"], 1.0, 0.5)
    }
}
