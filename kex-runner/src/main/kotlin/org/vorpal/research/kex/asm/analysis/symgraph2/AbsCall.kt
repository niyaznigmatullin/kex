package org.vorpal.research.kex.asm.analysis.symgraph2

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kfg.ir.Method

data class AbsCall(val method: Method, val thisArg: GraphObject, val arguments: List<GraphObject>) {
    suspend fun call(ctx: ExecutionContext, state: HeapState): Collection<CallResult> {
//        println("Calling here: $state, with abstract call $this")
        return MethodAbstractlyInvocator(ctx, method).invokeMethod(state, thisArg, arguments)
    }
}
