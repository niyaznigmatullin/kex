package org.vorpal.research.kex.symgraph

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlin.test.Ignore
import kotlin.test.Test

@ExperimentalSerializationApi
@InternalSerializationApi
class StaticDefaultInstanceTest: GraphBuildingTest("graph-static-default-instance") {
    @Test
    @Ignore
    fun testStaticDefaultInstance() {
        buildGraph(setOf(cm["org/vorpal/research/kex/test/symgraph/StaticDefaultInstance"]), 2)
    }
}
