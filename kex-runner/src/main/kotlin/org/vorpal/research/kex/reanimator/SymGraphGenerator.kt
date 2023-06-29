package org.vorpal.research.kex.reanimator

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symgraph2.GraphBuilder
import org.vorpal.research.kex.asm.analysis.symgraph2.SymGraphTestCasePrinter
import org.vorpal.research.kex.asm.state.PredicateStateAnalysis
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.descriptor.ObjectDescriptor
import org.vorpal.research.kex.ktype.KexClass
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.reanimator.actionsequence.ActionSequence
import org.vorpal.research.kex.reanimator.actionsequence.generator.ConstantGenerator
import org.vorpal.research.kex.reanimator.actionsequence.generator.GeneratorContext
import org.vorpal.research.kex.reanimator.codegen.packageName
import org.vorpal.research.kex.smt.SMTModel
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kfg.ir.Class
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kthelper.logging.log
import java.nio.file.Path

class SymGraphGenerator(
    override val ctx: ExecutionContext,
    val method: Method,
    val testName: String,
    val graphBuilder: GraphBuilder,
) : ParameterGenerator {

    private val constGenerator = ConstantGenerator(GeneratorContext(ctx, PredicateStateAnalysis(ctx.cm)))

    //    private val printer = ExecutorTestCasePrinter(ctx, method.packageName, testName)
    private val printer = SymGraphTestCasePrinter(ctx, method.packageName, testName)
//    val testKlassName = printer.fullKlassName

    suspend fun generate(descriptors: Parameters<Descriptor>): Boolean {
        log.debug("Descriptors to generate: $descriptors")
        val (params, sequence) = getActionSequences(descriptors) ?: return false
        printer.print(method, params, sequence)
        return true
    }

    override fun generate(testName: String, method: Method, state: PredicateState, model: SMTModel): Parameters<Any?> {
        TODO("Not yet implemented")
    }

    override fun emit(): Path {
        printer.emit()
        return printer.targetFile.toPath()
    }

    suspend fun getActionSequences(descriptors: Parameters<Descriptor>): Pair<Parameters<ActionSequence>, List<ActionSequence>>? {
        val objectDescriptors = buildSet {
            with(descriptors) {
                addAll(arguments.filter { it.type is KexClass }.map { it as ObjectDescriptor })
                instance?.let { add(it as ObjectDescriptor) }
            }
        }
        val result = graphBuilder.restoreActionSequences(objectDescriptors)
        log.debug("getActionSequences: call restoreActionSequences, result = $result")
        val (rootSequence, mapping) = result ?: return null
        val instance = descriptors.instance?.let { mapping.getValue(it as ObjectDescriptor) }
        val arguments = descriptors.arguments.map {
            when (it) {
                is ObjectDescriptor -> mapping.getValue(it)

                else -> constGenerator.generate(it)
            }
        }
        return Parameters(instance, arguments) to rootSequence
    }


}
