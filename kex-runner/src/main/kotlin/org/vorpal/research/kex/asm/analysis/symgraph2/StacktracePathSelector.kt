package org.vorpal.research.kex.asm.analysis.symgraph2

import org.vorpal.research.kex.asm.analysis.symbolic.SymbolicPathSelector
import org.vorpal.research.kex.asm.analysis.symbolic.TraverserState
import org.vorpal.research.kfg.ir.BasicBlock
import java.util.*


class StacktracePathSelector : SymbolicPathSelector {
    private val queue = PriorityQueue(compareBy<Pair<TraverserState, BasicBlock>> { it.first.stackTrace.size }.thenBy { it.first.symbolicState.clauses.size })

    override suspend fun add(state: TraverserState, block: BasicBlock) {
        queue += state to block
    }

    override suspend fun hasNext(): Boolean = queue.isNotEmpty()

    override suspend fun next(): Pair<TraverserState, BasicBlock> = queue.poll()
}
