package org.vorpal.research.kex.asm.analysis.symgraph

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.descriptor.ObjectDescriptor
import org.vorpal.research.kex.ktype.KexClass
import org.vorpal.research.kex.ktype.kexType
import org.vorpal.research.kfg.ir.Class
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.type.ClassType
import org.vorpal.research.kfg.type.Type
import org.vorpal.research.kfg.type.TypeFactory

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
                        newStates.add(HeapState(state, c, p.objects))
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
            state.objects.filter { it.descriptor.type.isSubtypeOf(this@GraphBuilder.types, type.kexType) }

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
        var oldStates = mutableSetOf(HeapState(null, null, listOf(GraphObject.Null)))
        for (l in 0 until maxL) {
            oldStates = exploreStates(oldStates)
            if (oldStates.isEmpty()) {
                break
            }
            println("oldStates iteration $l: $oldStates")
        }
    }
}