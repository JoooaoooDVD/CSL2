package com.craftstudio.cslauncher.engine.horizon;

import android.util.SparseArray;
import android.view.KeyEvent;

import androidx.annotation.Nullable;

/**
 * Android {@link KeyEvent} keycodes (what CS control layouts store) to Horizon key names
 * ({@code key.keyboard.*}, the same names Minecraft uses in options.txt).
 */
final class HorizonKeyMap {
    private static final SparseArray<String> MAP = new SparseArray<>(160);

    private HorizonKeyMap() {}

    private static void put(int keycode, String token) {
        MAP.put(keycode, token);
    }

    static {
        for (int i = 0; i < 26; i++) put(KeyEvent.KEYCODE_A + i, "key.keyboard." + (char) ('a' + i));
        for (int i = 0; i < 10; i++) put(KeyEvent.KEYCODE_0 + i, "key.keyboard." + i);
        for (int i = 0; i < 12; i++) put(KeyEvent.KEYCODE_F1 + i, "key.keyboard.f" + (i + 1));
        for (int i = 0; i < 10; i++) put(KeyEvent.KEYCODE_NUMPAD_0 + i, "key.keyboard.keypad." + i);
        put(KeyEvent.KEYCODE_SPACE, "key.keyboard.space");
        put(KeyEvent.KEYCODE_SHIFT_LEFT, "key.keyboard.left.shift");
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, "key.keyboard.right.shift");
        put(KeyEvent.KEYCODE_CTRL_LEFT, "key.keyboard.left.control");
        put(KeyEvent.KEYCODE_CTRL_RIGHT, "key.keyboard.right.control");
        put(KeyEvent.KEYCODE_ALT_LEFT, "key.keyboard.left.alt");
        put(KeyEvent.KEYCODE_ALT_RIGHT, "key.keyboard.right.alt");
        put(KeyEvent.KEYCODE_META_LEFT, "key.keyboard.left.win");
        put(KeyEvent.KEYCODE_META_RIGHT, "key.keyboard.right.win");
        put(KeyEvent.KEYCODE_ESCAPE, "key.keyboard.escape");
        put(KeyEvent.KEYCODE_BACK, "key.keyboard.escape");
        put(KeyEvent.KEYCODE_ENTER, "key.keyboard.enter");
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, "key.keyboard.keypad.enter");
        put(KeyEvent.KEYCODE_TAB, "key.keyboard.tab");
        put(KeyEvent.KEYCODE_DEL, "key.keyboard.backspace");
        put(KeyEvent.KEYCODE_FORWARD_DEL, "key.keyboard.delete");
        put(KeyEvent.KEYCODE_INSERT, "key.keyboard.insert");
        put(KeyEvent.KEYCODE_MOVE_HOME, "key.keyboard.home");
        put(KeyEvent.KEYCODE_MOVE_END, "key.keyboard.end");
        put(KeyEvent.KEYCODE_PAGE_UP, "key.keyboard.page.up");
        put(KeyEvent.KEYCODE_PAGE_DOWN, "key.keyboard.page.down");
        put(KeyEvent.KEYCODE_DPAD_UP, "key.keyboard.up");
        put(KeyEvent.KEYCODE_DPAD_DOWN, "key.keyboard.down");
        put(KeyEvent.KEYCODE_DPAD_LEFT, "key.keyboard.left");
        put(KeyEvent.KEYCODE_DPAD_RIGHT, "key.keyboard.right");
        put(KeyEvent.KEYCODE_CAPS_LOCK, "key.keyboard.caps.lock");
        put(KeyEvent.KEYCODE_NUM_LOCK, "key.keyboard.num.lock");
        put(KeyEvent.KEYCODE_SCROLL_LOCK, "key.keyboard.scroll.lock");
        put(KeyEvent.KEYCODE_SYSRQ, "key.keyboard.print.screen");
        put(KeyEvent.KEYCODE_BREAK, "key.keyboard.pause");
        put(KeyEvent.KEYCODE_MENU, "key.keyboard.menu");
        put(KeyEvent.KEYCODE_GRAVE, "key.keyboard.grave.accent");
        put(KeyEvent.KEYCODE_MINUS, "key.keyboard.minus");
        put(KeyEvent.KEYCODE_EQUALS, "key.keyboard.equal");
        put(KeyEvent.KEYCODE_LEFT_BRACKET, "key.keyboard.left.bracket");
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, "key.keyboard.right.bracket");
        put(KeyEvent.KEYCODE_BACKSLASH, "key.keyboard.backslash");
        put(KeyEvent.KEYCODE_SEMICOLON, "key.keyboard.semicolon");
        put(KeyEvent.KEYCODE_APOSTROPHE, "key.keyboard.apostrophe");
        put(KeyEvent.KEYCODE_COMMA, "key.keyboard.comma");
        put(KeyEvent.KEYCODE_PERIOD, "key.keyboard.period");
        put(KeyEvent.KEYCODE_SLASH, "key.keyboard.slash");
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, "key.keyboard.keypad.divide");
        put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, "key.keyboard.keypad.multiply");
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, "key.keyboard.keypad.subtract");
        put(KeyEvent.KEYCODE_NUMPAD_ADD, "key.keyboard.keypad.add");
        put(KeyEvent.KEYCODE_NUMPAD_DOT, "key.keyboard.keypad.decimal");
        put(KeyEvent.KEYCODE_NUMPAD_EQUALS, "key.keyboard.keypad.equal");
    }

    @Nullable
    static String token(int androidKeycode) {
        return MAP.get(androidKeycode);
    }
}
