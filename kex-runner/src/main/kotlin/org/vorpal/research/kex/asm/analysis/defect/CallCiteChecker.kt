@file:Suppress("SameParameterValue")

package org.vorpal.research.kex.asm.analysis.defect

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.annotations.AnnotationManager
import org.vorpal.research.kex.asm.manager.MethodManager
import org.vorpal.research.kex.asm.state.PredicateStateAnalysis
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.ktype.KexBool
import org.vorpal.research.kex.ktype.KexInt
import org.vorpal.research.kex.ktype.kexType
import org.vorpal.research.kex.reanimator.Reanimator
import org.vorpal.research.kex.smt.Result
import org.vorpal.research.kex.smt.SMTProxySolver
import org.vorpal.research.kex.state.BasicState
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kex.state.StateBuilder
import org.vorpal.research.kex.state.predicate.CallPredicate
import org.vorpal.research.kex.state.predicate.require
import org.vorpal.research.kex.state.term.CallTerm
import org.vorpal.research.kex.state.term.FieldTerm
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kex.state.term.term
import org.vorpal.research.kex.state.transformer.AnnotationAdapter
import org.vorpal.research.kex.state.transformer.BasicInvariantsTransformer
import org.vorpal.research.kex.state.transformer.BoolTypeAdapter
import org.vorpal.research.kex.state.transformer.ClassMethodAdapter
import org.vorpal.research.kex.state.transformer.ConcreteImplInliner
import org.vorpal.research.kex.state.transformer.ConstEnumAdapter
import org.vorpal.research.kex.state.transformer.ConstStringAdapter
import org.vorpal.research.kex.state.transformer.ConstantPropagator
import org.vorpal.research.kex.state.transformer.DoubleTypeAdapter
import org.vorpal.research.kex.state.transformer.EqualsTransformer
import org.vorpal.research.kex.state.transformer.FieldNormalizer
import org.vorpal.research.kex.state.transformer.IntrinsicAdapter
import org.vorpal.research.kex.state.transformer.KexIntrinsicsAdapter
import org.vorpal.research.kex.state.transformer.MemorySpacer
import org.vorpal.research.kex.state.transformer.Optimizer
import org.vorpal.research.kex.state.transformer.RecursiveInliner
import org.vorpal.research.kex.state.transformer.ReflectionInfoAdapter
import org.vorpal.research.kex.state.transformer.Slicer
import org.vorpal.research.kex.state.transformer.StaticFieldInliner
import org.vorpal.research.kex.state.transformer.StensgaardAA
import org.vorpal.research.kex.state.transformer.TermCollector
import org.vorpal.research.kex.state.transformer.TermRenamer
import org.vorpal.research.kex.state.transformer.TypeInfoMap
import org.vorpal.research.kex.state.transformer.TypeNameAdapter
import org.vorpal.research.kex.state.transformer.collectArguments
import org.vorpal.research.kex.state.transformer.collectAssumedTerms
import org.vorpal.research.kex.state.transformer.collectRequiredTerms
import org.vorpal.research.kex.state.transformer.collectStaticTypeInfo
import org.vorpal.research.kex.state.transformer.collectVariables
import org.vorpal.research.kex.state.transformer.transform
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.Package
import org.vorpal.research.kfg.ir.ConcreteClass
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.StringConstant
import org.vorpal.research.kfg.ir.value.Value
import org.vorpal.research.kfg.ir.value.instruction.ArrayStoreInst
import org.vorpal.research.kfg.ir.value.instruction.CallInst
import org.vorpal.research.kfg.ir.value.instruction.Instruction
import org.vorpal.research.kfg.visitor.MethodVisitor
import org.vorpal.research.kfg.visitor.executePipeline
import org.vorpal.research.kthelper.assert.unreachable
import org.vorpal.research.kthelper.logging.log
import org.vorpal.research.kthelper.runIf
import org.vorpal.research.kthelper.tryOrNull
import java.nio.file.Path

private val logQuery by lazy { kexConfig.getBooleanValue("smt", "logQuery", false) }
private val isMemspacingEnabled by lazy { kexConfig.getBooleanValue("smt", "memspacing", true) }
private val isSlicingEnabled by lazy { kexConfig.getBooleanValue("smt", "slicing", false) }

class CallCiteChecker(
    val ctx: ExecutionContext,
    private val callCiteTarget: Package,
    val psa: PredicateStateAnalysis
) : MethodVisitor {
    override val cm: ClassManager
        get() = ctx.cm
    private val dm get() = DefectManager
    private val im get() = MethodManager.KexIntrinsicManager
    private lateinit var method: Method
    private lateinit var callCites: Set<Instruction>
    private lateinit var generator: Reanimator
    private var testIndex = 0

    override fun cleanup() {}

    override fun visit(method: Method) {
        this.method = method
        callCites = getAllCallCites(method)
        initializeGenerator()

        super.visit(method)

        generator.emit()
    }

    override fun visitCallInst(inst: CallInst) {
        val state = getState(inst) ?: return

        val handler = { callCite: Instruction, ps: PredicateState, remapper: TermRenamer ->
            when (inst.method) {
                im.kexAssert(cm) -> checkAssertion(
                    inst,
                    callCite,
                    ps,
                    getAllAssertions(inst.args[0]).mapTo(mutableSetOf()) { remapper.transformTerm(it) }
                )

                im.kexAssertWithId(cm) -> checkAssertion(
                    inst,
                    callCite,
                    ps,
                    getAllAssertions(inst.args[1]).mapTo(mutableSetOf()) { remapper.transformTerm(it) },
                    (inst.args[0] as? StringConstant)?.value
                )

                else -> {}
            }
        }

        for (callCite in callCites) {
            val csState = getState(callCite) ?: continue
            val (preparedState, remapper) = buildInlinedState(csState, state) ?: continue
            handler(callCite, preparedState, remapper)
        }
    }

    private fun initializeGenerator() {
        generator = Reanimator(ctx, psa, method)
        testIndex = 0
    }

    infix fun Method.overrides(other: Method): Boolean = when {
        this == other -> true
        other.isFinal -> false
        this.klass !is ConcreteClass -> false
        other.klass !is ConcreteClass -> false
        this.name != other.name -> false
        this.desc != other.desc -> false
        !this.klass.isInheritorOf(other.klass) -> false
        else -> true
    }

    private fun getState(instruction: Instruction) =
        psa.builder(instruction.parent.method).getInstructionState(instruction)

    private fun buildInlinedState(
        callState: PredicateState,
        inlinedState: PredicateState
    ): Pair<PredicateState, TermRenamer>? {
        val callPredicate = (callState.takeLast(1) as? BasicState)?.first()
            ?: return null
        val filteredState = callState.dropLast(1)
        if (callPredicate !is CallPredicate) {
            log.warn("Unknown predicate in call cite: $callPredicate")
            return null
        }
        val callTerm = callPredicate.callTerm as CallTerm
        val (inlinedThis, inlinedArgs) = collectArguments(inlinedState)
        val mappings = run {
            val result = mutableMapOf<Term, Term>()
            if (inlinedThis != null) {
                result += inlinedThis to callTerm.owner
            } else if (!method.isStatic) {
                result += term { `this`(method.klass.kexType) } to callTerm.owner
            }
            for ((index, arg) in callTerm.arguments.withIndex()) {
                result += (inlinedArgs[index] ?: term { arg(arg.type, index) }) to arg
            }
            result
        }
        val remapper = TermRenamer("call.cite.inlined", mappings)
        val preparedState = remapper.apply(inlinedState)
        return (filteredState + preparedState) to remapper
    }

    private fun getAllAssertions(assertionsArray: Value): Set<Term> = method.body.flatten()
        .asSequence()
        .mapNotNull { it as? ArrayStoreInst }
        .filter { it.arrayRef == assertionsArray }
        .map { it.value }
        .mapTo(mutableSetOf()) { term { value(it) } }

    private fun getAllCallCites(method: Method): Set<Instruction> {
        val result = mutableSetOf<Instruction>()
        executePipeline(cm, callCiteTarget) {
            +object : MethodVisitor {
                override val cm: ClassManager
                    get() = this@CallCiteChecker.cm

                override fun cleanup() {}

                override fun visitCallInst(inst: CallInst) {
                    val calledMethod = inst.method
                    if (calledMethod overrides method)
                        result += inst
                }
            }
        }
        return result
    }

    private fun checkAssertion(
        inst: Instruction,
        callCite: Instruction,
        state: PredicateState,
        assertions: Set<Term>,
        id: String? = null
    ): Boolean {
        log.debug("Checking for assertion failure: ${inst.print()} at ${callCite.print()}")
        log.debug("State: {}", state)
        val assertionQuery = assertions.map {
            when (it.type) {
                is KexBool -> require { it equality true }
                is KexInt -> require { it equality 1 }
                else -> unreachable { log.error("Unknown assertion variable: $it") }
            }
        }.fold(StateBuilder()) { builder, predicate ->
            builder += predicate
            builder
        }.apply()

        val (checkerState, result) = check(state, assertionQuery)
        return when (result) {
            is Result.SatResult -> {
                val (path, testName) = getTest("Assertion", checkerState, result, callCite) ?: (null to null)
                val callStack = listOf(
                    "$method - ${inst.location}",
                    "${callCite.parent.parent} - ${callCite.location}"
                )
                dm += Defect.assert(callStack, id, path, testName)
                false
            }

            else -> true
        }
    }

    private fun prepareState(ps: PredicateState, typeInfoMap: TypeInfoMap) = transform(ps) {
        +AnnotationAdapter(method, AnnotationManager.defaultLoader)
        +RecursiveInliner(psa) { index, psa ->
            ConcreteImplInliner(method.cm.type, typeInfoMap, psa, inlineIndex = index)
        }
        +StaticFieldInliner(ctx, psa)
        +IntrinsicAdapter
        +KexIntrinsicsAdapter()
        +EqualsTransformer()
        +DoubleTypeAdapter()
        +BasicInvariantsTransformer(method)
        +ReflectionInfoAdapter(method, ctx.loader)
        +Optimizer()
        +ConstantPropagator
        +BoolTypeAdapter(method.cm.type)
        +ClassMethodAdapter(method.cm)
        +ConstEnumAdapter(ctx)
        +ConstStringAdapter(method.cm.type)
        +FieldNormalizer(method.cm)
        +TypeNameAdapter(ctx)
    }

    private fun getTest(
        nameBase: String,
        state: PredicateState,
        result: Result.SatResult,
        callCite: Instruction
    ): Pair<Path, String>? = tryOrNull {
        val testName = "test$nameBase${testIndex++}"
        generator.generate(testName, callCite.parent.method, state, result.model)
        generator.printer.targetFile.toPath() to testName
    }

    private fun check(analysisState: PredicateState, analysisQuery: PredicateState): Pair<PredicateState, Result> {
        val staticTypeInfoMap = collectStaticTypeInfo(types, analysisState, TypeInfoMap())
        var state = prepareState(analysisState, staticTypeInfoMap)
        var query = analysisQuery

        // memspacing
        runIf(isMemspacingEnabled) {
            log.debug("Memspacing started...")
            val spacer = MemorySpacer((state.builder() + query).apply())
            state = spacer.apply(state)
            query = spacer.apply(query)
            log.debug("Memspacing finished")
        }

        // slicing
        runIf(isSlicingEnabled) {
            log.debug("Slicing started...")

            val slicingTerms = run {
                val (`this`, arguments) = collectArguments(state)

                val results = hashSetOf<Term>()

                if (`this` != null) results += `this`
                results += arguments.values
                results += collectVariables(state).filter { it is FieldTerm && it.owner == `this` }
                results += collectAssumedTerms(state)
                results += collectRequiredTerms(state)
                results += TermCollector.getFullTermSet(query)
                results
            }

            val aa = StensgaardAA()
            aa.apply(state)
            log.debug("State size before slicing: ${state.size}")
            state = Slicer(state, query, slicingTerms, aa).apply(state)
            log.debug("State size after slicing: ${state.size}")
            log.debug("Slicing finished")
        }

        state = Optimizer().apply(state)
        query = Optimizer().apply(query)
        if (logQuery) {
            log.debug("Simplified state: {}", state)
            log.debug("Query: {}", query)
        }

        val result = SMTProxySolver(ctx).use {
            it.isViolated(state, query)
        }
        log.debug("Acquired {}", result)
        return state to result
    }

}
