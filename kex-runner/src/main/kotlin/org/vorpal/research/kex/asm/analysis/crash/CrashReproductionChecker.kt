package org.vorpal.research.kex.asm.analysis.crash

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.crash.precondition.ConstraintExceptionPrecondition
import org.vorpal.research.kex.asm.analysis.crash.precondition.ConstraintExceptionPreconditionBuilder
import org.vorpal.research.kex.asm.analysis.crash.precondition.DescriptorExceptionPreconditionBuilder
import org.vorpal.research.kex.asm.analysis.crash.precondition.ExceptionPreconditionBuilder
import org.vorpal.research.kex.asm.analysis.crash.precondition.ExceptionPreconditionBuilderImpl
import org.vorpal.research.kex.asm.analysis.symbolic.DefaultCallResolver
import org.vorpal.research.kex.asm.analysis.symbolic.SymbolicCallResolver
import org.vorpal.research.kex.asm.analysis.symbolic.SymbolicInvokeDynamicResolver
import org.vorpal.research.kex.asm.analysis.symbolic.SymbolicPathSelector
import org.vorpal.research.kex.asm.analysis.symbolic.SymbolicTraverser
import org.vorpal.research.kex.asm.analysis.symbolic.TraverserState
import org.vorpal.research.kex.asm.analysis.util.checkAsyncAndSlice
import org.vorpal.research.kex.compile.CompilationException
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.ktype.KexType
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.reanimator.UnsafeGenerator
import org.vorpal.research.kex.reanimator.codegen.klassName
import org.vorpal.research.kex.state.predicate.path
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kex.trace.symbolic.PathClause
import org.vorpal.research.kex.trace.symbolic.PathClauseType
import org.vorpal.research.kex.trace.symbolic.SymbolicState
import org.vorpal.research.kex.util.arrayIndexOOBClass
import org.vorpal.research.kex.util.asmString
import org.vorpal.research.kex.util.classCastClass
import org.vorpal.research.kex.util.negativeArrayClass
import org.vorpal.research.kex.util.newFixedThreadPoolContextWithMDC
import org.vorpal.research.kex.util.nullptrClass
import org.vorpal.research.kex.util.runtimeException
import org.vorpal.research.kex.util.testcaseDirectory
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.ir.Class
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.ThisRef
import org.vorpal.research.kfg.ir.value.instruction.ArrayLoadInst
import org.vorpal.research.kfg.ir.value.instruction.ArrayStoreInst
import org.vorpal.research.kfg.ir.value.instruction.CallInst
import org.vorpal.research.kfg.ir.value.instruction.CastInst
import org.vorpal.research.kfg.ir.value.instruction.FieldLoadInst
import org.vorpal.research.kfg.ir.value.instruction.FieldStoreInst
import org.vorpal.research.kfg.ir.value.instruction.Instruction
import org.vorpal.research.kfg.ir.value.instruction.NewArrayInst
import org.vorpal.research.kfg.ir.value.instruction.ThrowInst
import org.vorpal.research.kthelper.assert.ktassert
import org.vorpal.research.kthelper.logging.log
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimedValue
import kotlin.time.measureTimedValue


operator fun ClassManager.get(frame: StackTraceElement): Method {
    val entryClass = this[frame.className.asmString]
    return entryClass.allMethods.filter { it.name == frame.methodName }.first { method ->
        method.body.flatten().any { inst ->
            inst.location.file == frame.fileName && inst.location.line == frame.lineNumber
        }
    }
}

private val Instruction.isNullptrThrowing
    get() = when (this) {
        is ArrayLoadInst -> this.arrayRef !is ThisRef
        is ArrayStoreInst -> this.arrayRef !is ThisRef
        is FieldLoadInst -> !this.isStatic && this.owner !is ThisRef
        is FieldStoreInst -> !this.isStatic && this.owner !is ThisRef
        is CallInst -> !this.isStatic && this.callee !is ThisRef
        else -> false
    }


private fun StackTrace.targetException(context: ExecutionContext): Class =
    context.cm[firstLine.takeWhile { it != ':' }.asmString]

private fun StackTrace.targetInstructions(context: ExecutionContext): Set<Instruction> {
    val targetException = targetException(context)
    return context.cm[stackTraceLines.first()].body.flatten()
        .filter { it.location.line == stackTraceLines.first().lineNumber }
        .filterTo(mutableSetOf()) {
            when (targetException) {
                context.cm.nullptrClass -> it.isNullptrThrowing
                context.cm.arrayIndexOOBClass -> it is ArrayStoreInst || it is ArrayLoadInst
                context.cm.negativeArrayClass -> it is NewArrayInst
                context.cm.classCastClass -> it is CastInst
                else -> when (it) {
                    is ThrowInst -> it.throwable.type == targetException.asType
                    is CallInst -> targetException.isInheritorOf(context.cm.runtimeException)
                            || targetException in it.method.exceptions

                    else -> false
                }
            }
        }
}

class CrashReproductionChecker(
    ctx: ExecutionContext,
    @Suppress("MemberVisibilityCanBePrivate")
    val stackTrace: StackTrace,
    private val targetInstructions: Set<Instruction>,
    private val preconditionBuilder: ExceptionPreconditionBuilder,
    private val reproductionChecker: ExceptionReproductionChecker,
    private val shouldStopAfterFirst: Boolean
) : SymbolicTraverser(ctx, ctx.cm[stackTrace.stackTraceLines.last()]) {
    override val pathSelector: SymbolicPathSelector =
        RandomizedDistancePathSelector(ctx, rootMethod, targetInstructions, stackTrace)
    override val callResolver: SymbolicCallResolver = StackTraceCallResolver(
        stackTrace, DefaultCallResolver(ctx)
    )
    override val invokeDynamicResolver: SymbolicInvokeDynamicResolver = DefaultCallResolver(ctx)

    private val generatedTestClasses = mutableSetOf<String>()
    private val descriptors = mutableMapOf<String, Parameters<Descriptor>>()
    private val preconditions = mutableMapOf<String, ConstraintExceptionPrecondition>()
    private var lastPrecondition: ConstraintExceptionPrecondition? = null

    init {
        ktassert(
            targetInstructions.isNotEmpty(),
            "Could not find target instructions for stack trace\n\"\"\"${stackTrace.originalStackTrace}\"\"\""
        )
    }

    @Suppress("unused")
    companion object {

        data class CrashReproductionResult(
            val testClasses: Set<String>,
            val descriptors: Map<String, Parameters<Descriptor>>,
            val preconditions: Map<String, ConstraintExceptionPrecondition>
        ) {
            companion object {
                fun empty() = CrashReproductionResult(emptySet(), emptyMap(), emptyMap())
            }
        }

        private suspend fun runChecker(
            context: ExecutionContext,
            stackTrace: StackTrace,
            targetInstructions: Set<Instruction>,
            preconditionBuilder: ExceptionPreconditionBuilder,
            reproductionChecker: ExceptionReproductionChecker,
            shouldStopAfterFirst: Boolean
        ): CrashReproductionResult {
            val checker = CrashReproductionChecker(
                context,
                stackTrace,
                targetInstructions,
                preconditionBuilder,
                reproductionChecker,
                shouldStopAfterFirst
            )
            checker.analyze()
            return CrashReproductionResult(
                checker.generatedTestClasses,
                checker.descriptors,
                checker.preconditions
            )
        }

        @OptIn(ExperimentalTime::class)
        private suspend fun runCheckerWithTimeLimit(
            timeLimit: Duration,
            context: ExecutionContext,
            stackTrace: StackTrace,
            targetInstructions: Set<Instruction>,
            preconditionBuilder: ExceptionPreconditionBuilder,
            reproductionChecker: ExceptionReproductionChecker,
            shouldStopAfterFirst: Boolean
        ): TimedValue<CrashReproductionResult>? = withTimeoutOrNull(timeLimit) {
            measureTimedValue {
                runChecker(
                    context,
                    stackTrace,
                    targetInstructions,
                    preconditionBuilder,
                    reproductionChecker,
                    shouldStopAfterFirst
                )
            }
        }


        @ExperimentalTime
        @DelicateCoroutinesApi
        fun run(context: ExecutionContext, stackTrace: StackTrace): Set<String> {
            val timeLimit = kexConfig.getIntValue("crash", "timeLimit", 100)
            val stopAfterFirstCrash = kexConfig.getBooleanValue("crash", "stopAfterFirstCrash", false)

            val coroutineContext = newFixedThreadPoolContextWithMDC(1, "crash-dispatcher")
            return runBlocking(coroutineContext) {
                withTimeoutOrNull(timeLimit.seconds) {
                    async {
                        runChecker(
                            context,
                            stackTrace,
                            stackTrace.targetInstructions(context),
                            ExceptionPreconditionBuilderImpl(context, stackTrace.targetException(context)),
                            ExceptionReproductionCheckerImpl(context, stackTrace),
                            stopAfterFirstCrash
                        ).testClasses
                    }.await()
                } ?: emptySet()
            }
        }

        @ExperimentalTime
        @DelicateCoroutinesApi
        fun runWithDescriptorPreconditions(context: ExecutionContext, stackTrace: StackTrace): Set<String> =
            runIteratively(context, stackTrace) { result ->
                DescriptorExceptionPreconditionBuilder(
                    context,
                    stackTrace.targetException(context),
                    result.descriptors.values.toSet()
                )
            }

        @ExperimentalTime
        @DelicateCoroutinesApi
        fun runWithConstraintPreconditions(context: ExecutionContext, stackTrace: StackTrace): Set<String> =
            runIteratively(context, stackTrace) { result ->
                ConstraintExceptionPreconditionBuilder(
                    context,
                    stackTrace.targetException(context),
                    result.preconditions.values.toSet()
                )
            }

        @OptIn(ExperimentalPathApi::class)
        @ExperimentalTime
        @DelicateCoroutinesApi
        fun runIteratively(
            context: ExecutionContext,
            stackTrace: StackTrace,
            preconditionBuilder: (CrashReproductionResult) -> ExceptionPreconditionBuilder
        ): Set<String> {
            val globalTimeLimit = kexConfig.getIntValue("crash", "globalTimeLimit", 100).seconds
            val localTimeLimit = kexConfig.getIntValue("crash", "localTimeLimit")?.seconds ?: (globalTimeLimit / 10)
            val stopAfterFirstCrash = kexConfig.getBooleanValue("crash", "stopAfterFirstCrash", false)

            val coroutineContext = newFixedThreadPoolContextWithMDC(1, "crash-dispatcher")
            val firstLine = stackTrace.stackTraceLines.first()
            return runBlocking(coroutineContext) {
                val result = withTimeoutOrNull(globalTimeLimit) {
                    var (result, _) = runCheckerWithTimeLimit(
                        localTimeLimit,
                        context,
                        StackTrace(stackTrace.firstLine, listOf(firstLine)),
                        stackTrace.targetInstructions(context),
                        ExceptionPreconditionBuilderImpl(context, stackTrace.targetException(context)),
                        ExceptionReproductionCheckerImpl(
                            context,
                            StackTrace(stackTrace.firstLine, listOf(firstLine))
                        ),
                        stopAfterFirstCrash
                    ) ?: return@withTimeoutOrNull CrashReproductionResult.empty()

                    for ((line, prev) in stackTrace.stackTraceLines.drop(1)
                        .zip(stackTrace.stackTraceLines.dropLast(1))) {
                        if (result.testClasses.isEmpty()) break
                        kexConfig.testcaseDirectory.deleteRecursively()

                        val targetInstructions = context.cm[line].body.flatten()
                            .filter { it.location.line == line.lineNumber }
                            .filterTo(mutableSetOf()) { it is CallInst && it.method.name == prev.methodName }

                        val (currentResult, _) = runCheckerWithTimeLimit(
                            localTimeLimit,
                            context,
                            StackTrace(stackTrace.firstLine, listOf(line)),
                            targetInstructions,
                            preconditionBuilder(result),
                            ExceptionReproductionCheckerImpl(
                                context,
                                StackTrace(stackTrace.firstLine, listOf(line)),
                            ),
                            stopAfterFirstCrash
                        ) ?: return@withTimeoutOrNull CrashReproductionResult.empty()

                        val reproductionChecker = ExceptionReproductionCheckerImpl(
                            context,
                            StackTrace(stackTrace.firstLine, stackTrace.stackTraceLines.takeWhile { it != line } + line)
                        )
                        val filteredTestCases = currentResult.testClasses.filterTo(mutableSetOf()) {
                            reproductionChecker.isReproduced(it)
                        }
                        result = currentResult.copy(
                            testClasses = filteredTestCases,
                            descriptors = result.descriptors.filterKeys { it in filteredTestCases },
                            preconditions = result.preconditions.filterKeys { it in filteredTestCases }
                        )
                    }
                    result
                } ?: return@runBlocking emptySet()

                val reproductionChecker = ExceptionReproductionCheckerImpl(context, stackTrace)
                val resultingTestClasses = result.testClasses.filterTo(mutableSetOf()) {
                    reproductionChecker.isReproduced(it)
                }
                if (resultingTestClasses.isEmpty()) {
                    kexConfig.testcaseDirectory.deleteRecursively()
                }
                resultingTestClasses
            }
        }
    }

    override suspend fun traverseInstruction(inst: Instruction) {
        if (shouldStopAfterFirst && generatedTestClasses.isNotEmpty()) {
            return
        }

        if (inst in targetInstructions) {
            val state = currentState ?: return
            for (preCondition in preconditionBuilder.build(inst, state)) {
                val triggerState = state.copy(
                    symbolicState = state.symbolicState + preCondition
                )
                checkExceptionAndReport(triggerState, inst, generate(preconditionBuilder.targetException.symbolicClass))
            }
        }

        super.traverseInstruction(inst)
    }

    override suspend fun nullabilityCheck(
        state: TraverserState,
        inst: Instruction,
        term: Term
    ): TraverserState? {
        val persistentState = state.symbolicState
        val nullityClause = PathClause(
            PathClauseType.NULL_CHECK,
            inst,
            path { (term eq null) equality false }
        )
        return checkReachability(
            state.copy(
                symbolicState = persistentState.copy(
                    path = persistentState.path.add(nullityClause)
                ),
                nullCheckedTerms = state.nullCheckedTerms.add(term)
            ), inst
        )
    }

    override suspend fun boundsCheck(
        state: TraverserState,
        inst: Instruction,
        index: Term,
        length: Term
    ): TraverserState? {
        val persistentState = state.symbolicState
        val zeroClause = PathClause(
            PathClauseType.BOUNDS_CHECK,
            inst,
            path { (index ge 0) equality true }
        )
        val lengthClause = PathClause(
            PathClauseType.BOUNDS_CHECK,
            inst,
            path { (index lt length) equality true }
        )
        return checkReachability(
            state.copy(
                symbolicState = persistentState.copy(
                    path = persistentState.path.add(
                        zeroClause.copy(predicate = zeroClause.predicate)
                    ).add(
                        lengthClause.copy(predicate = lengthClause.predicate)
                    )
                ),
                boundCheckedTerms = state.boundCheckedTerms.add(index to length)
            ), inst
        )
    }

    override suspend fun newArrayBoundsCheck(
        state: TraverserState,
        inst: Instruction,
        index: Term
    ): TraverserState? {
        val persistentState = state.symbolicState
        val zeroClause = PathClause(
            PathClauseType.BOUNDS_CHECK,
            inst,
            path { (index ge 0) equality true }
        )
        return checkReachability(
            state.copy(
                symbolicState = persistentState.copy(
                    path = persistentState.path.add(zeroClause)
                ),
                boundCheckedTerms = state.boundCheckedTerms.add(index to index)
            ), inst
        )
    }

    override suspend fun typeCheck(
        state: TraverserState,
        inst: Instruction,
        term: Term,
        type: KexType
    ): TraverserState? {
        val currentlyCheckedType = type.getKfgType(ctx.types)
        val persistentState = state.symbolicState
        val typeClause = PathClause(
            PathClauseType.TYPE_CHECK,
            inst,
            path { (term `is` type) equality true }
        )
        return checkReachability(
            state.copy(
                symbolicState = persistentState.copy(
                    path = persistentState.path.add(typeClause)
                ),
                typeCheckedTerms = state.typeCheckedTerms.put(term, currentlyCheckedType)
            ), inst
        )
    }

    override suspend fun check(method: Method, state: SymbolicState): Parameters<Descriptor>? {
        val (params, precondition) = method.checkAsyncAndSlice(ctx, state) ?: return null
        lastPrecondition = precondition
        return params
    }

    override fun report(inst: Instruction, parameters: Parameters<Descriptor>, testPostfix: String): Boolean {
        if (inst !in targetInstructions) return false
        val testName = rootMethod.klassName + testPostfix + testIndex.getAndIncrement()
        val generator = UnsafeGenerator(ctx, rootMethod, testName)
        generator.generate(parameters)
        val testFile = generator.emit()
        try {
            compilerHelper.compileFile(testFile)
            if (!reproductionChecker.isReproduced(generator.testKlassName)) {
                return false
            }
            generatedTestClasses += generator.testKlassName
            descriptors[generator.testKlassName] = parameters
            preconditions[generator.testKlassName] = lastPrecondition!!
            lastPrecondition = null
            return true
        } catch (e: CompilationException) {
            log.error("Failed to compile test file $testFile")
            return false
        }
    }
}
