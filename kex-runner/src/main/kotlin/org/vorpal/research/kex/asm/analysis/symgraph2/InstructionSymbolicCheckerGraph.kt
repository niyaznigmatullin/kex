package org.vorpal.research.kex.asm.analysis.symgraph2

import kotlinx.coroutines.*
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symbolic.*
import org.vorpal.research.kex.asm.analysis.symbolic.InstructionSymbolicChecker
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
) : SymbolicTraverser(ctx, rootMethod) {
    override val pathSelector: SymbolicPathSelector = DequePathSelector()
    override val callResolver: SymbolicCallResolver = DefaultCallResolver(ctx)
    override val invokeDynamicResolver: SymbolicInvokeDynamicResolver = DefaultCallResolver(ctx)
    private val graphBuilder = GraphBuilder(ctx, rootMethod.klass)

    init {
        graphBuilder.build(3)
    }

    companion object {
        @ExperimentalTime
        @DelicateCoroutinesApi
        fun run(context: ExecutionContext, targets: Set<Method>) {
            val executors = kexConfig.getIntValue("symbolic", "numberOfExecutors", 8)
            val timeLimit = kexConfig.getIntValue("symbolic", "timeLimit", 100)

            val actualNumberOfExecutors = maxOf(1, minOf(executors, targets.size))
            val coroutineContext = newFixedThreadPoolContextWithMDC(actualNumberOfExecutors, "symbolic-dispatcher")
            runBlocking(coroutineContext) {
                withTimeoutOrNull(timeLimit.seconds) {
                    targets.map {
                        async { InstructionSymbolicCheckerGraph(context, it).analyze() }
                    }.awaitAll()
                }
            }
        }
    }

    override suspend fun report(
        inst: Instruction,
        parameters: Parameters<Descriptor>,
        testPostfix: String
    ): Boolean {
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
        return try {
            compilerHelper.compileFile(testFile)
            true
        } catch (e: CompilationException) {
            log.error("Failed to compile test file $testFile")
            false
        }
//        return true
    }
}


