package com.yuzugame.util;

public final class CodeUtils {

    private CodeUtils() {}

    public static String normalizeCode(String code) {
        if (code == null) return null;
        return code.toUpperCase();
    }
}
