package org.vorpal.research.kex.launcher

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symgraph.GraphBuilder
import org.vorpal.research.kex.asm.analysis.symgraph.GraphObject
import org.vorpal.research.kex.asm.analysis.symgraph.MethodAbstractlyInvocator
import org.vorpal.research.kex.descriptor.ConstantDescriptor
import org.vorpal.research.kfg.visitor.Pipeline

class SymGraphLauncher(classPaths: List<String>, targetName: String) : KexAnalysisLauncher(classPaths, targetName) {
    override fun prepareClassPath(ctx: ExecutionContext): Pipeline.() -> Unit = {}

    override fun launch() {
        val target = when (analysisLevel) {
            is ClassLevel -> analysisLevel.klass
            else -> TODO("Not yet implemented")
        }
        GraphBuilder(context, target).build(3)
//        val callResult = MethodAbstractlyInvocator(context, create).invokeMethod(GraphObject.Null, listOf(GraphObject.Null, GraphObject.Null), emptyList()).iterator().next()
//        println(callResult)
//        val addResult = MethodAbstractlyInvocator(context, addBefore).invokeMethod(callResult.returnValue!!, listOf(GraphObject.Null), callResult.objects)
//        println(addResult)
//        MethodAbstractlyInvocator(context, addBefore).invokeMethod(GraphObject.Null, listOf(GraphObject.Null, GraphObject.Null), emptyList())
//        publicMethods.forEach {
//            println("name = $it")
//            println(it.body)
//        }

//
//        val states = listOf(AbstractState.empty())
//        for (L in 1..maxL) {
//
//        }

    }

}
