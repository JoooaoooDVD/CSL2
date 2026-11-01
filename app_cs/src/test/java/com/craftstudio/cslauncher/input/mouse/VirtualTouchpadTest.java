package com.craftstudio.cslauncher.input.mouse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Regression tests for the PC-like virtual mouse: relative motion, no teleport, taps, scroll. */
public class VirtualTouchpadTest {
    private final List<String> mLog = new ArrayList<>();
    private float mCursorX = 100, mCursorY = 100;
    private VirtualTouchpad mPad;

    @Before
    public void setUp() {
        mPad = new VirtualTouchpad(new VirtualTouchpad.Sink() {
            @Override public void move(float dx, float dy) { mCursorX += dx; mCursorY += dy; mLog.add("move"); }
            @Override public void click(int button) { mLog.add("click" + button); }
            @Override public void scroll(float dx, float dy) { mLog.add("scroll " + dx + "," + dy); }
            @Override public void press(int button, boolean down) { mLog.add((down ? "press" : "release") + button); }
        }, 8f, 300);
    }

    @Test
    public void touchingFarAwayDoesNotMoveTheCursor() {
        mPad.down(0, 900, 500);
        assertEquals(100, mCursorX, 0);
        assertEquals(100, mCursorY, 0);
        mPad.up(50);
        assertEquals(100, mCursorX, 0);
        assertEquals(100, mCursorY, 0);
    }

    @Test
    public void movementIsRelativeAndSurvivesLiftingTheFinger() {
        mPad.down(0, 900, 500);
        mPad.move(910, 505, 1);
        mPad.move(930, 500, 1);
        mPad.up(400);
        assertEquals(130, mCursorX, 0);
        assertEquals(100, mCursorY, 0);
        // Second gesture somewhere else: continues from the current cursor, no jump
        mPad.down(1000, 10, 10);
        mPad.move(15, 20, 1);
        mPad.up(1400);
        assertEquals(135, mCursorX, 0);
        assertEquals(110, mCursorY, 0);
        assertFalse(mLog.contains("click0"));
    }

    @Test
    public void quickStillTapIsLeftClickAtCursor() {
        mPad.down(0, 400, 400);
        mPad.move(402, 401, 1);  // finger jitter inside slop
        mPad.up(120);
        assertTrue(mLog.contains("click0"));
        assertEquals(102, mCursorX, 0);
    }

    @Test
    public void slowOrMovingTouchIsNotAClick() {
        mPad.down(0, 400, 400);
        mPad.up(800);
        mPad.down(1000, 400, 400);
        mPad.move(450, 400, 1);
        mPad.up(1050);
        assertFalse(mLog.contains("click0"));
    }

    @Test
    public void twoFingerTapIsRightClick() {
        mPad.down(0, 400, 400);
        mPad.pointerCount(2, 401, 400);
        mPad.pointerCount(1, 401, 400);
        mPad.up(150);
        assertEquals(1, mLog.size());
        assertEquals("click1", mLog.get(0));
    }

    @Test
    public void twoFingersScrollAndDoNotMoveOrClick() {
        mPad.down(0, 400, 400);
        mPad.pointerCount(2, 400, 400);
        mPad.move(400, 380, 2);
        mPad.move(400, 350, 2);
        mPad.pointerCount(1, 500, 350);  // one finger lifts: re-anchor only
        mPad.move(500, 350, 1);
        mPad.up(500);
        assertEquals(100, mCursorX, 0);
        assertEquals(100, mCursorY, 0);
        assertEquals("scroll 0.0,-20.0", mLog.get(0));
        assertEquals("scroll 0.0,-30.0", mLog.get(1));
        assertEquals(2, mLog.size());
    }

    @Test
    public void doubleTapAndHoldDrags() {
        mPad.down(0, 400, 400);
        mPad.up(100);                 // click
        mPad.down(200, 400, 400);     // second touch right after: hold
        mPad.move(450, 420, 1);
        mPad.up(900);
        assertEquals("[click0, press0, move, release0]", mLog.toString());
        assertEquals(150, mCursorX, 0);
        // A later touch is a normal gesture again
        mPad.down(2000, 0, 0);
        mPad.up(2050);
        assertEquals("click0", mLog.get(mLog.size() - 1));
    }

    @Test
    public void optionsCanDisableTapRightClickAndDrag() {
        mPad.tapToClick = false;
        mPad.twoFingerRightClick = false;
        mPad.doubleTapDrag = false;
        mPad.down(0, 1, 1); mPad.up(50);
        mPad.down(60, 1, 1); mPad.move(30, 1, 1); mPad.up(300);
        mPad.down(400, 1, 1); mPad.pointerCount(2, 1, 1); mPad.pointerCount(1, 1, 1); mPad.up(450);
        assertEquals("[move]", mLog.toString());
    }

    @Test
    public void pointerCountChangeNeverJumps() {
        mPad.down(0, 100, 100);
        mPad.move(700, 700, 2);  // count changed inside a move: re-anchor, no motion
        assertEquals(100, mCursorX, 0);
        mPad.move(710, 700, 2);
        assertEquals("scroll 10.0,0.0", mLog.get(0));
        mPad.cancel();
        assertFalse(mPad.isActive());
        assertEquals(1, mLog.size());
    }
}
