package org.vorpal.research.kex.test.symgraph.getClass;

public class PointClass<T extends Point> extends ClassAsAField<T> {

    public PointClass() {
        super(Point.class);
    }
}
