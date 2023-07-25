package org.vorpal.research.kex.asm.analysis.symgraph2

import kotlinx.collections.immutable.PersistentMap
import kotlinx.coroutines.*
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symbolic.*
import org.vorpal.research.kex.asm.analysis.symgraph2.StacktracePathSelector
import org.vorpal.research.kex.asm.analysis.util.checkAsyncByPredicates
import org.vorpal.research.kex.compile.CompilationException
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.reanimator.SymGraphGenerator
import org.vorpal.research.kex.reanimator.UnsafeGenerator
import org.vorpal.research.kex.reanimator.codegen.klassName
import org.vorpal.research.kex.state.BasicState
import org.vorpal.research.kex.state.predicate.Predicate
import org.vorpal.research.kex.state.predicate.path
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kex.util.newFixedThreadPoolContextWithMDC
import org.vorpal.research.kfg.ir.BasicBlock
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.Value
import org.vorpal.research.kfg.ir.value.instruction.Instruction
import org.vorpal.research.kfg.ir.value.instruction.ReturnInst
import org.vorpal.research.kfg.type.ClassType
import org.vorpal.research.kthelper.logging.log
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

class InstructionSymbolicCheckerGraph(
    ctx: ExecutionContext,
    rootMethod: Method,
    private val graphBuilder: GraphBuilder
) : SymbolicTraverser(ctx, rootMethod) {
    override val pathSelector: SymbolicPathSelector = StacktracePathSelector()
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
            val graphBuilder = GraphBuilder(context, targets.flatMap { method ->
                buildList {
                    add(method.klass)
                    addAll(method.argTypes.filterIsInstance<ClassType>().map { it.klass }.filter { it.pkg.canonicalName == method.klass.pkg.canonicalName })
//                    addAll(context.cm.getAllSubtypesOf(method.klass))
//                    addAll(method.argTypes.flatMap {
//                        when (it) {
//                            is ClassType -> context.cm.getAllSubtypesOf(it.klass)
//                            else -> emptyList()
//                        }
//                    })
                }
            }.toSet())
            graphBuilder.build(4)
            log.debug("After building the graph")
            runBlocking(coroutineContext) {
                log.debug("Inside runBlocking")
                withTimeoutOrNull(timeLimit.seconds) {
                    log.debug("Inside withTimeoutOrNull")
                    targets.map {
                        async {
                            with(InstructionSymbolicCheckerGraph(context, it, graphBuilder)) {
                                withTimeoutOrNull((timeLimit / 3).seconds) {
                                    analyze()
                                }
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
        )
        log.debug("Generating test: ${generatorGraph.testName}")
        val generator = UnsafeGenerator(
            ctx,
            rootMethod,
            rootMethod.klassName + testPostfix + testIndex.getAndIncrement()
        )
        generator.generate(parameters)
        val testFile = if (generatorGraph.generate(parameters)) {
//            generator.emit()
            generatorGraph.emit()
        } else {
            generator.emit()
        }
        try {
            compilerHelper.compileFile(testFile)
        } catch (e: CompilationException) {
            log.error("Failed to compile test file $testFile")
        }
    }

    private suspend fun findDescriptors(traverserState: TraverserState): Parameters<Descriptor>? {
        val graphSymbolicStates = graphBuilder.allSymbolicStates
        val arguments = buildList {
            for ((index, type) in rootMethod.argTypes.withIndex()) {
                add(arg(type.symbolicType, index))
            }
        }
        val thisTerm = if (!rootMethod.isStatic && !rootMethod.isConstructor) {
            `this`(rootMethod.klass.symbolicClass)
        } else {
            null
        }
        val currentPredicateState = with(traverserState.symbolicState) {
            clauses.asState() + path.asState()
        }

        for (graphState in graphSymbolicStates) {
            val graphSymbolicState = graphState.predicateState
            for (absCall in graphBuilder.getAbstractCalls(graphState.heapState, rootMethod)) {
                val predicates = mutableListOf<Predicate>()
                if (thisTerm != null) {
                    val mappedThis = graphState.objectMapping.getValue(absCall.thisArg)
                    predicates.add(path { (thisTerm eq mappedThis) equality const(true) })
                }
                for ((argTerm, mappedArg) in arguments.zip(absCall.arguments)) {
                    if (mappedArg is ObjectArgument) {
                        val mappedTerm = graphState.objectMapping.getValue(mappedArg.obj)
                        predicates.add(path { (argTerm eq mappedTerm) equality const(true) })
                    }
                }
                val mappedSymbolicState = graphSymbolicState + BasicState(predicates) + currentPredicateState
                log.debug("Exc/Return Instruction check state for method: $rootMethod: $mappedSymbolicState with graph state ${
                    graphState.heapState.toString(
                        emptyMap()
                    )
                }")
                val (result, changedState) = rootMethod.checkAsyncByPredicates(ctx, mappedSymbolicState)
                if (result != null) {
                    log.debug(
                        "Exc/Return Instruction add for method: $rootMethod: $mappedSymbolicState, changedState = $changedState with result $result with graph state ${
                            graphState.heapState.toString(
                                emptyMap()
                            )
                        }"
                    )
                    return result
                } else {
                    log.debug("Exc/Return Instruction can't see for method: $rootMethod: $mappedSymbolicState, changedState = $changedState with graph state ${
                        graphState.heapState.toString(
                            emptyMap()
                        )
                    }")
                }
            }
        }
        return null
    }

    override suspend fun traverseReturnInst(inst: ReturnInst) {
        val traverserState = currentState ?: return
        val stackTrace = traverserState.stackTrace
        val stackTraceElement = stackTrace.lastOrNull()
        val receiver = stackTraceElement?.instruction
        if (receiver == null) {
            log.debug("Return Instruction for method: $rootMethod")
            val result = findDescriptors(traverserState)
            if (result != null) {
                report(inst, result, "sh")
            }
            currentState = null
//            super.traverseReturnInst(inst)
        } else {
            super.traverseReturnInst(inst)
        }
    }

    override suspend fun throwExceptionAndReport(
        state: TraverserState,
        parameters: Parameters<Descriptor>,
        inst: Instruction,
        throwable: Term
    ) {
        val throwableType = throwable.type.getKfgType(types)
        val catchFrame: Pair<BasicBlock, PersistentMap<Value, Term>>? = state.run {
            var catcher = inst.parent.handlers.firstOrNull { throwableType.isSubtypeOf(it.exception) }
            if (catcher != null) return@run catcher to this.valueMap
            for (i in stackTrace.indices.reversed()) {
                val block = stackTrace[i].instruction.parent
                catcher = block.handlers.firstOrNull { throwableType.isSubtypeOf(it.exception) }
                if (catcher != null) return@run catcher to stackTrace[i].valueMap
            }
            null
        }
        if (catchFrame == null) {
            val result = findDescriptors(state)
            if (result != null) {
                report(inst, result, "_throw_${throwableType.toString().replace("[/$.]".toRegex(), "_")}")
            }
        } else {
            super.throwExceptionAndReport(state, parameters, inst, throwable)
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


