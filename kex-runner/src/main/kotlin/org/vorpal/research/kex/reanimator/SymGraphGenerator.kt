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
import java.nio.file.Path

class SymGraphGenerator(
    override val ctx: ExecutionContext,
    val method: Method,
    val testName: String,
    klass: Class,
) : ParameterGenerator {
    private val graphBuilder = GraphBuilder(ctx, klass)

    private val constGenerator = ConstantGenerator(GeneratorContext(ctx, PredicateStateAnalysis(ctx.cm)))

    //    private val printer = ExecutorTestCasePrinter(ctx, method.packageName, testName)
    private val printer = SymGraphTestCasePrinter(ctx, method.packageName, testName)
//    val testKlassName = printer.fullKlassName

    init {
        graphBuilder.build(3)
    }

    fun generate(descriptors: Parameters<Descriptor>): Boolean {
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

    fun getActionSequences(descriptors: Parameters<Descriptor>): Pair<Parameters<ActionSequence>, List<ActionSequence>>? =
        with(descriptors) {
            val objectDescriptors = buildSet {
                addAll(arguments.filter { it.type is KexClass }.map { it as ObjectDescriptor })
                instance?.let { add(it as ObjectDescriptor) }
            }
            val (rootSequence, mapping) = graphBuilder.restoreActionSequences(objectDescriptors) ?: return null
            val instance = descriptors.instance?.let { mapping.getValue(it as ObjectDescriptor) }
            val arguments = arguments.map {
                when (it) {
                    is ObjectDescriptor -> mapping.getValue(it)

                    else -> constGenerator.generate(it)
                }
            }
            return Parameters(instance, arguments) to rootSequence
        }


}
