package org.vorpal.research.kex.reanimator

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symgraph2.*
import org.vorpal.research.kex.asm.state.PredicateStateAnalysis
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.ktype.*
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.reanimator.actionsequence.ActionSequence
import org.vorpal.research.kex.reanimator.actionsequence.generator.*
import org.vorpal.research.kex.reanimator.codegen.packageName
import org.vorpal.research.kex.smt.SMTModel
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kthelper.assert.unreachable
import org.vorpal.research.kthelper.logging.log
import java.nio.file.Path

class SymGraphGenerator(
    override val ctx: ExecutionContext,
    val method: Method,
    val testName: String,
) : ParameterGenerator {

    private val generatorsContext = GeneratorContext(ctx, PredicateStateAnalysis(ctx.cm))
    private val generator = PrimitiveGeneratorProxy(generatorsContext)

    private val printer = SymGraphTestCasePrinter(ctx, method.packageName, testName)

    suspend fun generate(testCase: GraphTestCase): Boolean {
        log.debug("HeapState to generate: ${testCase.heapState}")
        val (params, sequence) = getActionSequences(testCase) ?: return false
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

    private suspend fun getActionSequences(testCase: GraphTestCase): Pair<Parameters<ActionSequence>, List<ActionSequence>>? {
        val result = testCase.heapState.restore(ctx, testCase.termValues, generator)
        log.debug("getActionSequences: call restoreActionSequences, result = $result")
        if (result == null) {
            return null
        }
        val instance = testCase.parameters.instance?.let {
            it as ObjectArgument
            result.objectGenerators.getValue(it.obj)
        }

        val arguments = testCase.parameters.arguments.map {
            when (it) {
                is ObjectArgument -> result.objectGenerators.getValue(it.obj)

                is PrimitiveArgument -> {
                    val descriptor = testCase.termValues.getValue(it.term)
                    generator.generate(descriptor)
                }

                else -> unreachable { log.debug("NoneArgument in restore") }
            }
        }
        return Parameters(instance, arguments) to result.rootSequence
    }
}

class PrimitiveGeneratorProxy(override val context: GeneratorContext) : Generator {
    private val constGenerator = ConstantGenerator(context)
    private val stringGenerator = StringGenerator(constGenerator) // No need for fallback here
    private val classGenerator = ClassGenerator(stringGenerator)
    override fun supports(descriptor: Descriptor): Boolean {
        return !descriptor.type.isGraphObject
    }

    private fun getGenerator(type: KexType): Generator = when {
        type.isJavaClass -> classGenerator

        type.isString -> stringGenerator

        type is KexArray -> ArrayGenerator(getGenerator(type.element))

        type is KexNull -> constGenerator

        type.isGraphObject -> unreachable { log.debug("Type $type is not primitive") }

        else -> constGenerator
    }

    override fun generate(descriptor: Descriptor, generationDepth: Int): ActionSequence {
        return getGenerator(descriptor.type).generate(descriptor, generationDepth)
    }
}