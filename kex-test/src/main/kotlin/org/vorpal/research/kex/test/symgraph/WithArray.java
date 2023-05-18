package org.vorpal.research.kex.test.symgraph;

public class WithArray {
    Object[] a;

    public WithArray(int x, int y, int z) {
        a = new Object[] {x, y, z};
    }

    public void set(int x, Object z) {
        a[x] = z;
    }

    public Object get(int x) {
        return a[x];
    }
}
