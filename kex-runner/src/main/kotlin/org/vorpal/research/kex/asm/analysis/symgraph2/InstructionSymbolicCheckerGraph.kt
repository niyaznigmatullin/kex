package org.vorpal.research.kex.asm.analysis.symgraph2

import kotlinx.collections.immutable.PersistentMap
import kotlinx.coroutines.*
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symbolic.*
import org.vorpal.research.kex.asm.analysis.symgraph2.objects.GraphVertex
import org.vorpal.research.kex.compile.CompilationException
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.parameters.concreteParameters
import org.vorpal.research.kex.parameters.filterIgnoredStatic
import org.vorpal.research.kex.parameters.filterStaticFinals
import org.vorpal.research.kex.reanimator.SymGraphGenerator
import org.vorpal.research.kex.reanimator.UnsafeGenerator
import org.vorpal.research.kex.reanimator.codegen.klassName
import org.vorpal.research.kex.smt.AsyncChecker
import org.vorpal.research.kex.smt.InitialDescriptorReanimator
import org.vorpal.research.kex.smt.Result
import org.vorpal.research.kex.state.BasicState
import org.vorpal.research.kex.state.predicate.Predicate
import org.vorpal.research.kex.state.predicate.path
import org.vorpal.research.kex.state.term.ArgumentTerm
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kex.state.term.ValueTerm
import org.vorpal.research.kex.state.transformer.DescriptorGenerator
import org.vorpal.research.kex.state.transformer.generateInitialDescriptors
import org.vorpal.research.kex.util.newFixedThreadPoolContextWithMDC
import org.vorpal.research.kfg.ir.BasicBlock
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.Value
import org.vorpal.research.kfg.ir.value.instruction.Instruction
import org.vorpal.research.kfg.ir.value.instruction.ReturnInst
import org.vorpal.research.kfg.type.ClassType
import org.vorpal.research.kthelper.logging.debug
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
    private val tests = mutableListOf<Job>()

    companion object {
        @ExperimentalTime
        @DelicateCoroutinesApi
        fun run(context: ExecutionContext, targets: Set<Method>) {
            val executors = kexConfig.getIntValue("symgraph", "numberOfExecutors", 8)
            val timeLimit = kexConfig.getIntValue("symgraph", "timeLimit", 100)
            val depth = kexConfig.getIntValue("symgraph", "depth", 4)

            val actualNumberOfExecutors = maxOf(1, minOf(executors, targets.size))
            val coroutineContext = newFixedThreadPoolContextWithMDC(actualNumberOfExecutors, "symbolic-dispatcher")
            val graphBuilder = GraphBuilder(context, targets.flatMap { method ->
                buildList {
                    add(method.klass)
                    addAll(method.argTypes.filterIsInstance<ClassType>().map { it.klass }
                        .filter { it.pkg.canonicalName == method.klass.pkg.canonicalName })
                    // only classes from the same package are taken so that there are not that much
                    // TODO think about how to make it better
                }
            }.toSet())
            graphBuilder.build(depth)
            log.debug("After building the graph")
            runBlocking(coroutineContext) {
                log.debug("Running second phase with timeout = ${timeLimit.seconds}s")
                withTimeoutOrNull(timeLimit.seconds) {
                    targets.map {
                        launch {
                            with(InstructionSymbolicCheckerGraph(context, it, graphBuilder)) {
                                analyze()
                                tests.joinAll()
                            }
                        }
                    }.joinAll()
                }
            }
        }
    }

    private suspend fun generateTest(
        parameters: GraphTestCase,
        testPostfix: String,
    ) {
        val currentTestIndex = testIndex.getAndIncrement()
        val generatorGraph = SymGraphGenerator(
            ctx,
            rootMethod,
            rootMethod.klassName + testPostfix + currentTestIndex + "graph",
        )
        log.debug("Generating test: ${generatorGraph.testName}")
        val generator = UnsafeGenerator(
            ctx,
            rootMethod,
            rootMethod.klassName + testPostfix + currentTestIndex
        )
        generator.generate(parameters.descriptors)
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

    private suspend fun findParameters(traverserState: TraverserState): GraphTestCase? {
        val (argTerms, thisTerm) = rootMethod.generateArgumentTerms()
        val currentPredicateState = with(traverserState.symbolicState) {
            clauses.asState() + path.asState()
        }

        for (graphState in graphBuilder.allSymbolicStates) {
            for (absCall in graphBuilder.getAbstractCalls(graphState.heapState, rootMethod)) {
                val predicates = mutableListOf<Predicate>()
                if (thisTerm != null) {
                    val mappedThis = graphState.objectMapping.getValue(absCall.thisArg)
                    predicates.add(path { (thisTerm eq mappedThis) equality const(true) })
                }
                for ((argTerm, mappedArg) in argTerms.zip(absCall.arguments)) {
                    if (mappedArg is ObjectArgument) {
                        val mappedTerm = graphState.objectMapping.getValue(mappedArg.obj)
                        predicates.add(path { (argTerm eq mappedTerm) equality const(true) })
                    }
                }
                val mappedSymbolicState = graphState.predicateState + BasicState(predicates) + currentPredicateState
                log.debug(
                    "Exc/Return Instruction check state for method: {}: {} with graph state {}",
                    rootMethod,
                    mappedSymbolicState,
                    graphState.heapState.toString(
                        emptyMap()
                    )
                )
                val checker = AsyncChecker(rootMethod, ctx)
                val result = checker.prepareAndCheck(rootMethod, mappedSymbolicState)
                val changedState = checker.state
                check(result.known)
                if (result is Result.SatResult) {
                    log.debug(
                        "Exc/Return Instruction add for method: {}: {}, changedState = {} with result {} with graph state {}",
                        rootMethod,
                        mappedSymbolicState,
                        changedState,
                        result,
                        graphState.heapState.toString(
                            emptyMap()
                        )
                    )
                    val generator = DescriptorGenerator(
                        rootMethod,
                        ctx,
                        result.model,
                        InitialDescriptorReanimator(result.model, ctx)
                    )
                    generator.apply(changedState)
                    val thisParameter = absCall.thisArg.let {
                        when (it) {
                            GraphValue.Null -> null
                            else -> ObjectArgument(it)
                        }
                    }
                    val argParameter = absCall.arguments.zip(argTerms).map { (arg, argTerm) ->
                        when (arg) {
                            is ObjectArgument -> arg
                            else -> PrimitiveArgument(argTerm)
                        }
                    }
                    val parameters = Parameters(thisParameter, argParameter)
                    val termValues = buildMap {
                        for (term in graphState.heapState.terms) {
                            val descriptor = generator.reanimateTerm(term)
                            if (descriptor != null) {
                                put(term, descriptor)
                            }
                        }
                        for (arg in argParameter) {
                            if (arg is PrimitiveArgument) {
                                put(arg.term, generator.reanimateTerm(arg.term)!!)
                            }
                        }
                    }
                    // Not required, `descriptors` is generated only for fallback UnsafeGenerator
                    val descriptors = generateInitialDescriptors(
                        rootMethod,
                        ctx,
                        result.model,
                        changedState
                    ).concreteParameters(ctx.cm, ctx.accessLevel, ctx.random).also {
                        log.debug { "Generated params:\n$it" }
                    }
                        .filterStaticFinals(ctx.cm)
                        .filterIgnoredStatic()
                    return GraphTestCase(parameters, graphState.heapState, termValues, descriptors)
                } else {
                    log.debug(
                        "Exc/Return Instruction can't see for method: {}: {}, changedState = {} with graph state {}",
                        rootMethod,
                        mappedSymbolicState,
                        changedState,
                        graphState.heapState.toString(
                            emptyMap()
                        )
                    )
                }
            }
        }
        return null
    }

    private fun Method.generateArgumentTerms(): Pair<List<ArgumentTerm>, ValueTerm?> {
        val arguments = buildList {
            for ((index, type) in argTypes.withIndex()) {
                add(arg(type.symbolicType, index))
            }
        }
        val thisTerm = if (!isStatic && !isConstructor) {
            `this`(klass.symbolicClass)
        } else {
            null
        }
        return Pair(arguments, thisTerm)
    }

    override suspend fun traverseReturnInst(traverserState: TraverserState, inst: ReturnInst): TraverserState? {
        val stackTrace = traverserState.stackTrace
        val stackTraceElement = stackTrace.lastOrNull()
        val receiver = stackTraceElement?.instruction
        return if (receiver == null) {
            log.debug("Return Instruction for method: {}", rootMethod)
            val result = findParameters(traverserState)
            if (result != null) {
                createTestGenerationTask(result, "sh")
            }
            null
        } else {
            super.traverseReturnInst(traverserState, inst)
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
            val result = findParameters(state)
            if (result != null) {
                createTestGenerationTask(result, "_throw_${throwableType.toString().replace("[/$.]".toRegex(), "_")}")
            }
        } else {
            super.throwExceptionAndReport(state, parameters, inst, throwable)
        }
    }


    private suspend fun createTestGenerationTask(
        parameters: GraphTestCase,
        testPostfix: String
    ): Unit = coroutineScope {
        tests.add(launch { generateTest(parameters, testPostfix) })
    }
}


