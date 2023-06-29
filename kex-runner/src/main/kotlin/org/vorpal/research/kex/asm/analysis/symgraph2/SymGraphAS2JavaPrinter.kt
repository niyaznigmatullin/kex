package org.vorpal.research.kex.asm.analysis.symgraph2

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.reanimator.actionsequence.ActionSequence
import org.vorpal.research.kex.reanimator.actionsequence.ExternalMethodCall
import org.vorpal.research.kex.reanimator.codegen.javagen.ActionSequence2JavaPrinter
import org.vorpal.research.kthelper.tryOrNull

class SymGraphAS2JavaPrinter(ctx: ExecutionContext, packageName: String, klassName: String) :
    ActionSequence2JavaPrinter(ctx, packageName, klassName) {

    fun List<ActionSequence>.printAsJava() = forEach {
        it.printAsJava()
    }

    fun printActionSequenceList(
        testName: String,
        method: org.vorpal.research.kfg.ir.Method,
        params: Parameters<ActionSequence>,
        actionSequences: List<ActionSequence>
    ) {
        cleanup()
        val actionSequence = buildMethodCall(method, params)
        with(builder) {
            with(klass) {
                current = method(testName) {
                    returnType = void
                    annotations += "Test"
                    exceptions += "Throwable"
                }
            }
        }
        resolveTypes(actionSequence)
        actionSequences.printAsJava()
        actionSequence.printAsJava()
    }
}