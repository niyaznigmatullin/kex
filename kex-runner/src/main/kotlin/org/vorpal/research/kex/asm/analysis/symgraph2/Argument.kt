package org.vorpal.research.kex.asm.analysis.symgraph2

import org.vorpal.research.kex.asm.analysis.symgraph2.objects.GraphVertex
import org.vorpal.research.kex.ktype.KexNull
import org.vorpal.research.kex.ktype.isGraphObject
import org.vorpal.research.kex.state.term.Term

abstract class Argument

object NoneArgument : Argument()

data class PrimitiveArgument(val term: Term) : Argument() {
    init {
        check(term.type is KexNull || !term.type.isGraphObject)
    }
}

data class ObjectArgument(val obj: GraphVertex) : Argument() {
    init {
        check(obj.type is KexNull || obj.type.isGraphObject)
    }
}
