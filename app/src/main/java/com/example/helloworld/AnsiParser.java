package com.example.helloworld;

/**
 * Разбор потока терминала: управляющие символы C0, escape-последовательности ESC,
 * CSI (стрелки, цвета, позиционирование, очистка), OSC (заголовок окна — отбрасываем).
 *
 * Объект живёт между вызовами feed(), поэтому последовательность, разорванная
 * границей пакета TCP, доразбирается в следующем вызове.
 */
public class AnsiParser {

    /** Обратная связь серверу: ответы на DA/DSR-запросы. Может быть null. */
    public interface Reply {
        void onReply(String text);
    }

    private static final int S_TEXT = 0;
    private static final int S_ESC = 1;
    private static final int S_CSI = 2;
    private static final int S_OSC = 3;
    private static final int S_OSC_ESC = 4;
    private static final int S_CHARSET = 5;

    private final TerminalScreen screen;
    private final Reply reply;

    private int state = S_TEXT;
    private final StringBuilder params = new StringBuilder();
    private char lastPrinted = ' ';

    /** Многие складские меню на Linux присылают только LF — без CR строки «уезжают» лестницей. */
    private boolean crOnLf = true;
    private boolean lastWasCr;

    public AnsiParser(TerminalScreen screen, Reply reply) {
        this.screen = screen;
        this.reply = reply;
    }

    public void setCrOnLf(boolean value) {
        crOnLf = value;
    }

    public void feed(String text) {
        if (text == null) {
            return;
        }
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            switch (state) {
                case S_TEXT:
                    handleText(c);
                    break;
                case S_ESC:
                    handleEscape(c);
                    break;
                case S_CSI:
                    handleCsi(c);
                    break;
                case S_OSC:
                    if (c == 0x07) {
                        state = S_TEXT;
                    } else if (c == 0x1b) {
                        state = S_OSC_ESC;
                    }
                    break;
                case S_OSC_ESC:
                    // ESC \ — конец OSC, иначе остаёмся в OSC
                    state = (c == '\\') ? S_TEXT : S_OSC;
                    break;
                case S_CHARSET:
                    state = S_TEXT;
                    break;
                default:
                    state = S_TEXT;
                    break;
            }
        }
    }

    // --------------------------------------------------------------- состояния

    private void handleText(char c) {
        if (c == 0x1b) {
            state = S_ESC;
            return;
        }
        if (c < 0x20 || c == 0x7f) {
            handleControl(c);
            return;
        }
        lastPrinted = c;
        lastWasCr = false;
        screen.putChar(c);
    }

    private void handleControl(char c) {
        switch (c) {
            case 0x08: // BS
                screen.backspace();
                break;
            case 0x09: // HT
                screen.tab();
                break;
            case 0x0a: // LF
            case 0x0b: // VT
            case 0x0c: // FF
                if (crOnLf && !lastWasCr) {
                    screen.carriageReturn();
                }
                screen.lineFeed();
                lastWasCr = false;
                break;
            case 0x0d: // CR
                screen.carriageReturn();
                lastWasCr = true;
                break;
            default:
                // BEL, SO, SI, NUL и прочее — молча игнорируем
                break;
        }
    }

    private void handleEscape(char c) {
        switch (c) {
            case '[':
                params.setLength(0);
                state = S_CSI;
                break;
            case ']':
                state = S_OSC;
                break;
            case '7':
                screen.saveCursor();
                state = S_TEXT;
                break;
            case '8':
                screen.restoreCursor();
                state = S_TEXT;
                break;
            case 'D':
                screen.lineFeed();
                state = S_TEXT;
                break;
            case 'E':
                screen.nextLine(1);
                state = S_TEXT;
                break;
            case 'M':
                screen.reverseIndex();
                state = S_TEXT;
                break;
            case 'c':
                screen.reset();
                state = S_TEXT;
                break;
            case 'H':
                screen.setTabStop();
                state = S_TEXT;
                break;
            case '(':
            case ')':
            case '*':
            case '+':
            case '#':
            case '%':
                state = S_CHARSET;
                break;
            case '=':
            case '>':
                state = S_TEXT;
                break;
            default:
                state = S_TEXT;
                break;
        }
    }

    private void handleCsi(char c) {
        if (c == 0x1b) {
            state = S_ESC;
            return;
        }
        if (c >= 0x20 && c <= 0x3f) {
            params.append(c);
            return;
        }
        if (c >= 0x40 && c <= 0x7e) {
            int[] values = parseParams(params.toString());
            if (reply != null) {
                replySeq(c, values);
            }
            dispatch(c, values);
            params.setLength(0);
            state = S_TEXT;
            return;
        }
        // C0 внутри CSI: выполняем и продолжаем ждать финальный байт
        if (c < 0x20) {
            handleControl(c);
        }
    }

    private int[] parseParams(String raw) {
        String s = raw;
        boolean priv = false;
        int start = 0;
        if (s.length() > 0 && (s.charAt(0) == '?' || s.charAt(0) == '>' || s.charAt(0) == '<' || s.charAt(0) == '=')) {
            priv = s.charAt(0) == '?';
            start = 1;
        }
        if (start >= s.length()) {
            return priv ? new int[] { -2 } : new int[0];
        }
        s = s.substring(start);
        // отбрасываем промежуточные байты (0x20..0x2f) на конце, они нам не нужны
        while (s.length() > 0 && s.charAt(s.length() - 1) >= 0x20 && s.charAt(s.length() - 1) <= 0x2f) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.length() == 0) {
            return priv ? new int[] { -2 } : new int[0];
        }
        String[] parts = s.split(";", -1);
        int[] out = new int[parts.length + (priv ? 1 : 0)];
        int base = 0;
        if (priv) {
            out[0] = -2; // маркер приватной последовательности
            base = 1;
        }
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            int v = -1;
            if (p.length() > 0) {
                try {
                    v = Integer.parseInt(p);
                } catch (NumberFormatException e) {
                    v = -1;
                }
            }
            out[base + i] = v;
        }
        return out;
    }

    private int p(int[] values, int index, int def) {
        if (values == null || index >= values.length) {
            return def;
        }
        int v = values[index];
        return v <= 0 ? def : v;
    }

    // ------------------------------------------------------------ обратные ответы

    private void replySeq(char fin, int[] values) {
        switch (fin) {
            case 'n':
                if (p(values, 0, 0) == 6) {
                    reply.onReply("\u001b[" + (screen.getCursorY() + 1) + ";" + (screen.getCursorX() + 1) + "R");
                } else if (p(values, 0, 0) == 5) {
                    reply.onReply("\u001b[0n");
                }
                break;
            case 'c':
                if (values != null && values.length > 0 && values[0] == -2) {
                    reply.onReply("\u001b[?6c"); // VT102
                } else {
                    reply.onReply("\u001b[?6c");
                }
                break;
            case 't':
                if (p(values, 0, 0) == 18) {
                    reply.onReply("\u001b[8;" + screen.getRows() + ";" + screen.getCols() + "t");
                }
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ диспетчер

    private void dispatch(char fin, int[] values) {
        boolean priv = values != null && values.length > 0 && values[0] == -2;
        int first = priv ? (values.length > 1 ? values[1] : -1) : (values != null && values.length > 0 ? values[0] : -1);

        switch (fin) {
            case 'A':
                screen.cursorUp(p(values, priv ? 1 : 0, 1));
                break;
            case 'B':
                screen.cursorDown(p(values, priv ? 1 : 0, 1));
                break;
            case 'e':
                screen.cursorDown(p(values, priv ? 1 : 0, 1));
                break;
            case 'C':
                screen.cursorForward(p(values, priv ? 1 : 0, 1));
                break;
            case 'a':
                screen.cursorForward(p(values, priv ? 1 : 0, 1));
                break;
            case 'D':
                screen.cursorBack(p(values, priv ? 1 : 0, 1));
                break;
            case 'E':
                screen.nextLine(p(values, priv ? 1 : 0, 1));
                break;
            case 'F':
                screen.previousLine(p(values, priv ? 1 : 0, 1));
                break;
            case 'G':
            case '`':
                screen.columnAbsolute(p(values, priv ? 1 : 0, 1));
                break;
            case 'd':
                screen.rowAbsolute(p(values, priv ? 1 : 0, 1));
                break;
            case 'H':
            case 'f':
                screen.setCursor(p(values, priv ? 1 : 0, 1), p(values, priv ? 2 : 1, 1));
                break;
            case 'I':
                for (int i = 0; i < p(values, priv ? 1 : 0, 1); i++) {
                    screen.tab();
                }
                break;
            case 'Z':
                for (int i = 0; i < p(values, priv ? 1 : 0, 1); i++) {
                    screen.cursorBack(1);
                }
                break;
            case 'J':
                screen.eraseInDisplay(p(values, priv ? 1 : 0, 0));
                break;
            case 'K':
                screen.eraseInLine(p(values, priv ? 1 : 0, 0));
                break;
            case 'L':
                screen.insertLines(p(values, priv ? 1 : 0, 1));
                break;
            case 'M':
                screen.deleteLines(p(values, priv ? 1 : 0, 1));
                break;
            case 'P':
                screen.deleteChars(p(values, priv ? 1 : 0, 1));
                break;
            case 'X':
                screen.eraseChars(p(values, priv ? 1 : 0, 1));
                break;
            case '@':
                screen.insertChars(p(values, priv ? 1 : 0, 1));
                break;
            case 'S':
                screen.scrollUp(p(values, priv ? 1 : 0, 1));
                break;
            case 'T':
                screen.scrollDown(p(values, priv ? 1 : 0, 1));
                break;
            case 'b':
                for (int i = 0; i < p(values, priv ? 1 : 0, 1); i++) {
                    screen.putChar(lastPrinted);
                }
                break;
            case 'g':
                screen.clearTabStop(p(values, priv ? 1 : 0, 0));
                break;
            case 'm':
                screen.setGraphicRendition(sgrParams(values, priv));
                break;
            case 'h':
                if (priv) {
                    applyPrivateModes(values, true);
                }
                break;
            case 'l':
                if (priv) {
                    applyPrivateModes(values, false);
                }
                break;
            case 'r':
                screen.setScrollRegion(p(values, 0, 1), p(values, 1, screen.getRows()));
                break;
            case 's':
                screen.saveCursor();
                break;
            case 'u':
                screen.restoreCursor();
                break;
            default:
                // 'c', 'n', 't' обработаны в replySeq, остальное игнорируем
                break;
        }
    }

    private int[] sgrParams(int[] values, boolean priv) {
        if (values == null || values.length == 0) {
            return new int[0];
        }
        int skip = priv ? 1 : 0;
        int n = values.length - skip;
        if (n <= 0) {
            return new int[0];
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int v = values[skip + i];
            out[i] = v <= 0 ? 0 : v;
        }
        return out;
    }

    private void applyPrivateModes(int[] values, boolean on) {
        if (values == null) {
            return;
        }
        for (int i = 1; i < values.length; i++) {
            if (values[i] > 0) {
                screen.setPrivateMode(values[i], on);
            }
        }
    }
}
