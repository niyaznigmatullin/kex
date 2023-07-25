package org.vorpal.research.kex.test.symgraph.bst;


class Range {
    final int lower;
    final int upper;
    final boolean isPositiveInfinity;
    final boolean isNegativeInfinity;

    public Range() {
        this(0, 0, true, true);
    }

    private Range(final int u, final int l, final boolean ip, final boolean in) {
        this.upper = u;
        this.lower = l;
        this.isPositiveInfinity = ip;
        this.isNegativeInfinity = in;
    }

    public boolean inRange(final int value) {
        boolean ret = true;
        if (!this.isPositiveInfinity) {
            ret = value < this.upper;
        }
        if (!this.isNegativeInfinity) {
            ret = ret && (value > this.lower);
        }
        return ret;
    }

    public Range setLower(final int l) {
        assert this.isNegativeInfinity || (l > this.lower);
        return new Range(this.upper, l, this.isPositiveInfinity, false);
    }

    public Range setUpper(final int u) {
        assert this.isPositiveInfinity || (u < this.upper);
        return new Range(u, this.lower, false, this.isNegativeInfinity);
    }
}


class BinaryNode {
    // Friendly data; accessible by other package routines
    int element; // The data in the node

    BinaryNode left; // Left child

    BinaryNode right; // Right child

    public BinaryNode() {
        this(-1);
    }

    // Constructors
    BinaryNode(final int theElement) {
        this(theElement, null, null);
    }

    BinaryNode(final int theElement, final BinaryNode lt, final BinaryNode rt) {
        this.element = theElement;
        this.left = lt;
        this.right = rt;
    }
}

// BinarySearchTree class
//
// CONSTRUCTION: with no initializer
//
// ******************PUBLIC OPERATIONS*********************
// void insert( x )       --> Insert x
// void remove( x )       --> Remove x
// Comparable find( x )   --> Return item that matches x
// Comparable findMin( )  --> Return smallest item
// Comparable findMax( )  --> Return largest item
// boolean isEmpty( )     --> Return true if empty; else false
// void makeEmpty( )      --> Remove all items
// void printTree( )      --> Print tree in sorted order

/**
 * Implements an unbalanced binary search tree. Note that all "matching" is
 * based on the compareTo method.
 *
 * @author Mark Allen Weiss
 */
public class BinarySearchTree {

    // Test program
    public static void main(final String[] args) {
        final BinarySearchTree t = new BinarySearchTree();
        final int NUMS = 4000;
        final int GAP = 37;

        System.out.println("Checking... (no more output means success)");

        for (int i = GAP; i != 0; i = (i + GAP) % NUMS) {
            t.insert(i);
        }

        for (int i = 1; i < NUMS; i += 2) {
            t.remove(i);
        }

        if (NUMS < 40) {
            // t.printTree();
            // if( t.findMin( ))).intValue( ) != 2 ||
            // ((MyInteger)(t.findMax( ))).intValue( ) != NUMS - 2 )
            // System.out.println( "FindMin or FindMax error!" );
            //
            // for( int i = 2; i < NUMS; i+=2 )
            // if( ((MyInteger)(t.find( new MyInteger( i ) ))).intValue( ) != i )
            // System.out.println( "Find error1!" );
            //
            // for( int i = 1; i < NUMS; i+=2 )
            // {
            // if( t.find( new MyInteger( i ) ) != null )
            // System.out.println( "Find error2!" );
            // }
        }
    }

    /** The tree root. */
    public BinaryNode root;

    /**
     * Construct the tree.
     */
    public BinarySearchTree() {
        this.root = null;
    }

    /**
     * Internal method to get element field.
     *
     * @param t
     *          the node.
     * @return the element field or null if t is null.
     */
    private int elementAt(final BinaryNode t) {
        if (t == null) {
            return -1;
        }
        return t.element;
    }

    /**
     * Find an item in the tree.
     *
     * @param x
     *          the item to search for.
     * @return the matching item or null if not found.
     */
    //@ requires this.repOK();
    //@ ensures this.repOK();
    public int find(final int x) {
        return elementAt(find(x, this.root));
    }

    /**
     * Internal method to find an item in a subtree.
     *
     * @param x
     *          is item to search for.
     * @param t
     *          the node that roots the tree.
     * @return node containing the matched item.
     */
    private BinaryNode find(final int x, final BinaryNode t) {
        if (t == null) {
            return null;
        }
        if (x < t.element) {
            return find(x, t.left);
        } else if (x > t.element) {
            return find(x, t.right);
        } else {
            return t; // Match
        }
    }

    /**
     * Find the largest item in the tree.
     *
     * @return the largest item of null if empty.
     */
    //@ requires this.repOK();
    //@ ensures this.repOK();
    public int findMax() {
        return elementAt(findMax(this.root));
    }

    /**
     * Internal method to find the largest item in a subtree.
     *
     * @param t
     *          the node that roots the tree.
     * @return node containing the largest item.
     */
    private BinaryNode findMax(BinaryNode t) {
        if (t != null) {
            while (t.right != null) {
                t = t.right;
            }
        }

        return t;
    }

    /**
     * Find the smallest item in the tree.
     *
     * @return smallest item or null if empty.
     */
    //@ requires this.repOK();
    //@ ensures this.repOK();
    public int findMin() {
        return elementAt(findMin(this.root));
    }

    /**
     * Internal method to find the smallest item in a subtree.
     *
     * @param t
     *          the node that roots the tree.
     * @return node containing the smallest item.
     */
    private BinaryNode findMin(final BinaryNode t) {
        if (t == null) {
            return null;
        } else if (t.left == null) {
            return t;
        }
        return findMin(t.left);
    }

    /**
     * Insert into the tree; duplicates are ignored.
     *
     * @param x
     *          the item to insert.
     */

    //@ requires this.repOK();
    //@ ensures this.repOK();
    public void insert(final int x) {
        this.root = insert(x, this.root);
    }

    /**
     * Internal method to insert into a subtree.
     *
     * @param x
     *          the item to insert.
     * @param t
     *          the node that roots the tree.
     * @return the new root.
     */
    private BinaryNode insert(final int x, BinaryNode t) {
        /* 1 */if (t == null) {
            /* 2 */t = new BinaryNode(x, null, null);
        } else if (x < t.element) {
            /* 4 */t.left = insert(x, t.left);
        } else if (x > t.element) {
            /* 6 */t.right = insert(x, t.right);
        } else {
            /* 8 */; // Duplicate; do nothing
        }
        /* 9 */
        return t;
    }

    /**
     * Test if the tree is logically empty.
     *
     * @return true if empty, false otherwise.
     */
    public boolean isEmpty() {
        return this.root == null;
    }

    /**
     * Make the tree logically empty.
     */
    public void makeEmpty() {
        this.root = null;
    }

    // /**
    //  * Print the tree contents in sorted order.
    //  */
    // public void printTree() {
    //   if (isEmpty()) {
    //     System.out.println("Empty tree");
    //   } else {
    //     printTree(this.root);
    //   }
    // }

    // /**
    //  * Internal method to print a subtree in sorted order.
    //  *
    //  * @param t
    //  *          the node that roots the tree.
    //  */
    // private void printTree(final BinaryNode t) {
    //   if (t != null) {
    //     printTree(t.left);
    //     System.out.println(t.element);
    //     printTree(t.right);
    //   }
    // }

    /**
     * Remove from the tree. Nothing is done if x is not found.
     *
     * @param x
     *          the item to remove.
     */
    //@ requires this.repOK();
    //@ ensures this.repOK();
    public void remove(final int x) {
        this.root = remove(x, this.root);
    }

    /**
     * Internal method to remove from a subtree.
     *
     * @param x
     *          the item to remove.
     * @param t
     *          the node that roots the tree.
     * @return the new root.
     */
    private BinaryNode remove(final int x, BinaryNode t) {
        if (t == null) {
            return t; // Item not found; do nothing
        }
        if (x < t.element) {
            t.left = remove(x, t.left);
        } else if (x > t.element) {
            t.right = remove(x, t.right);
        } else if ((t.left != null) && (t.right != null)) // Two children
        {
            t.element = findMin(t.right).element;
            t.right = remove(t.element, t.right);
        } else {
            t = (t.left != null) ? t.left : t.right;
        }
        return t;
    }

    boolean repOK() {
        if (this.root == null) {
            return true;
        } else {
            return repOK(this.root);
        }
    }

    private boolean repOK(final BinaryNode t) {
        return repOK(t, new Range());
    }

    private boolean repOK(final BinaryNode t, final Range range) {
        if (t == null) {
            return true;
        }
        if (!range.inRange(t.element)) {
            return false;
        }
        boolean ret = true;
        ret = ret && repOK(t.left, range.setUpper(t.element));
        ret = ret && repOK(t.right, range.setLower(t.element));
        return ret;
    }
}
