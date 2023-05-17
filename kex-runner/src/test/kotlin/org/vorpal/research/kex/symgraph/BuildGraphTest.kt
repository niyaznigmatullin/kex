package org.vorpal.research.kex.symgraph

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.vorpal.research.kex.KexRunnerTest
import org.vorpal.research.kex.asm.analysis.symgraph2.GraphBuilder
import org.vorpal.research.kex.asm.manager.ClassInstantiationDetector
import org.vorpal.research.kfg.Package
import org.vorpal.research.kfg.visitor.executePipeline
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@ExperimentalSerializationApi
@InternalSerializationApi
class BuildGraphTest: KexRunnerTest("build-graph") {

    @Test
    fun test() {
        executePipeline(analysisContext.cm, Package.defaultPackage) {
            +ClassInstantiationDetector(analysisContext)
        }
        val builder = GraphBuilder(analysisContext, setOf(cm["org/vorpal/research/kex/test/symgraph/EmptyClass"]))
        builder.build(2)
        assertEquals(3, builder.allStates.size)
    }

    @Test
    fun testNode() {
        executePipeline(analysisContext.cm, Package.defaultPackage) {
            +ClassInstantiationDetector(analysisContext)
        }
        val builder = GraphBuilder(analysisContext, setOf(cm["org/vorpal/research/kex/test/symgraph/Node"]))
        builder.build(2)
        assertEquals(9, builder.allStates.size)
    }
}
