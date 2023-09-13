package org.vorpal.research.kex.test.symgraph.strings;

public class StringUtils {

    public static boolean isPalindrome(String s) {
        return new StringBuilder(s).reverse().toString().equals(s);
    }

    public static String concat(String s, String t) {
        return s + t;
    }

    public static boolean firstAndLastEqual(String s) {
        return s.charAt(0) == s.charAt(s.length() - 1);
    }

    public static String append(String s, char c) {
        return s + c;
    }
}
