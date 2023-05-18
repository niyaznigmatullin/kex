package org.vorpal.research.kex.asm.analysis.symgraph2

import org.vorpal.research.kex.asm.analysis.symgraph2.objects.GraphVertex
import org.vorpal.research.kex.state.term.Term

abstract class Argument

object NoneArgument : Argument()

data class PrimitiveArgument(val term: Term) : Argument()

data class ObjectArgument(val obj: GraphVertex) : Argument()
