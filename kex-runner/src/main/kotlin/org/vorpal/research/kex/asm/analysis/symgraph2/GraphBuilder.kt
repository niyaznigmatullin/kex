package org.vorpal.research.kex.asm.analysis.symgraph2

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.ktype.KexClass
import org.vorpal.research.kex.ktype.kexType
import org.vorpal.research.kex.util.newFixedThreadPoolContextWithMDC
import org.vorpal.research.kfg.ir.Class
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.type.ClassType
import org.vorpal.research.kfg.type.Type
import org.vorpal.research.kfg.type.TypeFactory
import kotlin.system.measureTimeMillis

class GraphBuilder(val ctx: ExecutionContext, private val klass: Class) {
    private val publicMethods = klass.allMethods.filter { it.isPublic }
    private val coroutineContext = newFixedThreadPoolContextWithMDC(5, "abstract-caller")
    private var calls = 0

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
                allCalls.map { c -> async { c.call(ctx, state).map { Pair(c, it) } } }.awaitAll().flatten()
            for ((c, p) in callResults) {
                val newState = HeapState(state, c, p.objects, p.activeObjects)
                if (!newStates.any { it.checkIsomorphism(newState) }) {
                    newStates.add(newState)
                }
            }
        }
        newStates
    }

    private inner class AbsCallGenerator(val state: HeapState, val m: Method) {
        private val argumentsList = mutableListOf<GraphObject>()
        private val callsList = mutableListOf<AbsCall>()

        private fun getAllObjectsOfSubtype(type: Type) =
            state.activeObjects.filter { it.type.isSubtypeOf(this@GraphBuilder.types, type.kexType) }

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
            argumentsList.add(GraphObject.Null)
            backtrack(currentArgumentIndex + 1)
            argumentsList.removeLast()
            val argType = m.argTypes[currentArgumentIndex]
            if (argType is ClassType) {
                for (arg in getAllObjectsOfSubtype(argType)) {
                    argumentsList.add(arg)
                    backtrack(currentArgumentIndex + 1)
                    argumentsList.removeLast()
                }
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
                var oldStates = mutableSetOf(HeapState(null, null, listOf(GraphObject.Null), setOf(GraphObject.Null)))
                val allStates = mutableListOf<HeapState>()
                for (l in 0 until maxL) {
                    oldStates = exploreStates(oldStates)
                    if (oldStates.isEmpty()) {
                        break
                    }
                    println("oldStates iteration $l: ${oldStates.size}")
                    if (l == 0) {
                        println(oldStates)
                    }
                    for (state in oldStates) {
                        if (!allStates.any { otherState ->
                                otherState.checkIsomorphism(state)
                            }) {
                            allStates.add(state)
                        }
                    }
                }
                println("the number of states = ${allStates.size}")
                println(allStates.withIndex().joinToString(separator = "\n") { (stateIndex, state) ->
                    stateToString(stateIndex, state)
                })
                println("Abstract calls: ${calls}")
            }
            println("Took ${time}ms")
        }
    }

    companion object {
        fun stateToString(stateIndex: Int, state: HeapState): String {
            val stateRepr = buildString {
                appendLine("[[State #$stateIndex]]")
                val callSequence = mutableListOf<String>()
                var c: HeapState? = state
                while (c?.absCall != null) {
                    callSequence.add(c.absCall!!.method.toString())
                    c = c.prevHeapState
                }
                appendLine(callSequence.reversed())
                appendLine("Nodes = ${state.objects.size}, active = ${state.activeObjects.size}")
                val stateToIndex = state.objects.withIndex().associate { (i, v) ->
                    val id = if (v == GraphObject.Null) {
                        "null"
                    } else if (state.activeObjects.contains(v)) {
                        "a$i"
                    } else {
                        "$i"
                    }
                    Pair(v, id)
                }
                for (d in state.objects) {
                    if (d.type !is KexClass) {
                        continue
                    }
                    for ((_, value) in d.objectFields) {
                        val from = stateToIndex.getValue(d)
                        val to = stateToIndex.getValue(value)
                        appendLine("$from -> $to")
                    }
                }
            }
            return stateRepr
        }
    }
}