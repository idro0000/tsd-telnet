package com.example.helloworld;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Отрисовка экранной модели терминала: сетка символов, цвета ANSI, позиционирование
 * курсора, прокрутка истории пальцем.
 *
 * Данные берутся из {@link TerminalScreen}. Разбор потока и отрисовка синхронизируются
 * по объекту экрана, поэтому посторонних локов здесь нет.
 *
 * ВАЖНО: весь код в пределах API 15 (Android 4.0.4) — только android.graphics + widgets.
 */
public class TerminalView extends View {

    /** Обратные вызовы для Activity. */
    public interface Listener {
        void onLongPress();
    }

    private static final int[] PALETTE_DARK = {
            0xFF000000, 0xFFAA0000, 0xFF00AA00, 0xFFAA5500,
            0xFF0000AA, 0xFFAA00AA, 0xFF00AAAA, 0xFFAAAAAA,
            0xFF555555, 0xFFFF5555, 0xFF55FF55, 0xFFFFFF55,
            0xFF5555FF, 0xFFFF55FF, 0xFF55FFFF, 0xFFFFFFFF
    };

    /** Светлая схема: дефолтная пара цветов (7/0) инвертирована, чтобы текст был тёмным на белом. */
    private static final int[] PALETTE_LIGHT = {
            0xFFFFFFFF, 0xFFAA0000, 0xFF007000, 0xFF806000,
            0xFF0000AA, 0xFFAA00AA, 0xFF007070, 0xFF1A1A1A,
            0xFF808080, 0xFFFF0000, 0xFF00A000, 0xFFAA8000,
            0xFF0000FF, 0xFFCC00CC, 0xFF008080, 0xFF000000
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TerminalScreen.LineRef lineRef = new TerminalScreen.LineRef();
    private final char[] boxAscii = new char[0x2600];

    private char[] runBuf = new char[256];

    private TerminalScreen screen;
    private Listener listener;

    private int[] palette = PALETTE_DARK;
    private boolean brightBold = true;
    private boolean asciiBox;
    private boolean builtinFont = true;
    private Typeface monoTypeface = Typeface.MONOSPACE;
    /** Минимальная сетка, которая обязана влезть в авто-режиме: меньше колонок — крупнее буквы. */
    private int minCols = 40;
    private int minRows = 12;

    private int fontPx = 12;
    private int maxFontPx = 20;
    private boolean autoSize = true;
    private int cellW = 6;
    private int cellH = 14;
    private float baseline = 11f;

    private int scrollOffset;
    private float lastTouchY;
    private long touchDownTime;
    private boolean dragging;

    public TerminalView(Context context) {
        super(context);
        init();
    }

    // ВАЖНО: конструктор с AttributeSet обязателен — виджет создаётся из разметки
    // (activity_main.xml). Без него setContentView падает с InflateException
    // и приложение закрывается сразу при запуске.
    public TerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TerminalView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        monoTypeface = loadTypeface(getContext(), builtinFont);
        paint.setTypeface(monoTypeface);
        paint.setStyle(Paint.Style.FILL);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setKeepScreenOn(true);
        buildBoxAscii();
        measureMetrics(fontPx);
    }

    /**
     * Псевдографику часть прошивок ТСД не содержит в шрифте — тогда её подменяют ASCII.
     * Таблица соответствия для основных символов рамок, блоков и стрелок.
     */
    private void buildBoxAscii() {
        boxAscii[0x2500] = '-';
        boxAscii[0x2501] = '=';
        boxAscii[0x2502] = '|';
        boxAscii[0x2503] = '|';
        boxAscii[0x2504] = '-';
        boxAscii[0x2505] = '=';
        boxAscii[0x2506] = '|';
        boxAscii[0x2507] = '|';
        boxAscii[0x2508] = '-';
        boxAscii[0x2509] = '=';
        boxAscii[0x250A] = '|';
        boxAscii[0x250B] = '|';
        boxAscii[0x250C] = '+';
        boxAscii[0x250D] = '+';
        boxAscii[0x250E] = '+';
        boxAscii[0x250F] = '+';
        boxAscii[0x2510] = '+';
        boxAscii[0x2511] = '+';
        boxAscii[0x2512] = '+';
        boxAscii[0x2513] = '+';
        boxAscii[0x2514] = '+';
        boxAscii[0x2515] = '+';
        boxAscii[0x2516] = '+';
        boxAscii[0x2517] = '+';
        boxAscii[0x2518] = '+';
        boxAscii[0x2519] = '+';
        boxAscii[0x251A] = '+';
        boxAscii[0x251B] = '+';
        boxAscii[0x251C] = '+';
        boxAscii[0x251D] = '+';
        boxAscii[0x251E] = '+';
        boxAscii[0x251F] = '+';
        boxAscii[0x2520] = '+';
        boxAscii[0x2521] = '+';
        boxAscii[0x2522] = '+';
        boxAscii[0x2523] = '+';
        boxAscii[0x2524] = '+';
        boxAscii[0x2525] = '+';
        boxAscii[0x2526] = '+';
        boxAscii[0x2527] = '+';
        boxAscii[0x2528] = '+';
        boxAscii[0x2529] = '+';
        boxAscii[0x252A] = '+';
        boxAscii[0x252B] = '+';
        boxAscii[0x252C] = '+';
        boxAscii[0x252D] = '+';
        boxAscii[0x252E] = '+';
        boxAscii[0x252F] = '+';
        boxAscii[0x2530] = '+';
        boxAscii[0x2531] = '+';
        boxAscii[0x2532] = '+';
        boxAscii[0x2533] = '+';
        boxAscii[0x2534] = '+';
        boxAscii[0x2535] = '+';
        boxAscii[0x2536] = '+';
        boxAscii[0x2537] = '+';
        boxAscii[0x2538] = '+';
        boxAscii[0x2539] = '+';
        boxAscii[0x253A] = '+';
        boxAscii[0x253B] = '+';
        boxAscii[0x253C] = '+';
        boxAscii[0x253D] = '+';
        boxAscii[0x253E] = '+';
        boxAscii[0x253F] = '+';
        boxAscii[0x2550] = '=';
        boxAscii[0x2551] = '|';
        boxAscii[0x2554] = '+';
        boxAscii[0x2557] = '+';
        boxAscii[0x255A] = '+';
        boxAscii[0x255D] = '+';
        boxAscii[0x2560] = '+';
        boxAscii[0x2563] = '+';
        boxAscii[0x2566] = '+';
        boxAscii[0x2569] = '+';
        boxAscii[0x256C] = '+';
        boxAscii[0x2588] = '#';
        boxAscii[0x2591] = '.';
        boxAscii[0x2592] = ':';
        boxAscii[0x2593] = '#';
        boxAscii[0x25A0] = '#';
        boxAscii[0x25CF] = '*';
        boxAscii[0x2022] = '*';
        boxAscii[0x2190] = '<';
        boxAscii[0x2191] = '^';
        boxAscii[0x2192] = '>';
        boxAscii[0x2193] = 'v';
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setScreen(TerminalScreen screen) {
        this.screen = screen;
        invalidate();
    }

    /**
     * Моношрифт для сетки. По умолчанию берём DejaVu Sans Mono из apk: он есть
     * кириллицей и рамками. На части ТСД системный моношрифт без кириллицы —
     * вместо букв получаются ромбики. Если файл не загрузился, откат на системный.
     */
    private static Typeface loadTypeface(Context context, boolean builtin) {
        if (!builtin) {
            return Typeface.MONOSPACE;
        }
        try {
            return Typeface.createFromAsset(context.getAssets(), "DejaVuSansMono.ttf");
        } catch (Throwable e) {
            return Typeface.MONOSPACE;
        }
    }

    public void configure(int fontPx, int maxFontPx, boolean autoSize, boolean light,
                          boolean boxDrawing, boolean brightBold, boolean builtinFont,
                          int minCols, int minRows) {
        this.fontPx = Math.max(5, fontPx);
        this.maxFontPx = Math.max(this.fontPx, maxFontPx);
        this.autoSize = autoSize;
        this.palette = light ? PALETTE_LIGHT : PALETTE_DARK;
        this.asciiBox = boxDrawing;
        this.brightBold = brightBold;
        this.builtinFont = builtinFont;
        this.minCols = clamp(minCols, 16, 120);
        this.minRows = clamp(minRows, 4, 60);
        this.monoTypeface = loadTypeface(getContext(), builtinFont);
        paint.setTypeface(monoTypeface);
        measureMetrics(this.fontPx);
        requestLayout();
        if (getWidth() > 0 && getHeight() > 0) {
            relayout(getWidth(), getHeight());
        }
        invalidate();
    }

    private void measureMetrics(int size) {
        paint.setTextSize(size);
        Paint.FontMetrics fm = paint.getFontMetrics();
        int h = (int) Math.ceil(paint.getFontSpacing());
        cellH = h > 3 ? h : size + 2;
        int w = (int) Math.ceil(paint.measureText("M"));
        cellW = w > 0 ? w : (size / 2 + 1);
        baseline = -fm.ascent;
        float limit = cellH - 1;
        if (baseline > limit) {
            baseline = limit;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        relayout(w, h);
    }

    /** Подгонка шрифта и сетки под размер виджета. */
    private void relayout(int w, int h) {
        if (screen == null || w <= 0 || h <= 0) {
            return;
        }
        if (autoSize) {
            // Ищем наибольший шрифт, при котором влезает минимальная сетка (min_cols x min_rows).
            // Меньше колонок в минимуме — крупнее буквы.
            int size = maxFontPx;
            measureMetrics(size);
            while (size > 6 && (w < cellW * minCols || h < cellH * minRows)) {
                size--;
                measureMetrics(size);
            }
            fontPx = size;
            synchronized (screen) {
                screen.resize(Math.max(20, w / cellW), Math.max(6, h / cellH));
            }
        } else {
            int size = fontPx;
            measureMetrics(size);
            int cols;
            int rows;
            synchronized (screen) {
                cols = screen.getCols();
                rows = screen.getRows();
            }
            while (size > 6 && (cols * cellW > w || rows * cellH > h)) {
                size--;
                measureMetrics(size);
            }
            fontPx = size;
        }
        invalidate();
    }

    /**
     * Шаг «крупнее/мельче». В авто-режиме размер шрифта следует из минимальной сетки,
     * поэтому уменьшаем число колонок: буквы становятся крупнее, но в строке их меньше.
     * В режиме auto_size=0 просто меняется размер шрифта.
     */
    public void zoom(int delta) {
        if (delta == 0) {
            return;
        }
        if (autoSize) {
            minCols = clamp(minCols - delta * 4, 16, 120);
            minRows = clamp(minRows - delta, 4, 60);
            maxFontPx = clamp(maxFontPx + delta * 2, 8, 48);
        } else {
            fontPx = clamp(fontPx + delta, 6, 48);
        }
        relayout(getWidth(), getHeight());
        invalidate();
    }

    public int getMinCols() {
        return minCols;
    }

    public int getMinRows() {
        return minRows;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }

    public int getFontPx() {
        return fontPx;
    }

    public int getCellHeight() {
        return cellH;
    }

    public void scrollToBottom() {
        scrollOffset = 0;
        invalidate();
    }

    public boolean isScrolledBack() {
        return scrollOffset > 0;
    }

    // --------------------------------------------------------------- отрисовка

    @Override
    protected void onDraw(Canvas canvas) {
        if (screen == null) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        paint.setTypeface(monoTypeface);
        paint.setTextSize(fontPx);

        synchronized (screen) {
            int colCount = screen.getCols();
            int totalLines = screen.totalLines();
            int visible = Math.max(1, h / Math.max(1, cellH));
            int maxOffset = Math.max(0, totalLines - visible);
            if (scrollOffset > maxOffset) {
                scrollOffset = maxOffset;
            }
            int bottom = totalLines - scrollOffset;
            int first = Math.max(0, bottom - visible);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(palette[0]);
            canvas.drawRect(0, 0, w, h, paint);

            for (int index = first; index < bottom; index++) {
                screen.getLine(index, lineRef);
                drawLine(canvas, index - first, colCount);
            }

            if (scrollOffset == 0 && screen.isCursorVisible()) {
                drawCursor(canvas, colCount);
            }
        }
    }

    private void drawLine(Canvas canvas, int visualRow, int colCount) {
        char[] text = lineRef.text;
        byte[] flags = lineRef.flags;
        byte[] fg = lineRef.fg;
        byte[] bg = lineRef.bg;
        int offset = lineRef.offset;
        int length = lineRef.length;
        int limit = Math.min(length, colCount);
        int y = visualRow * cellH;

        int x = 0;
        while (x < limit) {
            int i = offset + x;
            byte flagsRun = flags[i];
            int fgRun = fgColor(fg[i], flagsRun);
            int bgRun = bgColor(bg[i]);
            boolean boldRun = (flagsRun & TerminalScreen.FLAG_BOLD) != 0;
            boolean underlineRun = (flagsRun & TerminalScreen.FLAG_UNDERLINE) != 0;
            boolean hiddenRun = (flagsRun & TerminalScreen.FLAG_HIDDEN) != 0;

            int startX = x;
            int count = 0;
            while (x < limit) {
                int j = offset + x;
                byte f2 = flags[j];
                if (fgColor(fg[j], f2) != fgRun
                        || bgColor(bg[j]) != bgRun
                        || ((f2 & TerminalScreen.FLAG_BOLD) != 0) != boldRun
                        || ((f2 & TerminalScreen.FLAG_UNDERLINE) != 0) != underlineRun
                        || ((f2 & TerminalScreen.FLAG_HIDDEN) != 0) != hiddenRun) {
                    break;
                }
                char c = text[j];
                if (runBuf.length < limit) {
                    ensureRunBuf(limit);
                }
                runBuf[count] = translate(c == 0 ? ' ' : c);
                count++;
                x++;
            }

            int px = startX * cellW;
            if (bgRun != palette[0]) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(bgRun);
                canvas.drawRect(px, y, px + (x - startX) * cellW, y + cellH, paint);
            }
            if (!hiddenRun && count > 0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(fgRun);
                paint.setFakeBoldText(boldRun);
                paint.setUnderlineText(underlineRun);
                canvas.drawText(runBuf, 0, count, px, y + baseline, paint);
                paint.setFakeBoldText(false);
                paint.setUnderlineText(false);
            }
        }
    }

    private void ensureRunBuf(int size) {
        int next = runBuf.length;
        while (next < size) {
            next *= 2;
        }
        runBuf = new char[next];
    }

    private void drawCursor(Canvas canvas, int colCount) {
        int row = screen.getCursorY();
        int col = screen.getCursorX();
        if (row < 0 || row >= screen.getRows() || col < 0 || col >= colCount) {
            return;
        }
        int i = row * colCount + col;
        char ch = screen.rawText()[i];
        byte flags = screen.rawFlags()[i];
        int coreFg = fgColor(screen.rawFg()[i], flags);
        int coreBg = bgColor(screen.rawBg()[i]);
        if ((flags & TerminalScreen.FLAG_REVERSE) != 0) {
            int tmp = coreFg;
            coreFg = coreBg;
            coreBg = tmp;
        }
        // курсор — инвертированная ячейка
        int x = col * cellW;
        int y = row * cellH;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(coreFg == palette[0] ? palette[7] : coreFg);
        canvas.drawRect(x, y, x + cellW, y + cellH, paint);
        paint.setColor(coreBg);
        paint.setFakeBoldText(false);
        paint.setUnderlineText(false);
        canvas.drawText(String.valueOf(translate(ch == 0 ? ' ' : ch)), x, y + baseline, paint);
    }

    private char translate(char c) {
        if (asciiBox && c > 0 && c < boxAscii.length && boxAscii[c] != 0) {
            return boxAscii[c];
        }
        return c;
    }

    private int fgColor(byte colorIndex, byte flags) {
        int idx = colorIndex & 0x0f;
        if (brightBold && (flags & TerminalScreen.FLAG_BOLD) != 0 && idx < 8) {
            idx += 8;
        }
        return palette[idx];
    }

    private int bgColor(byte colorIndex) {
        return palette[colorIndex & 0x0f];
    }

    // ------------------------------------------------------------------- ввод

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchY = event.getY();
                touchDownTime = System.currentTimeMillis();
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = event.getY() - lastTouchY;
                if (Math.abs(dy) >= cellH) {
                    dragging = true;
                    int lines = (int) (dy / cellH);
                    if (lines == 0) {
                        lines = dy > 0 ? 1 : -1;
                    }
                    scrollOffset -= lines;
                    if (scrollOffset < 0) {
                        scrollOffset = 0;
                    }
                    lastTouchY = event.getY();
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                long held = System.currentTimeMillis() - touchDownTime;
                if (!dragging && held >= 600) {
                    if (listener != null) {
                        listener.onLongPress();
                    }
                } else if (!dragging && isScrolledBack()) {
                    scrollToBottom();
                }
                dragging = false;
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }
}
