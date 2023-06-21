package org.vorpal.research.kex.symgraph

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.vorpal.research.kex.KexRunnerTest
import org.vorpal.research.kex.asm.analysis.symgraph2.GraphBuilder
import org.vorpal.research.kex.asm.manager.ClassInstantiationDetector
import org.vorpal.research.kfg.Package
import org.vorpal.research.kfg.ir.Class
import org.vorpal.research.kfg.visitor.executePipeline

@ExperimentalSerializationApi
@InternalSerializationApi
abstract class GraphBuildingTest(name: String) : KexRunnerTest(name) {
    fun buildGraph(classes: Set<Class>, depth: Int): GraphBuilder {
        executePipeline(analysisContext.cm, Package.defaultPackage) {
            +ClassInstantiationDetector(analysisContext)
        }
        return GraphBuilder(analysisContext, classes).apply { build(depth) }
    }
}