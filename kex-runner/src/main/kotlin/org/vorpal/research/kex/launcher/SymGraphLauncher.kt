package org.vorpal.research.kex.launcher

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symgraph2.GraphBuilder
import org.vorpal.research.kfg.visitor.Pipeline

class SymGraphLauncher(classPaths: List<String>, targetName: String) : KexAnalysisLauncher(classPaths, targetName) {
    override fun prepareClassPath(ctx: ExecutionContext): Pipeline.() -> Unit = {}

    override fun launch() {
        val target = when (analysisLevel) {
            is ClassLevel -> analysisLevel.klass
            else -> TODO("Not yet implemented")
        }
        GraphBuilder(context, setOf(target)).build(4)
    }

}
