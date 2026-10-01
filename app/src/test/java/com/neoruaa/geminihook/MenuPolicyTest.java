package com.neoruaa.geminihook;

import org.junit.Test;

import static org.junit.Assert.*;

public final class MenuPolicyTest {
    @Test
    public void chatChangesOnlyImageRegenerationVisibility() {
        Object[] arguments = new Object[29];
        for (int i = 0; i < arguments.length; i++) {
            arguments[i] = new Object();
        }
        arguments[14] = Boolean.FALSE;
        Object[] original = arguments.clone();
        MenuPolicy.enableChatItem(arguments, 14);
        for (int i = 0; i < arguments.length; i++) {
            assertSame(i == 14 ? Boolean.TRUE : original[i], arguments[i]);
        }
    }

    @Test
    public void floatyChangesOnlyProItemVisibility() {
        Object[] arguments = {new Object(), 0x7f150556, false, 296720, new Object()};
        Object[] original = arguments.clone();
        MenuPolicy.enableFloatyItem(arguments, 1, 2, 0x7f150556);
        for (int i = 0; i < arguments.length; i++) {
            assertSame(i == 2 ? Boolean.TRUE : original[i], arguments[i]);
        }
    }

    @Test
    public void floatyPreservesUnrelatedItems() {
        Object[] arguments = {new Object(), 0x7f15055a, false, 210098, new Object()};
        Object[] original = arguments.clone();
        MenuPolicy.enableFloatyItem(arguments, 1, 2, 0x7f150556);
        assertArrayEquals(original, arguments);
    }

    @Test
    public void usesDiscoveredPositionsAndResourceId() {
        Object[] arguments = {false, new Object(), new Object(), 0x7f190123, false};
        Object[] original = arguments.clone();
        MenuPolicy.enableFloatyItem(arguments, 3, 0, 0x7f190123);
        assertSame(Boolean.TRUE, arguments[0]);
        for (int i = 1; i < arguments.length; i++) assertSame(original[i], arguments[i]);
        MenuPolicy.enableChatItem(arguments, 4);
        assertSame(Boolean.TRUE, arguments[4]);
    }
}
