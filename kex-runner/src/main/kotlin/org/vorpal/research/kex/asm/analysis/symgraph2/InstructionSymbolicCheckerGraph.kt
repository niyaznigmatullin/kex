package org.vorpal.research.kex.asm.analysis.symgraph2

import kotlinx.coroutines.*
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symbolic.*
import org.vorpal.research.kex.compile.CompilationException
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.reanimator.SymGraphGenerator
import org.vorpal.research.kex.reanimator.UnsafeGenerator
import org.vorpal.research.kex.reanimator.codegen.klassName
import org.vorpal.research.kex.util.newFixedThreadPoolContextWithMDC
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.instruction.Instruction
import org.vorpal.research.kthelper.logging.log
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

class InstructionSymbolicCheckerGraph(
    ctx: ExecutionContext,
    rootMethod: Method,
    private val graphBuilder: GraphBuilder
) : SymbolicTraverser(ctx, rootMethod) {
    override val pathSelector: SymbolicPathSelector = DequePathSelector()
    override val callResolver: SymbolicCallResolver = DefaultCallResolver(ctx)
    override val invokeDynamicResolver: SymbolicInvokeDynamicResolver = DefaultCallResolver(ctx)
    private val tests = mutableListOf<ReportedTest>()

    private data class ReportedTest(val parameters: Parameters<Descriptor>, val testPostfix: String)

    companion object {
        @ExperimentalTime
        @DelicateCoroutinesApi
        fun run(context: ExecutionContext, targets: Set<Method>) {
            val executors = kexConfig.getIntValue("symbolic", "numberOfExecutors", 8)
            val timeLimit = kexConfig.getIntValue("symbolic", "timeLimit", 100)

            val actualNumberOfExecutors = maxOf(1, minOf(executors, targets.size))
            val coroutineContext = newFixedThreadPoolContextWithMDC(actualNumberOfExecutors, "symbolic-dispatcher")
            val graphBuilder = GraphBuilder(context, targets.map { it.klass }.toSet())
            graphBuilder.build(3)
            runBlocking(coroutineContext) {
                withTimeoutOrNull(timeLimit.seconds) {
                    targets.map {
                        async {
                            with(InstructionSymbolicCheckerGraph(context, it, graphBuilder)) {
                                analyze()
                                generateTests()
                            }
                        }
                    }.awaitAll()
                }
            }
        }
    }

    suspend fun generateTests() = tests.forEach { (parameters, testPostfix) ->
        val generatorGraph = SymGraphGenerator(
            ctx,
            rootMethod,
            rootMethod.klassName + testPostfix + testIndex.getAndIncrement() + "graph",
            graphBuilder
        );
        val testFile = if (generatorGraph.generate(parameters)) {
            generatorGraph.emit()
        } else {
            val generator = UnsafeGenerator(
                ctx,
                rootMethod,
                rootMethod.klassName + testPostfix + testIndex.getAndIncrement()
            )
            generator.generate(parameters)
            generator.emit()
        }
        try {
            compilerHelper.compileFile(testFile)
        } catch (e: CompilationException) {
            log.error("Failed to compile test file $testFile")
        }
    }

    override fun report(
        inst: Instruction,
        parameters: Parameters<Descriptor>,
        testPostfix: String
    ): Boolean {
        tests.add(ReportedTest(parameters, testPostfix))
        return true
    }
}


