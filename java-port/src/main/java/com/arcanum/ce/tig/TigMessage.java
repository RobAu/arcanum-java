package com.arcanum.ce.tig;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Event message queue. Maps {@code tig/message.h} (TigMessage + the
 * tig_message_* API). The C engine pumps SDL events into this queue; the Java
 * port will pump libGDX input events here from an InputProcessor.
 */
public final class TigMessage {

    /** TigMessageType */
    public static final int TIG_MESSAGE_MOUSE = 0;
    public static final int TIG_MESSAGE_BUTTON = 1;
    public static final int TIG_MESSAGE_CHAR = 2;
    public static final int TIG_MESSAGE_KEYBOARD = 3;
    public static final int TIG_MESSAGE_QUIT = 4;
    public static final int TIG_MESSAGE_TEXT_INPUT = 5;

    public int type;
    public int timestamp;
    // Union payload in C; flattened here.
    public int mouseX;
    public int mouseY;
    public int mouseEvent;
    public int key;
    public boolean pressed;
    public int ch;

    private static final Deque<TigMessage> QUEUE = new ArrayDeque<>();

    public TigMessage() {
    }

    /** tig_message_enqueue */
    public static int enqueue(TigMessage message) {
        QUEUE.addLast(copy(message));
        return 0;
    }

    /** tig_message_dequeue: fills {@code out}; returns 0 if a message was
     *  available, non-zero when the queue is empty. */
    public static int dequeue(TigMessage out) {
        TigMessage m = QUEUE.pollFirst();
        if (m == null) {
            return 1;
        }
        out.type = m.type;
        out.timestamp = m.timestamp;
        out.mouseX = m.mouseX;
        out.mouseY = m.mouseY;
        out.mouseEvent = m.mouseEvent;
        out.key = m.key;
        out.pressed = m.pressed;
        out.ch = m.ch;
        return 0;
    }

    /** tig_message_ping */
    public static void ping() {
        // libGDX delivers input via callbacks; nothing to poll here yet.
    }

    /** tig_message_post_quit */
    public static int postQuit(int exitCode) {
        TigMessage m = new TigMessage();
        m.type = TIG_MESSAGE_QUIT;
        enqueue(m);
        return 0;
    }

    private static TigMessage copy(TigMessage s) {
        TigMessage d = new TigMessage();
        d.type = s.type;
        d.timestamp = s.timestamp;
        d.mouseX = s.mouseX;
        d.mouseY = s.mouseY;
        d.mouseEvent = s.mouseEvent;
        d.key = s.key;
        d.pressed = s.pressed;
        d.ch = s.ch;
        return d;
    }
}
