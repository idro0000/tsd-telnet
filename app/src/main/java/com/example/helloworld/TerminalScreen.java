package com.example.helloworld;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Экранная модель терминала: сетка символов с атрибутами, курсор, область прокрутки
 * и буфер истории (scrollback).
 *
 * Полноценного VT100 в Android нет, поэтому нужный для складских меню набор возможностей
 * (позиционирование курсора, очистка, цвета, альтернативный экран) собран здесь руками.
 *
 * ВАЖНО: методы не синхронизированы. Поток чтения сокета и поток отрисовки обязаны
 * работать с объектом под общим монитором (synchronized (screen)).
 */
public class TerminalScreen {

    public static final int FLAG_BOLD = 1;
    public static final int FLAG_DIM = 2;
    public static final int FLAG_UNDERLINE = 4;
    public static final int FLAG_BLINK = 8;
    public static final int FLAG_REVERSE = 16;
    public static final int FLAG_HIDDEN = 32;
    public static final int FLAG_ITALIC = 64;

    public static final int MODE_WRAP = 1;
    public static final int MODE_ORIGIN = 2;
    public static final int MODE_CURSOR_VISIBLE = 4;
    public static final int MODE_ALT = 8;

    public static final int DEFAULT_FG = 7;
    public static final int DEFAULT_BG = 0;

    /** Ссылка на строку экрана или истории. Один объект переиспользуется при отрисовке. */
    public static final class LineRef {
        public char[] text;
        public byte[] flags;
        public byte[] fg;
        public byte[] bg;
        public int offset;
        public int length;
    }

    private static final class Buffer {
        char[] text;
        byte[] flags;
        byte[] fg;
        byte[] bg;
        int cols;
        int rows;
        int cursorX;
        int cursorY;
        int savedX;
        int savedY;
        byte savedFg;
        byte savedBg;
        byte savedFlags;

        Buffer(int cols, int rows) {
            this.cols = cols;
            this.rows = rows;
            text = new char[cols * rows];
            flags = new byte[cols * rows];
            fg = new byte[cols * rows];
            bg = new byte[cols * rows];
            wipe();
        }

        void wipe() {
            Arrays.fill(text, ' ');
            Arrays.fill(flags, (byte) 0);
            Arrays.fill(fg, (byte) DEFAULT_FG);
            Arrays.fill(bg, (byte) DEFAULT_BG);
            cursorX = 0;
            cursorY = 0;
            savedX = 0;
            savedY = 0;
            savedFg = DEFAULT_FG;
            savedBg = DEFAULT_BG;
            savedFlags = 0;
        }
    }

    private static final class Line {
        char[] text;
        byte[] flags;
        byte[] fg;
        byte[] bg;
    }

    private int cols;
    private int rows;
    private int scrollbackLimit;

    private Buffer main;
    private Buffer alt;
    private Buffer buffer;

    private final ArrayDeque<Line> scrollback = new ArrayDeque<Line>();

    private int scrollTop;
    private int scrollBottom;

    private byte penFg = DEFAULT_FG;
    private byte penBg = DEFAULT_BG;
    private byte penFlags;

    private int modes;
    private boolean wrapPending;

    private final boolean[] tabs = new boolean[512];

    public TerminalScreen(int cols, int rows, int scrollbackLimit) {
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
        this.scrollbackLimit = Math.max(0, scrollbackLimit);
        main = new Buffer(this.cols, this.rows);
        alt = new Buffer(this.cols, this.rows);
        buffer = main;
        resetTabStops();
        reset();
    }

    private void resetTabStops() {
        Arrays.fill(tabs, false);
        for (int i = 8; i < tabs.length; i += 8) {
            tabs[i] = true;
        }
    }

    public void reset() {
        main.wipe();
        alt.wipe();
        buffer = main;
        scrollTop = 0;
        scrollBottom = rows - 1;
        penFg = DEFAULT_FG;
        penBg = DEFAULT_BG;
        penFlags = 0;
        wrapPending = false;
        modes = MODE_WRAP | MODE_CURSOR_VISIBLE;
        scrollback.clear();
        resetTabStops();
    }

    // ------------------------------------------------------------------ доступ

    public int getCols() {
        return cols;
    }

    public int getRows() {
        return rows;
    }

    public int getScrollbackLimit() {
        return scrollbackLimit;
    }

    public boolean isCursorVisible() {
        return (modes & MODE_CURSOR_VISIBLE) != 0;
    }

    public int getCursorX() {
        return buffer.cursorX;
    }

    public int getCursorY() {
        return buffer.cursorY;
    }

    public boolean isAlternateScreen() {
        return (modes & MODE_ALT) != 0;
    }

    public int getScrollbackCount() {
        return scrollback.size();
    }

    /** Всего строк для отрисовки: история + живой экран. */
    public int totalLines() {
        return scrollback.size() + rows;
    }

    /**
     * Возвращает строку по индексу (0 — самая старая в истории, totalLines()-1 — низ экрана).
     * Параметр out переиспользуется, новых объектов не создаётся.
     */
    public void getLine(int index, LineRef out) {
        int sb = scrollback.size();
        if (index < 0) {
            index = 0;
        }
        if (index < sb) {
            Line line = null;
            if (index < sb / 2) {
                Iterator<Line> it = scrollback.iterator();
                for (int i = 0; i <= index; i++) {
                    line = it.next();
                }
            } else {
                Iterator<Line> it = scrollback.descendingIterator();
                int steps = sb - 1 - index;
                for (int i = 0; i <= steps; i++) {
                    line = it.next();
                }
            }
            out.text = line.text;
            out.flags = line.flags;
            out.fg = line.fg;
            out.bg = line.bg;
            out.offset = 0;
            out.length = line.text.length;
            return;
        }
        int row = index - sb;
        if (row >= rows) {
            row = rows - 1;
        }
        out.text = buffer.text;
        out.flags = buffer.flags;
        out.fg = buffer.fg;
        out.bg = buffer.bg;
        out.offset = row * cols;
        out.length = cols;
    }

    public char[] rawText() {
        return buffer.text;
    }

    public byte[] rawFlags() {
        return buffer.flags;
    }

    public byte[] rawFg() {
        return buffer.fg;
    }

    public byte[] rawBg() {
        return buffer.bg;
    }

    public int getScrollTop() {
        return scrollTop;
    }

    public int getScrollBottom() {
        return scrollBottom;
    }

    // -------------------------------------------------------------- изменение

    public void resize(int newCols, int newRows) {
        newCols = Math.max(1, newCols);
        newRows = Math.max(1, newRows);
        if (newCols == cols && newRows == rows) {
            return;
        }
        main = resizeBuffer(main, newCols, newRows);
        alt = resizeBuffer(alt, newCols, newRows);
        buffer = ((modes & MODE_ALT) != 0) ? alt : main;
        cols = newCols;
        rows = newRows;
        scrollTop = 0;
        scrollBottom = rows - 1;
        clampCursor();
        // ширины старых строк истории не совпадают с новой сеткой — историю сбрасываем
        scrollback.clear();
    }

    private Buffer resizeBuffer(Buffer old, int newCols, int newRows) {
        Buffer fresh = new Buffer(newCols, newRows);
        fresh.cursorX = Math.min(old.cursorX, newCols - 1);
        fresh.cursorY = Math.min(old.cursorY, newRows - 1);
        fresh.savedX = Math.min(old.savedX, newCols - 1);
        fresh.savedY = Math.min(old.savedY, newRows - 1);
        fresh.savedFg = old.savedFg;
        fresh.savedBg = old.savedBg;
        fresh.savedFlags = old.savedFlags;
        int copyRows = Math.min(old.rows, newRows);
        int copyCols = Math.min(old.cols, newCols);
        for (int r = 0; r < copyRows; r++) {
            for (int c = 0; c < copyCols; c++) {
                int src = r * old.cols + c;
                int dst = r * newCols + c;
                if (src < old.text.length && dst < fresh.text.length) {
                    fresh.text[dst] = old.text[src];
                    fresh.flags[dst] = old.flags[src];
                    fresh.fg[dst] = old.fg[src];
                    fresh.bg[dst] = old.bg[src];
                }
            }
        }
        return fresh;
    }

    public void putChar(char c) {
        if (wrapPending) {
            carriageReturn();
            lineFeed();
            wrapPending = false;
        }
        int idx = buffer.cursorY * cols + buffer.cursorX;
        if (idx >= 0 && idx < buffer.text.length) {
            buffer.text[idx] = c;
            buffer.flags[idx] = penFlags;
            buffer.fg[idx] = penFg;
            buffer.bg[idx] = penBg;
        }
        if (buffer.cursorX >= cols - 1) {
            if ((modes & MODE_WRAP) != 0) {
                wrapPending = true;
            }
        } else {
            buffer.cursorX++;
        }
    }

    public void carriageReturn() {
        buffer.cursorX = 0;
        wrapPending = false;
    }

    public void lineFeed() {
        wrapPending = false;
        if (buffer.cursorY == scrollBottom) {
            scrollUp(1);
        } else if (buffer.cursorY < rows - 1) {
            buffer.cursorY++;
        }
    }

    public void reverseIndex() {
        wrapPending = false;
        if (buffer.cursorY == scrollTop) {
            scrollDown(1);
        } else if (buffer.cursorY > 0) {
            buffer.cursorY--;
        }
    }

    public void backspace() {
        wrapPending = false;
        if (buffer.cursorX > 0) {
            buffer.cursorX--;
        }
    }

    public void tab() {
        wrapPending = false;
        for (int x = buffer.cursorX + 1; x < cols; x++) {
            if (x < tabs.length && tabs[x]) {
                buffer.cursorX = x;
                return;
            }
        }
        buffer.cursorX = cols - 1;
    }

    public void setTabStop() {
        if (buffer.cursorX < tabs.length) {
            tabs[buffer.cursorX] = true;
        }
    }

    public void clearTabStop(int mode) {
        if (mode == 3) {
            Arrays.fill(tabs, false);
        } else if (buffer.cursorX < tabs.length) {
            tabs[buffer.cursorX] = false;
        }
    }

    public void cursorUp(int n) {
        buffer.cursorY -= Math.max(1, n);
        if (buffer.cursorY < scrollTop) {
            buffer.cursorY = scrollTop;
        }
        wrapPending = false;
    }

    public void cursorDown(int n) {
        buffer.cursorY += Math.max(1, n);
        if (buffer.cursorY > scrollBottom) {
            buffer.cursorY = scrollBottom;
        }
        wrapPending = false;
    }

    public void cursorForward(int n) {
        buffer.cursorX += Math.max(1, n);
        if (buffer.cursorX > cols - 1) {
            buffer.cursorX = cols - 1;
        }
        wrapPending = false;
    }

    public void cursorBack(int n) {
        buffer.cursorX -= Math.max(1, n);
        if (buffer.cursorX < 0) {
            buffer.cursorX = 0;
        }
        wrapPending = false;
    }

    public void nextLine(int n) {
        cursorDown(n);
        carriageReturn();
    }

    public void previousLine(int n) {
        cursorUp(n);
        carriageReturn();
    }

    /** row и col — 1-based, как в VT100. */
    public void setCursor(int row, int col) {
        int r = Math.max(0, row - 1);
        int c = Math.max(0, col - 1);
        if ((modes & MODE_ORIGIN) != 0) {
            r += scrollTop;
            if (r > scrollBottom) {
                r = scrollBottom;
            }
        }
        buffer.cursorY = Math.min(r, rows - 1);
        buffer.cursorX = Math.min(c, cols - 1);
        wrapPending = false;
    }

    public void rowAbsolute(int row) {
        setCursor(row, buffer.cursorX + 1);
    }

    public void columnAbsolute(int col) {
        setCursor(buffer.cursorY + 1, col);
    }

    public void saveCursor() {
        buffer.savedX = buffer.cursorX;
        buffer.savedY = buffer.cursorY;
        buffer.savedFg = penFg;
        buffer.savedBg = penBg;
        buffer.savedFlags = penFlags;
    }

    public void restoreCursor() {
        buffer.cursorX = Math.min(buffer.savedX, cols - 1);
        buffer.cursorY = Math.min(buffer.savedY, rows - 1);
        penFg = buffer.savedFg;
        penBg = buffer.savedBg;
        penFlags = buffer.savedFlags;
        wrapPending = false;
    }

    /** Режимы из DEC (CSI ? n h/l). */
    public void setPrivateMode(int mode, boolean on) {
        switch (mode) {
            case 1:
            case 7:
                setMode(MODE_WRAP, on);
                break;
            case 6:
                setMode(MODE_ORIGIN, on);
                setCursor(1, 1);
                break;
            case 25:
                setMode(MODE_CURSOR_VISIBLE, on);
                break;
            case 47:
            case 1047:
            case 1049:
                if (on) {
                    if ((modes & MODE_ALT) == 0) {
                        saveCursor();
                        alt.wipe();
                        buffer = alt;
                        modes |= MODE_ALT;
                        scrollTop = 0;
                        scrollBottom = rows - 1;
                    }
                } else {
                    if ((modes & MODE_ALT) != 0) {
                        modes &= ~MODE_ALT;
                        buffer = main;
                        restoreCursor();
                        scrollTop = 0;
                        scrollBottom = rows - 1;
                    }
                }
                break;
            case 12:
            case 2004:
            default:
                break;
        }
    }

    public void setMode(int bits, boolean on) {
        if (on) {
            modes |= bits;
        } else {
            modes &= ~bits;
        }
    }

    private void clampCursor() {
        if (buffer.cursorX > cols - 1) {
            buffer.cursorX = cols - 1;
        }
        if (buffer.cursorY > rows - 1) {
            buffer.cursorY = rows - 1;
        }
        if (buffer.cursorX < 0) {
            buffer.cursorX = 0;
        }
        if (buffer.cursorY < 0) {
            buffer.cursorY = 0;
        }
    }

    // ------------------------------------------------------------------ очистка

    public void eraseInDisplay(int mode) {
        switch (mode) {
            case 0:
                eraseInLine(0);
                fillRows(buffer.cursorY + 1, rows - 1);
                break;
            case 1:
                eraseInLine(1);
                fillRows(0, buffer.cursorY - 1);
                break;
            case 2:
                fillRows(0, rows - 1);
                break;
            case 3:
                scrollback.clear();
                fillRows(0, rows - 1);
                break;
            default:
                break;
        }
    }

    public void eraseInLine(int mode) {
        int y = buffer.cursorY;
        if (y < 0 || y >= rows) {
            return;
        }
        int from;
        int to;
        switch (mode) {
            case 0:
                from = buffer.cursorX;
                to = cols - 1;
                break;
            case 1:
                from = 0;
                to = buffer.cursorX;
                break;
            case 2:
                from = 0;
                to = cols - 1;
                break;
            default:
                return;
        }
        fillCells(y, from, to);
    }

    public void eraseChars(int n) {
        n = Math.max(1, n);
        fillCells(buffer.cursorY, buffer.cursorX, Math.min(cols - 1, buffer.cursorX + n - 1));
    }

    private void fillRows(int fromRow, int toRow) {
        if (fromRow < 0) {
            fromRow = 0;
        }
        if (toRow >= rows) {
            toRow = rows - 1;
        }
        for (int r = fromRow; r <= toRow; r++) {
            fillCells(r, 0, cols - 1);
        }
    }

    private void fillCells(int row, int fromCol, int toCol) {
        if (row < 0 || row >= rows) {
            return;
        }
        if (fromCol < 0) {
            fromCol = 0;
        }
        if (toCol > cols - 1) {
            toCol = cols - 1;
        }
        // очистка всегда идёт текущими атрибутами (как в xterm с BCE), но чаще всего это дефолт
        byte f = penFlags;
        for (int c = fromCol; c <= toCol; c++) {
            int idx = row * cols + c;
            if (idx < 0 || idx >= buffer.text.length) {
                continue;
            }
            buffer.text[idx] = ' ';
            buffer.flags[idx] = f;
            buffer.fg[idx] = penFg;
            buffer.bg[idx] = penBg;
        }
    }

    // -------------------------------------------------------------- вставка/удаление

    public void insertLines(int n) {
        n = Math.max(1, n);
        if (buffer.cursorY < scrollTop || buffer.cursorY > scrollBottom) {
            return;
        }
        for (int i = 0; i < n; i++) {
            for (int r = scrollBottom; r > buffer.cursorY; r--) {
                copyRow(r - 1, r);
            }
            fillCells(buffer.cursorY, 0, cols - 1);
        }
    }

    public void deleteLines(int n) {
        n = Math.max(1, n);
        if (buffer.cursorY < scrollTop || buffer.cursorY > scrollBottom) {
            return;
        }
        for (int i = 0; i < n; i++) {
            for (int r = buffer.cursorY; r < scrollBottom; r++) {
                copyRow(r + 1, r);
            }
            fillCells(scrollBottom, 0, cols - 1);
        }
    }

    public void insertChars(int n) {
        n = Math.max(1, n);
        int y = buffer.cursorY;
        int x = buffer.cursorX;
        for (int c = cols - 1; c >= x; c--) {
            int src = y * cols + c - n;
            int dst = y * cols + c;
            if (src < y * cols + x) {
                buffer.text[dst] = ' ';
                buffer.flags[dst] = penFlags;
                buffer.fg[dst] = penFg;
                buffer.bg[dst] = penBg;
            } else {
                buffer.text[dst] = buffer.text[src];
                buffer.flags[dst] = buffer.flags[src];
                buffer.fg[dst] = buffer.fg[src];
                buffer.bg[dst] = buffer.bg[src];
            }
        }
    }

    public void deleteChars(int n) {
        n = Math.max(1, n);
        int y = buffer.cursorY;
        int x = buffer.cursorX;
        for (int c = x; c < cols; c++) {
            int src = c + n;
            int dst = y * cols + c;
            if (src < cols) {
                buffer.text[dst] = buffer.text[y * cols + src];
                buffer.flags[dst] = buffer.flags[y * cols + src];
                buffer.fg[dst] = buffer.fg[y * cols + src];
                buffer.bg[dst] = buffer.bg[y * cols + src];
            } else {
                buffer.text[dst] = ' ';
                buffer.flags[dst] = penFlags;
                buffer.fg[dst] = penFg;
                buffer.bg[dst] = penBg;
            }
        }
    }

    private void copyRow(int fromRow, int toRow) {
        int src = fromRow * cols;
        int dst = toRow * cols;
        for (int c = 0; c < cols; c++) {
            if (src + c >= buffer.text.length || dst + c >= buffer.text.length) {
                break;
            }
            buffer.text[dst + c] = buffer.text[src + c];
            buffer.flags[dst + c] = buffer.flags[src + c];
            buffer.fg[dst + c] = buffer.fg[src + c];
            buffer.bg[dst + c] = buffer.bg[src + c];
        }
    }

    // ------------------------------------------------------------------ прокрутка

    public void scrollUp(int n) {
        n = Math.max(1, n);
        for (int i = 0; i < n; i++) {
            if (buffer == main && scrollTop == 0 && scrollbackLimit > 0) {
                scrollback.addLast(snapshotRow(0));
                while (scrollback.size() > scrollbackLimit) {
                    scrollback.removeFirst();
                }
            }
            for (int r = scrollTop; r < scrollBottom; r++) {
                copyRow(r + 1, r);
            }
            fillCells(scrollBottom, 0, cols - 1);
        }
    }

    public void scrollDown(int n) {
        n = Math.max(1, n);
        for (int i = 0; i < n; i++) {
            for (int r = scrollBottom; r > scrollTop; r--) {
                copyRow(r - 1, r);
            }
            fillCells(scrollTop, 0, cols - 1);
        }
    }

    private Line snapshotRow(int row) {
        Line line = new Line();
        line.text = new char[cols];
        line.flags = new byte[cols];
        line.fg = new byte[cols];
        line.bg = new byte[cols];
        int base = row * cols;
        for (int c = 0; c < cols; c++) {
            line.text[c] = buffer.text[base + c];
            line.flags[c] = buffer.flags[base + c];
            line.fg[c] = buffer.fg[base + c];
            line.bg[c] = buffer.bg[base + c];
        }
        return line;
    }

    public void setScrollRegion(int top, int bottom) {
        if (top < 1) {
            top = 1;
        }
        if (bottom > rows) {
            bottom = rows;
        }
        if (top >= bottom) {
            return;
        }
        scrollTop = top - 1;
        scrollBottom = bottom - 1;
        setCursor(1, 1);
    }

    // ------------------------------------------------------------------ атрибуты

    public void setGraphicRendition(int[] params) {
        if (params == null || params.length == 0) {
            resetPen();
            return;
        }
        for (int i = 0; i < params.length; i++) {
            int p = params[i];
            switch (p) {
                case 0:
                    resetPen();
                    break;
                case 1:
                    penFlags |= FLAG_BOLD;
                    break;
                case 2:
                    penFlags |= FLAG_DIM;
                    break;
                case 3:
                    penFlags |= FLAG_ITALIC;
                    break;
                case 4:
                    penFlags |= FLAG_UNDERLINE;
                    break;
                case 5:
                case 6:
                    penFlags |= FLAG_BLINK;
                    break;
                case 7:
                    penFlags |= FLAG_REVERSE;
                    break;
                case 8:
                    penFlags |= FLAG_HIDDEN;
                    break;
                case 22:
                    penFlags &= ~(FLAG_BOLD | FLAG_DIM);
                    break;
                case 23:
                    penFlags &= ~FLAG_ITALIC;
                    break;
                case 24:
                    penFlags &= ~FLAG_UNDERLINE;
                    break;
                case 25:
                    penFlags &= ~FLAG_BLINK;
                    break;
                case 27:
                    penFlags &= ~FLAG_REVERSE;
                    break;
                case 28:
                    penFlags &= ~FLAG_HIDDEN;
                    break;
                case 39:
                    penFg = DEFAULT_FG;
                    break;
                case 49:
                    penBg = DEFAULT_BG;
                    break;
                default:
                    if (p >= 30 && p <= 37) {
                        penFg = (byte) (p - 30);
                    } else if (p >= 40 && p <= 47) {
                        penBg = (byte) (p - 40);
                    } else if (p >= 90 && p <= 97) {
                        penFg = (byte) (p - 90 + 8);
                    } else if (p >= 100 && p <= 107) {
                        penBg = (byte) (p - 100 + 8);
                    } else if (p == 38 || p == 48) {
                        // 38;5;n  или  38;2;r;g;b — приближаем к палитре 16 цветов
                        int value = -1;
                        if (i + 1 < params.length && params[i + 1] == 5 && i + 2 < params.length) {
                            value = params[i + 2];
                            i += 2;
                        } else if (i + 1 < params.length && params[i + 1] == 2 && i + 4 < params.length) {
                            value = rgbToIndex(params[i + 2], params[i + 3], params[i + 4]);
                            i += 4;
                        }
                        if (value >= 0) {
                            int idx = value >= 16 ? nearestPalette(value) : value;
                            if (p == 38) {
                                penFg = (byte) idx;
                            } else {
                                penBg = (byte) idx;
                            }
                        }
                    }
                    break;
            }
        }
    }

    private int rgbToIndex(int r, int g, int b) {
        // грубая оценка яркости: достаточно для 16-цветной палитры
        int level = (r + g + b) / 3;
        if (level < 40) {
            return 0;
        }
        if (level > 220) {
            return 7;
        }
        return 7;
    }

    private int nearestPalette(int color256) {
        if (color256 < 16) {
            return color256;
        }
        if (color256 >= 232) {
            int gray = color256 - 232;
            return gray > 12 ? 7 : (gray > 6 ? 8 : 0);
        }
        int c = color256 - 16;
        int r = c / 36;
        int g = (c % 36) / 6;
        int b = c % 6;
        boolean bright = (r + g + b) > 9;
        int idx = (b > 2 ? 4 : 0) | (g > 2 ? 2 : 0) | (r > 2 ? 1 : 0);
        return bright ? idx + 8 : idx;
    }

    private void resetPen() {
        penFg = DEFAULT_FG;
        penBg = DEFAULT_BG;
        penFlags = 0;
    }

    public byte getPenFg() {
        return penFg;
    }

    public byte getPenBg() {
        return penBg;
    }

    public byte getPenFlags() {
        return penFlags;
    }
}
