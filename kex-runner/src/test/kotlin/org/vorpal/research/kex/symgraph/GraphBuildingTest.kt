package org.vorpal.research.kex.symgraph

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.vorpal.research.kex.KexRunnerTest
import org.vorpal.research.kex.asm.analysis.symgraph2.GraphBuilder
import org.vorpal.research.kex.asm.analysis.symgraph2.InstructionSymbolicCheckerGraph
import org.vorpal.research.kex.asm.manager.ClassInstantiationDetector
import org.vorpal.research.kex.jacoco.CoverageReporter
import org.vorpal.research.kex.launcher.ClassLevel
import org.vorpal.research.kfg.Package
import org.vorpal.research.kfg.ir.Class
import org.vorpal.research.kfg.visitor.executePipeline
import org.vorpal.research.kthelper.logging.log
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime

@ExperimentalTime
@ExperimentalSerializationApi
@InternalSerializationApi
@DelicateCoroutinesApi
abstract class GraphBuildingTest(name: String) : KexRunnerTest(name) {
    fun buildGraph(classes: Set<Class>, depth: Int): GraphBuilder {
        executePipeline(analysisContext.cm, Package.defaultPackage) {
            +ClassInstantiationDetector(analysisContext)
        }
        return GraphBuilder(analysisContext, classes).apply { build(depth) }
    }

    fun assertCoverage(klass: Class, expectedCoverage: Double = 1.0, eps: Double = 0.0) {
        executePipeline(analysisContext.cm, Package.defaultPackage) {
            +ClassInstantiationDetector(analysisContext)
        }

        val methods = klass.allMethods.filter { !it.isPrivate }.toSet()
        InstructionSymbolicCheckerGraph.run(analysisContext, methods)

        val coverage = CoverageReporter(listOf(jar)).execute(klass.cm, ClassLevel(klass))
        log.debug(coverage.print(true))
        assertEquals(expectedCoverage, coverage.instructionCoverage.ratio, eps)
    }
}
