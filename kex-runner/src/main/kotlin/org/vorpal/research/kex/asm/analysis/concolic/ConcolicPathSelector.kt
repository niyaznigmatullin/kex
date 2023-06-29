package org.vorpal.research.kex.asm.analysis.concolic

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.util.SuspendableIterator
import org.vorpal.research.kex.trace.symbolic.PathClause
import org.vorpal.research.kex.trace.symbolic.PersistentSymbolicState
import org.vorpal.research.kex.trace.symbolic.protocol.ExecutionCompletedResult
import org.vorpal.research.kfg.ir.Method

interface ConcolicPathSelector : SuspendableIterator<PersistentSymbolicState> {
    val ctx: ExecutionContext

    suspend fun isEmpty(): Boolean
    suspend fun addExecutionTrace(method: Method, result: ExecutionCompletedResult)
    fun reverse(pathClause: PathClause): PathClause?
}
