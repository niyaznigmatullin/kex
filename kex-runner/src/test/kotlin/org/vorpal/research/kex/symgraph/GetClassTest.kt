package org.vorpal.research.kex.symgraph

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlin.test.Ignore
import kotlin.test.Test

@ExperimentalSerializationApi
@InternalSerializationApi
class GetClassTest: GraphBuildingTest("graph-get-class") {
    @Test
    @Ignore
    fun testGetClass() {
        buildGraph(setOf(cm["org/vorpal/research/kex/test/symgraph/getClass/PointClass"]), 2)
    }
}
