package org.vorpal.research.kex.test.symgraph.getClass;

public class ClassAsAField<T> {
    Class<T> type;

    protected ClassAsAField(Class<? super T> type) {
        this.type = (Class<T>) type;
    }
}
