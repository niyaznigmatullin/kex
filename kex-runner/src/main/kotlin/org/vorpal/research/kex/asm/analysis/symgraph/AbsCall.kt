package org.vorpal.research.kex.asm.analysis.symgraph

import kotlinx.coroutines.runBlocking
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kfg.ir.Method

data class AbsCall(val method: Method, val thisArg: GraphObject, val arguments: List<GraphObject>) {
    companion object {
        var calls = 0
    }
    fun call(ctx: ExecutionContext, state: HeapState): Collection<CallResult> {
//        println("Calling here: $state, with abstract call $this")
        calls++
        return runBlocking {
            MethodAbstractlyInvocator(ctx, method).invokeMethod(state, thisArg, arguments)
        }
    }
}
