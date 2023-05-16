@file:Suppress("unused")

package org.vorpal.research.kex.asm.analysis

import org.vorpal.research.kfg.ir.BasicBlock
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kthelper.graph.GraphTraversal

private fun buildBlockMapping(method: Method): Map<BasicBlock, BasicBlock> {
    val result = mutableMapOf<BasicBlock, BasicBlock>()
    val visited = mutableSetOf<BasicBlock>()

    for (block in method.body) {
        if (block in visited) continue

        val list = mutableListOf<BasicBlock>()
        var current = block
        while (true) {
            if (current.successors.size != 1) break
            val successor = current.successors.first()
            if (successor.predecessors.size != 1) break
            list += current
            current = successor
        }

        val last = list.lastOrNull() ?: continue
        for (b in list) {
            result[b] = last
            visited += b
        }
    }

    return result
}

private fun filterBlockOrder(method: Method, order: List<BasicBlock>): List<BasicBlock> {
    val mapping = buildBlockMapping(method)
    val mapped = order.map { mapping.getOrDefault(it, it) }
    val filteredMapping = mutableListOf<BasicBlock>()
    for (block in mapped) {
        if (block !in filteredMapping) filteredMapping += block
    }
    return filteredMapping
}

interface SearchStrategy : Iterable<BasicBlock> {
    val method: Method
}

class TopologicalStrategy(override val method: Method) : SearchStrategy {
    private val order: List<BasicBlock>

    init {
        val tpOrder = GraphTraversal(method.body).topologicalSort()
        order = filterBlockOrder(method, tpOrder)
    }

    override fun iterator() = order.iterator()
}

class BfsStrategy(override val method: Method) : SearchStrategy {
    private val bfsSearch: List<BasicBlock>

    init {
        val bfsOrder = GraphTraversal(method.body).bfs()
        bfsSearch = filterBlockOrder(method, bfsOrder)
    }

    override fun iterator() = bfsSearch.iterator()
}

class DfsStrategy(override val method: Method) : SearchStrategy {
    private val dfsSearch: List<BasicBlock>

    init {
        val dfsOrder = GraphTraversal(method.body).dfs()
        dfsSearch = filterBlockOrder(method, dfsOrder)
    }

    override fun iterator() = dfsSearch.iterator()
}

class UnfilteredDfsStrategy(override val method: Method) : SearchStrategy {
    private val dfsOrder: List<BasicBlock> = GraphTraversal(method.body).dfs()

    override fun iterator() = dfsOrder.iterator()
}
