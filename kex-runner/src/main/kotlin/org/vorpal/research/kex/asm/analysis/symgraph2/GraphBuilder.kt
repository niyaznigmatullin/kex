package org.vorpal.research.kex.asm.analysis.symgraph2

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symgraph2.heapstate.EmptyHeapState
import org.vorpal.research.kex.asm.analysis.symgraph2.heapstate.HeapState
import org.vorpal.research.kex.asm.analysis.symgraph2.heapstate.InvocationResultHeapState
import org.vorpal.research.kex.asm.analysis.symgraph2.heapstate.UnionHeapState
import org.vorpal.research.kex.descriptor.ObjectDescriptor
import org.vorpal.research.kex.ktype.kexType
import org.vorpal.research.kex.reanimator.actionsequence.ActionSequence
import org.vorpal.research.kex.smt.AsyncSMTProxySolver
import org.vorpal.research.kex.smt.Result
import org.vorpal.research.kex.state.*
import org.vorpal.research.kex.state.term.*
import org.vorpal.research.kex.state.transformer.TermRemapper
import org.vorpal.research.kex.state.transformer.collectTerms
import org.vorpal.research.kex.util.newFixedThreadPoolContextWithMDC
import org.vorpal.research.kfg.ir.Class
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.type.ClassType
import org.vorpal.research.kfg.type.Type
import org.vorpal.research.kfg.type.TypeFactory
import kotlin.system.measureTimeMillis

class GraphBuilder(val ctx: ExecutionContext, klasses: Set<Class>) : TermBuilder {
    private val publicMethods = klasses.flatMap { it.allMethods }.filter { it.isPublic }
    private val coroutineContext = newFixedThreadPoolContextWithMDC(5, "abstract-caller")
    private var calls = 0
    private val activeStates = mutableSetOf<HeapState>()
    private val allStates = mutableSetOf<HeapState>()

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
//                if (!newStates.any { it.checkIsomorphism(newState) != null }) {
//                    newStates.add(newState)
//                }
            }
        }
        newStates
    }

    private suspend fun buildNewStateOrNull(state: HeapState, c: AbsCall, p: CallResult): HeapState? {
        val predicateState = state.predicateState + p.predicateState
        val result = AsyncSMTProxySolver(ctx).use {
            it.isPathPossibleAsync(predicateState, emptyState())
        }
        if (result == Result.UnsatResult) {
            return null
        }
        val namedTerms = buildSet {
            addAll(collectTerms(predicateState) { it.isNamed })
            for (obj in p.objects) {
                addAll(obj.primitiveFields.values)
            }
        }.associateWith { generate(it.type) }
        val replacedPredicateState = TermRemapper(namedTerms).apply(predicateState)
        val freeTerms = namedTerms
            .filterKeys { it is ArgumentTerm }
            .mapKeys { it.key as ArgumentTerm }
        val absCall = c.setPrimitiveTerms(freeTerms)
        val reverseMapping = namedTerms.toList().associate { it.second to it.first }
//        println("${p.objects} $namedTerms $predicateState")
        p.objects.forEach { it.remapTerms(namedTerms) }
        check(state.terms.all { it in namedTerms })
//        println("after ${p.objects}")
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

    private fun makeReverseFieldMapping(
        state: HeapState,
        mapping: Map<GraphObject, GraphObject>
    ): Map<Term, Term> {
        return buildMap {
            for (obj1 in state.objects) {
                val obj2 = mapping.getValue(obj1)
                for ((field, term2) in obj2.primitiveFields) {
                    val term1 = obj1.primitiveFields.getValue(field)
                    put(term2, term1)
                }
            }
        }
    }

    private suspend fun addState(
        newState: HeapState
    ): Pair<HeapState?, HeapState?> {
        for (state in activeStates) {
            val objMap = state.checkIsomorphism(newState) ?: continue
            val fieldMapping = makeReverseFieldMapping(state, objMap)
            val mappedNewPredicateState = TermRemapper(fieldMapping).apply(newState.predicateState)
            val result = AsyncSMTProxySolver(ctx).use {
                it.definitelyImplies(mappedNewPredicateState, state.predicateState)
            }
            if (result) {
                return null to null
            }
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
        mappingOldToNew: Map<GraphObject, GraphObject>,
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

        private fun getAllObjectsOfSubtype(type: Type): List<GraphObject> {
            return state.activeObjects.filter {
                it.type.isSubtypeOf(this@GraphBuilder.types, type.kexType) || it == GraphObject.Null
            }
        }

        private fun backtrack(currentArgumentIndex: Int) {
            if (currentArgumentIndex == m.argTypes.size) {
                val thisCandidates = if (m.isStatic) {
                    listOf(GraphObject.Null)
                } else {
                    getAllObjectsOfSubtype(m.klass.asType)
                }
                for (thisArg in thisCandidates) {
                    callsList.add(AbsCall(m, thisArg, argumentsList.toList()))
                }
                return
            }
            val argType = m.argTypes[currentArgumentIndex]
            if (argType is ClassType) {
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

    private fun getAbstractCalls(state: HeapState, m: Method): Collection<AbsCall> {
        return AbsCallGenerator(state, m).generate()
    }

    fun build(maxL: Int) {
        runBlocking(coroutineContext) {
            val time = measureTimeMillis {
                var oldStates = mutableSetOf<HeapState>(EmptyHeapState)
                addState(EmptyHeapState)
                for (l in 0 until maxL) {
                    oldStates = exploreStates(oldStates)
                    if (oldStates.isEmpty()) {
                        break
                    }
                    println("oldStates iteration $l: ${oldStates.size}")
                    if (l == 0) {
                        println(oldStates)
                    }
                }
                println("the number of states = ${allStates.size}")
                val stateEnumeration = allStates.withIndex().associate { (index, state) -> state to index }
                println(allStates.joinToString(separator = "\n") { state ->
                    state.toString(stateEnumeration)
                })
                println("Abstract calls: $calls")
            }
            println("Took ${time}ms")
        }
    }

    suspend fun restoreActionSequences(objectDescriptors: Set<ObjectDescriptor>): Pair<List<ActionSequence>, Map<ObjectDescriptor, ActionSequence>>? {
        for (state in allStates) {
            val result = state.getMappingToConcreteOrNull(ctx, objectDescriptors)
            if (result != null) {
                return result
            }
        }
        return null
//        val mapping = result.mapping
//        val stateEnumeration = allStates.withIndex().associate { (index, state) -> state to index }
//        return buildString {
//            appendLine("Found mapping")
//            appendLine(state.toString(stateEnumeration))
//            appendLine(objectDescriptors)
//            appendLine(mapping.mapping.mapKeys { it.key.term }.mapValues { state.getObjectIndex(it.value) })
//            appendLine(mapping.terms)
//            appendLine(result.callList)
//        }
    }
}
