package org.vorpal.research.kex.test.symgraph.primitivearray;

public class ListStackOfArrays {
    static class ListNode {
        ListNode prev;
        int[] value;

        public ListNode(ListNode prev, int[] value) {
            this.prev = prev;
            this.value = value;
        }
    }

    ListNode top;

    public ListStackOfArrays() {
        top = null;
    }

    public void push(int[] a) {
        top = new ListNode(top, a);
    }

    public int[] topAndPop() {
        int[] result = top.value;
        top = top.prev;
        return result;
    }

    public int[] top() {
        return top.value;
    }

    public void pop() {
        top = top.prev;
    }

    public void setTop(int index, int value) {
        if (top.value != null && index < top.value.length) {
            top.value[index] = value;
        }
    }
}
