package org.vorpal.research.kex.asm.analysis.symgraph2

import kotlinx.coroutines.withTimeoutOrNull
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symgraph2.heapstate.HeapState
import org.vorpal.research.kex.asm.analysis.symgraph2.objects.GraphVertex
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.state.term.ArgumentTerm
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kthelper.logging.log
import kotlin.time.Duration.Companion.seconds

data class AbsCall(val method: Method, val thisArg: GraphVertex, val arguments: List<Argument>) {
    companion object {
        val timeLimit = kexConfig.getIntValue("symgraph", "symbolicCallTimeout", 20)
    }

    suspend fun call(ctx: ExecutionContext, state: HeapState): Collection<CallResult> {
        val invocator = MethodAbstractlyInvocator(ctx, method, this@AbsCall)
        val result = withTimeoutOrNull(timeLimit.seconds) {
            log.debug("Calling here: {}, with abstract call {}", state, this@AbsCall)
            invocator.invokeMethod(state, thisArg, arguments)
            log.debug("Finished successfully: {}, with abstract call {}", state, this@AbsCall)
        }
        if (result == null) {
            log.debug("Finished with timeout: {}, with abstract call {}", state, this@AbsCall)
        }
        return invocator.getGeneratedInvocationPaths()
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
