package org.vorpal.research.kex.asm.analysis.symgraph2

import org.vorpal.research.kex.state.term.Term

class GraphPrimitive(val term: Term): GraphValue(term.type)
