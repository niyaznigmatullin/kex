package org.vorpal.research.kex.asm.analysis.symgraph2

import kotlinx.coroutines.*
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symgraph2.heapstate.*
import org.vorpal.research.kex.asm.analysis.symgraph2.objects.GraphObject
import org.vorpal.research.kex.asm.analysis.symgraph2.objects.GraphVertex
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.ktype.KexClass
import org.vorpal.research.kex.ktype.isGraphObject
import org.vorpal.research.kex.ktype.kexType
import org.vorpal.research.kex.smt.AsyncSMTProxySolver
import org.vorpal.research.kex.smt.Result
import org.vorpal.research.kex.state.*
import org.vorpal.research.kex.state.predicate.path
import org.vorpal.research.kex.state.term.*
import org.vorpal.research.kex.state.transformer.TermRemapper
import org.vorpal.research.kex.state.transformer.collectTerms
import org.vorpal.research.kex.util.newFixedThreadPoolContextWithMDC
import org.vorpal.research.kfg.ir.Class
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.type.Type
import org.vorpal.research.kfg.type.TypeFactory
import org.vorpal.research.kthelper.assert.unreachable
import kotlin.system.measureTimeMillis
import org.vorpal.research.kthelper.logging.log
import kotlin.time.Duration.Companion.milliseconds

class GraphBuilder(val ctx: ExecutionContext, klasses: Set<Class>) : TermBuilder {
    private val publicMethods = klasses.flatMap { it.allMethods }.filter {
        it.isPublic && !it.isNative && (!it.isStatic || it.returnType.kexType is KexClass) && !it.isAbstract
    }
    private val coroutineContext = newFixedThreadPoolContextWithMDC(
        kexConfig.getIntValue("symbolic", "numberOfExecutors", 8),
        "abstract-caller"
    )
    private var calls = 0
    private val activeStates = mutableSetOf<HeapState>()
    val allStates = mutableSetOf<HeapState>()
    val allSymbolicStates = mutableSetOf<HeapSymbolicState>()

    val types: TypeFactory
        get() = ctx.types

    private suspend fun exploreStates(oldStates: MutableSet<HeapState>): MutableSet<HeapState> = coroutineScope {
        val newStates = mutableSetOf<HeapState>()
        while (oldStates.isNotEmpty()) {
            val state = oldStates.iterator().next()
            oldStates.remove(state)
            val allCalls = publicMethods.flatMap { m -> getAbstractCalls(state, m) }
            calls += allCalls.size
            val callResults =
                allCalls.map { c ->
                    async {
                        c.call(ctx, state).mapNotNull { buildNewStateOrNull(state, c, it) }
                    }
                }.awaitAll().flatten()
            for (newState in callResults) {
                val (unionState, stateToRemove) = addState(newState)
                if (unionState == null) {
                    continue
                }
                if (unionState === newState) {
                    newStates.add(newState)
                    continue
                }
                if (stateToRemove != null) {
                    if (stateToRemove in oldStates) {
                        oldStates.remove(stateToRemove)
                        oldStates.add(unionState)
                    } else {
//                        check(stateToRemove in newStates)
                        newStates.remove(stateToRemove)
                        newStates.add(unionState)
                    }
                }
            }
        }
        newStates
    }

    private suspend fun buildNewStateOrNull(state: HeapState, c: AbsCall, p: CallResult): HeapState? {
        val predicateState = state.predicateState + p.predicateState
        val result = try {
            withTimeout(10000) {
                AsyncSMTProxySolver(ctx).use {
                    it.isPathPossibleAsync(predicateState, emptyState())
                }
            }
        } catch (_: TimeoutCancellationException) {
            log.debug("Timeout on checking the full path's predicate for call: $c")
            null
        }
        if (result == null || result is Result.UnsatResult) {
            return null
        }
        val namedTerms = buildSet {
            addAll(collectTerms(predicateState) { it.isNamed })
            for (obj in p.objects) {
                when (obj) {
                    is GraphObject -> addAll(obj.objectFields.values.filterIsInstance<GraphPrimitive>().map { it.term }
                        .filter { it.isNamed })
                }
            }
            for ((index, arg) in c.arguments.withIndex()) {
                if (arg is NoneArgument) {
                    add(arg(c.method.argTypes[index].kexType, index))
                }
            }
            addAll(state.terms)
        }.filter {
            when (it) {
                is ValueTerm, is ArgumentTerm -> true
                is ConstClassTerm, is ConstStringTerm, is FieldTerm, is ArrayIndexTerm -> false
                is StaticClassRefTerm -> unreachable("StaticClassRefTerm unexpected")
                is ReturnValueTerm -> unreachable("ReturnValueTerm unexpected")
                else -> unreachable("any ${it.javaClass} unexpected")
            }
        }.associateWith { generate(it.type) }
        val replacedPredicateState = TermRemapper(namedTerms).apply(predicateState)
        val freeTerms = namedTerms
            .filterKeys { it is ArgumentTerm }
            .mapKeys { it.key as ArgumentTerm }
        val absCall = c.setPrimitiveTerms(freeTerms)
        check(absCall.arguments.all { it != NoneArgument })
        val reverseMapping = namedTerms.toList().associate { it.second to it.first }
        p.objects.forEach { it.remapTerms(namedTerms) }
        return InvocationResultHeapState(
            p.objects,
            p.activeObjects,
            replacedPredicateState,
            absCall,
            state,
            reverseMapping,
            p.activeObjectsMapping,
            p.returnValue
        )
    }

    private suspend fun addState(
        newState: HeapState
    ): Pair<HeapState?, HeapState?> {
        log.debug("Trying to add new state: ${newState.toString(emptyMap())}")
        for (state in activeStates) {
            val objMap = state.checkIsomorphism(newState) ?: continue
            val fieldMapping = state.makeReverseFieldMapping(objMap)
            val mappedNewPredicateState = TermRemapper(fieldMapping).apply(newState.predicateState)
            val result = withTimeoutOrNull(500.milliseconds) {
                AsyncSMTProxySolver(ctx).use {
                    it.definitelyImplies(mappedNewPredicateState, state.predicateState)
                }
            }
            if (result != null && result) {
                log.debug("New state $newState is implied by old state $state")
                return null to null
            }
            log.debug("Checking for implication: $mappedNewPredicateState and ${state.predicateState} results in no implication $result")
            val unionState = merge(state, newState, objMap, fieldMapping, mappedNewPredicateState)
            allStates.add(newState)
            allStates.add(unionState)
            activeStates.remove(state)
            activeStates.add(unionState)
            return unionState to state
        }
        allStates.add(newState)
        activeStates.add(newState)
        return newState to null
    }

    private fun merge(
        oldState: HeapState,
        newState: HeapState,
        mappingOldToNew: Map<GraphVertex, GraphVertex>,
        termsNewToOld: Map<Term, Term>,
        mappedNewPredicateState: PredicateState,
    ): UnionHeapState {
        val unionPredicateState = ChoiceState(listOf(oldState.predicateState, mappedNewPredicateState))
        val termsOldToNew = termsNewToOld.asSequence().associate { it.value to it.key }
        val terms = buildSet {
            addAll(oldState.terms)
            addAll(newState.terms)
            removeAll(termsOldToNew.values)
        }
        return UnionHeapState(unionPredicateState, terms, oldState, newState, mappingOldToNew, termsOldToNew)
    }

    private inner class AbsCallGenerator(val state: HeapState, val m: Method) {
        private val argumentsList = mutableListOf<Argument>()
        private val callsList = mutableListOf<AbsCall>()

        private fun getAllObjectsOfSubtype(type: Type): List<GraphVertex> {
            return state.activeObjects.filter {
                it.type.isSubtypeOf(this@GraphBuilder.types, type.kexType) || it == GraphValue.Null
            }
        }

        private fun backtrack(currentArgumentIndex: Int) {
            if (currentArgumentIndex == m.argTypes.size) {
                val thisCandidates = if (m.isStatic || m.isConstructor) {
                    listOf(GraphValue.Null)
                } else {
                    getAllObjectsOfSubtype(m.klass.asType).filter { it != GraphValue.Null }
                }
                for (thisArg in thisCandidates) {
                    callsList.add(AbsCall(m, thisArg, argumentsList.toList()))
                }
                return
            }
            val argType = m.argTypes[currentArgumentIndex]
            if (argType.kexType.isGraphObject) {
                for (arg in getAllObjectsOfSubtype(argType)) {
                    argumentsList.add(ObjectArgument(arg))
                    backtrack(currentArgumentIndex + 1)
                    argumentsList.removeLast()
                }
            } else {
                argumentsList.add(NoneArgument)
                backtrack(currentArgumentIndex + 1)
                argumentsList.removeLast()
            }
        }

        fun generate(): Collection<AbsCall> {
            backtrack(0)
            return callsList
        }
    }

    fun getAbstractCalls(state: HeapState, m: Method): Collection<AbsCall> {
        return AbsCallGenerator(state, m).generate()
    }

    fun build(maxL: Int) {
        log.debug("Building graph for methods: $publicMethods")
        runBlocking(coroutineContext) {
            val time = measureTimeMillis {
                var oldStates = mutableSetOf<HeapState>(EmptyHeapState)
                addState(EmptyHeapState)
                for (l in 0 until maxL) {
                    oldStates = exploreStates(oldStates)
                    if (oldStates.isEmpty()) {
                        break
                    }
                    log.debug("oldStates iteration $l: ${oldStates.size}")
                }
                log.debug("the number of states = ${allStates.size}")
                allSymbolicStates.addAll(allStates.map { it.buildSymbolicState() })
                val stateEnumeration = allStates.withIndex().associate { (index, state) -> state to index }
                log.debug(allStates.joinToString(separator = "\n") { state ->
                    state.toString(stateEnumeration)
                })
                log.debug("Abstract calls: $calls")
            }
            log.debug("Took ${time}ms")
        }
        log.debug("End building graph")
    }

    private fun HeapState.buildSymbolicState(): HeapSymbolicState {
        val objectTerms = buildMap {
            for (v in objects) {
                val term = if (v == GraphValue.Null) {
                    const(null)
                } else {
                    generate(v.type)
                }
                put(v, term)
            }
        }
        val predicates = buildList {
            for (v in objectTerms.values) {
                for (u in objectTerms.values) {
                    if (v == u) {
                        break
                    }
                    add(path { v inequality u })
                }
            }
            for ((obj, term) in objectTerms) {
                if (obj !is GraphObject) {
                    continue
                }
                for ((field, fieldValue) in obj.objectFields) {
                    when (fieldValue) {
                        is GraphPrimitive -> add(path { term.field(field).load() equality fieldValue.term })
                        is GraphVertex -> add(path {
                            term.field(field).load() equality objectTerms.getValue(fieldValue)
                        })
                    }
                }
//                for ((field, fieldValue) in obj.primitiveFields) {
//                    add(path { term.field(field).load() equality fieldValue })
//                }
            }
        }
        return HeapSymbolicState(this, predicateState + BasicState(predicates), objectTerms)
    }
}
