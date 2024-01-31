package org.vorpal.research.kex.asm.analysis.symgraph2

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.reanimator.actionsequence.ActionSequence
import org.vorpal.research.kex.reanimator.codegen.TestCasePrinter
import org.vorpal.research.kex.util.testcaseDirectory
import org.vorpal.research.kfg.ir.Method
import java.io.File

class SymGraphTestCasePrinter(
    ctx: ExecutionContext,
    packageName: String,
    val klassName: String
) : TestCasePrinter(ctx, packageName) {
    private val testDirectory = kexConfig.testcaseDirectory
    override val printer = SymGraphAS2JavaPrinter(ctx, packageName.replace("/", "."), klassName)
    override fun print(testName: String, method: Method, actionSequences: Parameters<ActionSequence>) {
        TODO("Not yet implemented")
    }

    override val targetFile: File = run {
        val targetFileName = "$klassName.java"
        testDirectory.resolve(packageName).resolve(targetFileName).toAbsolutePath().toFile().apply {
            parentFile?.mkdirs()
        }
    }

    companion object {
        const val TEST_METHOD = "test"
    }

    fun print(testName: String, method: Method, params: Parameters<ActionSequence>, actionSequences: List<ActionSequence>) {
        printer.printActionSequenceList(validateString(testName), method, params, actionSequences)
    }

    fun print(method: Method, params: Parameters<ActionSequence>, actionSequences: List<ActionSequence>) {
        print(TEST_METHOD, method, params, actionSequences)
    }

    override fun emit() {
        targetFile.writeText(printer.emit())
    }
}
