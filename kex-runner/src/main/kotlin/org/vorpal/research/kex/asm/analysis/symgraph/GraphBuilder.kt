package org.vorpal.research.kex.asm.analysis.symgraph

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.descriptor.ConstantDescriptor
import org.vorpal.research.kex.descriptor.ObjectDescriptor
import org.vorpal.research.kex.ktype.KexClass
import org.vorpal.research.kex.ktype.KexNull
import org.vorpal.research.kex.ktype.kexType
import org.vorpal.research.kfg.ir.Class
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.type.ClassType
import org.vorpal.research.kfg.type.Type
import org.vorpal.research.kfg.type.TypeFactory
import kotlin.system.measureTimeMillis

class GraphBuilder(val ctx: ExecutionContext, private val klass: Class) {
    private val publicMethods = klass.allMethods.filter { it.isPublic }

    val types: TypeFactory
        get() = ctx.types

    private fun exploreStates(oldStates: MutableSet<HeapState>): MutableSet<HeapState> {
        val newStates = mutableSetOf<HeapState>()
        while (oldStates.isNotEmpty()) {
            val state = oldStates.iterator().next()
            oldStates.remove(state)
            for (m in publicMethods) {
                for (c in getAbstractCalls(state, m)) {
                    for (p in c.call(ctx, state)) {
                        val newState = HeapState(state, c, p.objects, p.activeObjects)
                        if (!newStates.any { it.checkIsomorphism(newState) }) {
                            newStates.add(newState)
                        }
                    }
                }
            }
        }
        return newStates
    }

    private inner class AbsCallGenerator(val state: HeapState, val m: Method) {
        private val argumentsList = mutableListOf<GraphObject>()
        private val callsList = mutableListOf<AbsCall>()

        private fun getAllObjectsOfSubtype(type: Type) =
            state.activeObjects.filter { it.descriptor.type.isSubtypeOf(this@GraphBuilder.types, type.kexType) }

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
            allStates.withIndex().forEach { (stateIndex, state) ->
                val stateRepr = buildString {
                    appendLine("[[State #$stateIndex]]")
                    val callSequence = mutableListOf<String>()
                    var c: HeapState? = state
                    while (c?.absCall != null) {
                        callSequence.add(c.absCall!!.method.toString())
                        c = c.prevHeapState
                    }
                    appendLine(callSequence.reversed())
                    appendLine("Nodes = ${state.objects.size}")
                    val stateToIndex = state.objects.map { it.descriptor }.withIndex().associate { (i, v) ->
                        val id = if (v == ConstantDescriptor.Null) {
                            "null"
                        } else if (state.activeObjects.any { it.descriptor == v }) {
                            "a$i"
                        } else {
                            "$i"
                        }
                        Pair(v, id)
                    }
                    for (obj in state.objects) {
                        val d = obj.descriptor
                        if (d.type !is KexClass) {
                            continue
                        }
                        d as ObjectDescriptor
                        for ((field, desc) in d.fields) {
                            if (desc.type is KexClass || desc.type is KexNull) {
                                val from = stateToIndex.getValue(obj.descriptor)
                                val to = stateToIndex.getValue(desc)
                                appendLine("$from -> $to")
                            }
                        }
                    }
                }
                println(stateRepr)
            }
            println("Abstract calls: ${AbsCall.calls}")
        }
        println("Took ${time}ms")
    }
}