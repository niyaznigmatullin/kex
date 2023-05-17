package org.vorpal.research.kex.asm.analysis.symgraph2

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symgraph2.heapstate.HeapState
import org.vorpal.research.kex.state.term.ArgumentTerm
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kthelper.logging.log

data class AbsCall(val method: Method, val thisArg: GraphObject, val arguments: List<Argument>) {
    suspend fun call(ctx: ExecutionContext, state: HeapState): Collection<CallResult> {
        log.debug("Calling here: $state, with abstract call $this")
        return MethodAbstractlyInvocator(ctx, method).invokeMethod(state, thisArg, arguments)
    }

    fun setPrimitiveTerms(mapping: Map<ArgumentTerm, Term>): AbsCall {
        val arguments = arguments.toMutableList()
        for ((arg, to) in mapping) {
            if (arguments[arg.index] == NoneArgument) {
                arguments[arg.index] = PrimitiveArgument(to)
            }
        }
        return AbsCall(method, thisArg, arguments)
    }
}
