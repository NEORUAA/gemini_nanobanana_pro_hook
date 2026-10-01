package com.neoruaa.geminihook;

final class MenuPolicy {
    private MenuPolicy() {}

    static void enableChatItem(Object[] arguments, int visibilityArgument) {
        arguments[visibilityArgument] = Boolean.TRUE;
    }

    static void enableFloatyItem(Object[] arguments, int titleArgument,
            int visibilityArgument, int proTitle) {
        // Leave all other overflow items' visibility untouched.
        if (Integer.valueOf(proTitle).equals(arguments[titleArgument])) {
            arguments[visibilityArgument] = Boolean.TRUE;
        }
    }
}
