package com.example.helloworld;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Прогон логики telnet-клиента на обычной JVM:
 *  - эмулятор VT100/ANSI (TerminalScreen + AnsiParser);
 *  - разбор конфига, escape-последовательности, раскладка клавиш;
 *  - сквозной обмен с локальным «сервером»: приглашения -> пароль из файла -> экран.
 */
public class Harness {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        testTextAndCursor();
        testColors();
        testErase();
        testScrollback();
        testAlternateScreen();
        testTabsAndBackspace();
        testWrapAndBoundary();
        testCrOnLf();
        testRepliesAndCursorVisibility();
        testCharSets();
        testUnescape();
        testKeyMapping();
        testLayoutAndViews(args);
        testEndToEnd();

        System.out.println();
        System.out.println("ИТОГ: успешно " + passed + ", провалено " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name + "   -> " + detail);
        }
    }

    private static String row(TerminalScreen s, int r) {
        char[] text = s.rawText();
        int cols = s.getCols();
        return new String(text, r * cols, cols);
    }

    private static char at(TerminalScreen s, int r, int c) {
        return s.rawText()[r * s.getCols() + c];
    }

    // ------------------------------------------------------------------- тесты

    private static void testTextAndCursor() {
        System.out.println("Текст, перевод строки, курсор");
        TerminalScreen s = new TerminalScreen(20, 6, 50);
        AnsiParser p = new AnsiParser(s, null);
        p.feed("AB\r\nCD");
        check("строка 0 = AB", row(s, 0).startsWith("AB"), row(s, 0));
        check("строка 1 = CD", row(s, 1).startsWith("CD"), row(s, 1));
        check("курсор 2,1", s.getCursorX() == 2 && s.getCursorY() == 1, s.getCursorX() + "," + s.getCursorY());

        p.feed("\u001b[3;5HX");
        check("позиционирование CUP", at(s, 2, 4) == 'X', "символ=" + at(s, 2, 4));
        check("курсор после CUP", s.getCursorX() == 5 && s.getCursorY() == 2, s.getCursorX() + "," + s.getCursorY());
    }

    private static void testColors() {
        System.out.println("Цвета ANSI");
        TerminalScreen s = new TerminalScreen(20, 4, 10);
        AnsiParser p = new AnsiParser(s, null);
        p.feed("\u001b[31;1mR\u001b[0mN");
        byte[] fg = s.rawFg();
        byte[] flags = s.rawFlags();
        check("красный цвет (31)", (fg[0] & 0x0f) == 1, "fg=" + (fg[0] & 0x0f));
        check("жирный (1)", (flags[0] & TerminalScreen.FLAG_BOLD) != 0, "flags=" + flags[0]);
        check("сброс атрибутов (0)", (fg[1] & 0x0f) == 7 && flags[1] == 0, "fg=" + (fg[1] & 0x0f) + " flags=" + flags[1]);
        p.feed("\u001b[7mR");
        check("инверсия (7)", (s.rawFlags()[2] & TerminalScreen.FLAG_REVERSE) != 0, "flags=" + s.rawFlags()[2]);
        p.feed("\u001b[27m\u001b[44mB");
        check("фон (44) и снятие инверсии", (s.rawBg()[3] & 0x0f) == 4 && (s.rawFlags()[3] & TerminalScreen.FLAG_REVERSE) == 0,
                "bg=" + (s.rawBg()[3] & 0x0f) + " flags=" + s.rawFlags()[3]);
    }

    private static void testErase() {
        System.out.println("Очистка строки и экрана");
        TerminalScreen s = new TerminalScreen(10, 3, 10);
        AnsiParser p = new AnsiParser(s, null);
        p.feed("ABC\u001b[K");
        check("очистка до конца строки", row(s, 0).equals("ABC       "), "[" + row(s, 0) + "]");
        p.feed("\u001b[2J");
        check("полная очистка экрана", row(s, 0).trim().length() == 0 && row(s, 2).trim().length() == 0,
                "[" + row(s, 0) + "]");
        p.feed("\u001b[3;1HAAAA");
        p.feed("\u001b[3;3H\u001b[1K");
        check("очистка до курсора", row(s, 2).equals("   A" + "      "), "[" + row(s, 2) + "]");
    }

    private static void testScrollback() {
        System.out.println("История (scrollback)");
        TerminalScreen s = new TerminalScreen(20, 6, 100);
        AnsiParser p = new AnsiParser(s, null);
        for (int i = 0; i < 10; i++) {
            p.feed("L" + i + "\r\n");
        }
        check("в истории 5 строк", s.getScrollbackCount() == 5, "count=" + s.getScrollbackCount());
        TerminalScreen.LineRef ref = new TerminalScreen.LineRef();
        s.getLine(0, ref);
        String first = new String(ref.text, ref.offset, ref.length).trim();
        s.getLine(s.getScrollbackCount(), ref);
        String live = new String(ref.text, ref.offset, ref.length).trim();
        check("первая строка истории = L0", first.equals("L0"), "[" + first + "]");
        check("верх экрана = L5", live.equals("L5"), "[" + live + "]");
        s.getLine(s.totalLines() - 2, ref);
        String last = new String(ref.text, ref.offset, ref.length).trim();
        check("низ данных = L9", last.equals("L9"), "[" + last + "]");
        check("последняя строка пуста после CRLF", row(s, 5).trim().length() == 0, "[" + row(s, 5) + "]");
    }

    private static void testAlternateScreen() {
        System.out.println("Альтернативный экран (меню на весь экран)");
        TerminalScreen s = new TerminalScreen(20, 4, 10);
        AnsiParser p = new AnsiParser(s, null);
        p.feed("MAIN\r\n");
        p.feed("\u001b[?1049h");
        check("переключение в alt", s.isAlternateScreen(), "alt=" + s.isAlternateScreen());
        check("alt чистый", row(s, 0).trim().length() == 0, "[" + row(s, 0) + "]");
        p.feed("MENU\u001b[2;2H>");
        check("alt содержит меню", row(s, 0).startsWith("MENU"), "[" + row(s, 0) + "]");
        p.feed("\u001b[?1049l");
        check("возврат в основной экран", !s.isAlternateScreen() && row(s, 0).startsWith("MAIN"), "[" + row(s, 0) + "]");
    }

    private static void testTabsAndBackspace() {
        System.out.println("Табуляция и backspace");
        TerminalScreen s = new TerminalScreen(20, 3, 10);
        AnsiParser p = new AnsiParser(s, null);
        p.feed("\tX");
        check("табуляция в колонку 8", at(s, 0, 8) == 'X', "символ=" + at(s, 0, 8));
        p.feed("\u001b[2;1HABC\b\bZ");
        check("backspace", row(s, 1).startsWith("AZC"), "[" + row(s, 1) + "]");
    }

    private static void testWrapAndBoundary() {
        System.out.println("Перенос строки и последняя колонка");
        TerminalScreen s = new TerminalScreen(20, 4, 10);
        AnsiParser p = new AnsiParser(s, null);
        p.feed("01234567890123456789W");
        check("перенос на 21-м символе", at(s, 1, 0) == 'W', "символ=" + at(s, 1, 0));
        TerminalScreen s2 = new TerminalScreen(80, 3, 10);
        AnsiParser p2 = new AnsiParser(s2, null);
        p2.feed("\u001b[1;80H#");
        check("символ в 80-й колонке", at(s2, 0, 79) == '#', "символ=" + at(s2, 0, 79));
        p2.feed("\u001b[1;2r");
        check("область прокрутки задана", s2.getScrollTop() == 0 && s2.getScrollBottom() == 1,
                s2.getScrollTop() + ".." + s2.getScrollBottom());
        check("размер экрана 80x3", s2.getCols() == 80 && s2.getRows() == 3, s2.getCols() + "x" + s2.getRows());
    }

    private static void testCrOnLf() {
        System.out.println("Перевод строки без CR (меню на Linux)");
        TerminalScreen s = new TerminalScreen(20, 4, 10);
        AnsiParser p = new AnsiParser(s, null);
        p.feed("AB\nC");
        check("LF без CR вернул каретку", row(s, 1).startsWith("C"), "[" + row(s, 1) + "]");
        TerminalScreen s2 = new TerminalScreen(20, 4, 10);
        AnsiParser p2 = new AnsiParser(s2, null);
        p2.setCrOnLf(false);
        p2.feed("AB\nC");
        check("режим без автоперевода (лестница)", row(s2, 1).indexOf('C') == 2, "[" + row(s2, 1) + "]");
    }

    private static void testRepliesAndCursorVisibility() {
        System.out.println("Ответы на запросы сервера и видимость курсора");
        TerminalScreen s = new TerminalScreen(20, 4, 10);
        final List<String> replies = new ArrayList<String>();
        AnsiParser p = new AnsiParser(s, new AnsiParser.Reply() {
            public void onReply(String text) {
                replies.add(text);
            }
        });
        p.feed("\u001b[2;3H\u001b[6n");
        check("ответ на запрос позиции (DSR 6)", replies.size() == 1 && replies.get(0).equals("\u001b[2;3R"),
                replies.isEmpty() ? "нет ответа" : replies.get(0));
        p.feed("\u001b[c");
        check("ответ на запрос типа терминала (DA)", replies.size() == 2 && replies.get(1).equals("\u001b[?6c"),
                replies.size() < 2 ? "нет ответа" : replies.get(1));
        p.feed("\u001b[?25l");
        check("курсор скрыт (?25l)", !s.isCursorVisible(), "visible=" + s.isCursorVisible());
        p.feed("\u001b[?25h");
        check("курсор показан (?25h)", s.isCursorVisible(), "visible=" + s.isCursorVisible());
    }

    private static void testCharSets() {
        System.out.println("Кодировки");
        byte[] cp866 = new byte[] { (byte) 0x8f, (byte) 0xe0, (byte) 0xae, (byte) 0xa2, (byte) 0xa5, (byte) 0xe0, (byte) 0xaa, (byte) 0xa0 };
        check("cp866", CharsetKit.decode(cp866, CharsetKit.normalize("cp866")).equals("Проверка"),
                CharsetKit.decode(cp866, "Cp866"));
        byte[] win = new byte[] { (byte) 0xcf, (byte) 0xf0, (byte) 0xee };
        check("windows-1251", CharsetKit.decode(win, CharsetKit.normalize("1251")).equals("Про"),
                CharsetKit.decode(win, "windows-1251"));
        check("нормализация имён", CharsetKit.normalize("cp866").equals("Cp866")
                && CharsetKit.normalize("koi8-r").equals("KOI8-R")
                && CharsetKit.normalize("utf8").equals("UTF-8"), CharsetKit.normalize("utf8"));
    }

    private static void testUnescape() {
        System.out.println("Разбор escape-последовательностей в конфиге");
        check("\\eOP", TelnetConfig.unescape("\\eOP").equals("\u001bOP"), TelnetConfig.unescape("\\eOP"));
        check("\\x1b", TelnetConfig.unescape("\\x1b").equals("\u001b"), TelnetConfig.unescape("\\x1b"));
        check("\\033 (восьмеричное)", TelnetConfig.unescape("\\033").equals("\u001b"),
                TelnetConfig.unescape("\\033").length() + " симв.");
        check("\\0", TelnetConfig.unescape("\\0").equals("\u0000"), "");
        check("слово CR", TelnetConfig.unescape("CR").equals("\r"), "");
        check("слово CRLF", TelnetConfig.unescape("CRLF").equals("\r\n"), "");
        check("обычный текст не портится", TelnetConfig.unescape("kladovshik").equals("kladovshik"), "");
        check("обратный слэш", TelnetConfig.unescape("a\\\\b").equals("a\\b"), TelnetConfig.unescape("a\\\\b"));
    }

    private static void testKeyMapping() {
        System.out.println("Раскладка клавиш");
        TelnetConfig cfg = new TelnetConfig();
        cfg.enterSequence = "\r";
        cfg.keyDisabled.add(Integer.valueOf(android.view.KeyEvent.KEYCODE_VOLUME_UP));
        cfg.keyOverrides.put(Integer.valueOf(android.view.KeyEvent.KEYCODE_F1), new byte[] { 0x1b, 'O', 'P' });

        byte[] enter = KeyMapper.map(new android.view.KeyEvent(android.view.KeyEvent.KEYCODE_ENTER, 10, 0, 0), cfg);
        check("Enter -> CR", enter != null && enter.length == 1 && enter[0] == 0x0d, String.valueOf(enter == null ? -1 : enter[0]));
        byte[] f1 = KeyMapper.map(new android.view.KeyEvent(android.view.KeyEvent.KEYCODE_F1, 0, 0, 0), cfg);
        check("F1 -> подмена из конфига", f1 != null && f1[0] == 0x1b && f1[1] == 'O' && f1[2] == 'P', "len=" + (f1 == null ? -1 : f1.length));
        byte[] vol = KeyMapper.map(new android.view.KeyEvent(android.view.KeyEvent.KEYCODE_VOLUME_UP, 0, 0, 0), cfg);
        check("VOLUME_UP заглушена", vol == null, "не null");
        byte[] up = KeyMapper.map(new android.view.KeyEvent(android.view.KeyEvent.KEYCODE_DPAD_UP, 0, 0, 0), cfg);
        check("стрелка вверх -> ESC [ A", up != null && up[1] == '[' && up[2] == 'A', "");
        byte[] f5 = KeyMapper.map(new android.view.KeyEvent(android.view.KeyEvent.KEYCODE_F5, 0, 0, 0), cfg);
        check("F5 -> ESC [ 15 ~", f5 != null && f5.length == 5 && f5[4] == '~', "len=" + (f5 == null ? -1 : f5.length));
        byte[] digit = KeyMapper.map(new android.view.KeyEvent(29, '7', 0, 0), cfg);
        check("цифра штрихкода уходит как есть", digit != null && digit[0] == '7', "");
        // на части ТСД карта клавиш не отдаёт буквы (unicode = 0) — символ берётся из кода
        byte[] letter = KeyMapper.map(new android.view.KeyEvent(android.view.KeyEvent.KEYCODE_A, 0, 0, 0), cfg);
        check("буква при unicode=0 -> 'a'", letter != null && letter[0] == 'a', "len=" + (letter == null ? -1 : letter.length));
        byte[] upper = KeyMapper.map(new android.view.KeyEvent(android.view.KeyEvent.KEYCODE_Z, 0, 1, 0), cfg);
        check("Shift+Z при unicode=0 -> 'Z'", upper != null && upper[0] == 'Z', "");
        byte[] zero = KeyMapper.map(new android.view.KeyEvent(android.view.KeyEvent.KEYCODE_0, 0, 0, 0), cfg);
        check("цифра 0 при unicode=0 -> '0'", zero != null && zero[0] == '0', "");
        byte[] gap = KeyMapper.map(new android.view.KeyEvent(android.view.KeyEvent.KEYCODE_SPACE, 0, 0, 0), cfg);
        check("пробел при unicode=0 -> ' '", gap != null && gap[0] == ' ', "");
        byte[] menu = KeyMapper.map(new android.view.KeyEvent(android.view.KeyEvent.KEYCODE_MENU, 0, 0, 0), cfg);
        check("MENU не уходит на сервер", menu == null, "не null");
        check("имя клавиши VOLUME_DOWN распознано", KeyMapper.keyCodeByName("VOLUME_DOWN") != null, "");
        check("имя KEYCODE_F1 распознано", Integer.valueOf(android.view.KeyEvent.KEYCODE_F1).equals(KeyMapper.keyCodeByName("KEYCODE_F1")), "");
        check("числовой код клавиши", Integer.valueOf(131).equals(KeyMapper.keyCodeByName("131")), "");
    }

    // ------------------------------------------------- сквозной тест с сервером

    private static boolean hasSequence(List<Integer> bytes, int... pattern) {
        synchronized (bytes) {
            outer:
            for (int i = 0; i + pattern.length <= bytes.size(); i++) {
                for (int j = 0; j < pattern.length; j++) {
                    if (bytes.get(i + j).intValue() != pattern[j]) {
                        continue outer;
                    }
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Проверка того, что приложение не падает при запуске: собственные View из разметки
     * обязаны иметь конструктор (Context, AttributeSet), а findViewById — ссылаться
     * на реально объявленные id.
     */
    private static void testLayoutAndViews(String[] args) {
        System.out.println("Разметка и виджеты (защита от падения при запуске)");
        File root = findProjectRoot(args);
        if (root == null) {
            check("найден корень проекта", false, "запустить из корня проекта или передать путь аргументом");
            return;
        }
        File javaDir = new File(root, "app/src/main/java/com/example/helloworld");
        File layoutFile = new File(root, "app/src/main/res/layout/activity_main.xml");
        String layout = read(layoutFile);
        check("разметка activity_main.xml читается", layout != null, layoutFile.getPath());

        if (layout != null) {
            List<String> custom = new ArrayList<String>();
            Matcher m = Pattern.compile("<\\s*([A-Za-z][A-Za-z0-9_.]*)").matcher(layout);
            while (m.find()) {
                String tag = m.group(1);
                if (tag.indexOf('.') > 0 && !tag.startsWith("android.")) {
                    custom.add(tag);
                }
            }
            check("в разметке есть собственные View", custom.size() > 0, "найдено: " + custom.size());
            for (int i = 0; i < custom.size(); i++) {
                String tag = custom.get(i);
                String simple = tag.substring(tag.lastIndexOf('.') + 1);
                File src = new File(javaDir, simple + ".java");
                String text = read(src);
                boolean hasClass = text != null;
                boolean hasCtor = hasClass && Pattern.compile("\\b" + simple
                        + "\\s*\\(\\s*Context\\s+\\w+\\s*,\\s*AttributeSet\\b").matcher(text).find();
                check(simple + ": класс найден", hasClass, src.getPath());
                check(simple + ": есть конструктор (Context, AttributeSet)",
                        hasCtor, "иначе setContentView падает с InflateException");
            }
        }

        String activity = read(new File(javaDir, "MainActivity.java"));
        check("MainActivity.java читается", activity != null, "файл не найден");
        if (activity != null && layout != null) {
            Set<String> used = new LinkedHashSet<String>();
            Matcher m = Pattern.compile("R\\.id\\.([A-Za-z0-9_]+)").matcher(activity);
            while (m.find()) {
                used.add(m.group(1));
            }
            check("MainActivity использует R.id", used.size() > 0, "ссылок не найдено");
            for (String id : used) {
                check("id " + id + " объявлен в разметке",
                        layout.contains("@+id/" + id), "ожидался @+id/" + id);
            }
        }
    }

    private static File findProjectRoot(String[] args) {
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                File candidate = new File(args[i]);
                if (new File(candidate, "app/src/main/java/com/example/helloworld").isDirectory()) {
                    return candidate;
                }
            }
        }
        File dir = new File(System.getProperty("user.dir", "."));
        for (int i = 0; i < 6 && dir != null; i++) {
            if (new File(dir, "app/src/main/java/com/example/helloworld").isDirectory()) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    private static String read(File file) {
        try {
            byte[] data = new byte[(int) file.length()];
            InputStream in = new FileInputStream(file);
            try {
                int offset = 0;
                int n;
                while (offset < data.length && (n = in.read(data, offset, data.length - offset)) > 0) {
                    offset += n;
                }
            } finally {
                in.close();
            }
            return new String(data, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static void testEndToEnd() throws Exception {
        System.out.println("Сквозной обмен: конфиг -> сокет -> telnet -> экран");

        final ServerSocket server = new ServerSocket(0);
        final int port = server.getLocalPort();
        final List<String> received = Collections.synchronizedList(new ArrayList<String>());
        final List<Integer> clientBytes = Collections.synchronizedList(new ArrayList<Integer>());

        Thread serverThread = new Thread(new Runnable() {
            public void run() {
                try {
                    final Socket socket = server.accept();
                    socket.setTcpNoDelay(true);
                    final InputStream in = socket.getInputStream();
                    OutputStream out = socket.getOutputStream();

                    Thread reader = new Thread(new Runnable() {
                        public void run() {
                            StringBuilder line = new StringBuilder();
                            try {
                                byte[] buf = new byte[256];
                                int n;
                                while ((n = in.read(buf)) > 0) {
                                    for (int i = 0; i < n; i++) {
                                        int b = buf[i] & 0xff;
                                        clientBytes.add(Integer.valueOf(b));
                                        if (b == 0x0d || b == 0x0a) {
                                            if (line.length() > 0) {
                                                received.add(line.toString());
                                                line.setLength(0);
                                            }
                                        } else if (b >= 0x20) {
                                            line.append((char) b);
                                        }
                                    }
                                }
                            } catch (Exception ignored) {
                                // сокет закрыт — нормальное завершение теста
                            }
                        }
                    }, "test-reader");
                    reader.setDaemon(true);
                    reader.start();

                    String charset = "Cp866";
                    out.write(("Складской терминал\r\n\u001b[1;33mЛогин: \u001b[0m").getBytes(charset));
                    out.flush();
                    Thread.sleep(400);
                    out.write("Пароль: ".getBytes(charset));
                    out.flush();
                    Thread.sleep(400);

                    // переговоры: просим эхо, объявляем эхо, включаем NAWS, спрашиваем тип терминала
                    out.write(new byte[] { (byte) 255, (byte) 253, (byte) 1 });          // IAC DO ECHO
                    out.write(new byte[] { (byte) 255, (byte) 251, (byte) 1 });          // IAC WILL ECHO
                    out.write(new byte[] { (byte) 255, (byte) 253, (byte) 31 });         // IAC DO NAWS
                    out.write(new byte[] { (byte) 255, (byte) 250, (byte) 24, (byte) 1, (byte) 255, (byte) 240 }); // SB TTYPE SEND
                    out.flush();
                    Thread.sleep(400);

                    out.write(("\u001b[2J\u001b[H\u001b[7m=== Склад ===\u001b[0m\r\n"
                            + "1. Приёмка\r\n2. Отгрузка\r\n\u001b[5;3H> ").getBytes(charset));
                    out.flush();
                    Thread.sleep(900);
                    socket.close();
                } catch (Exception e) {
                    received.add("ошибка сервера: " + e);
                }
            }
        }, "test-server");
        serverThread.setDaemon(true);
        serverThread.start();

        File cfgFile = File.createTempFile("telnet", ".ini");
        FileOutputStream cfgOut = new FileOutputStream(cfgFile);
        String cfgText = "[server]\n"
                + "host=127.0.0.1\n"
                + "port=" + port + "\n"
                + "charset_recv=cp866\n"
                + "charset_send=cp866\n"
                + "auto_connect=1\n"
                + "lf_as_crlf=1\n"
                + "enter=CR\n"
                + "\n"
                + "[auth]\n"
                + "login=kladovshik\n"
                + "password=sekret\n"
                + "prompt_login=Логин:\n"
                + "prompt_password=Пароль:\n"
                + "delay_ms=50\n"
                + "\n"
                + "[keys]\n"
                + "F1=\\eOP\n"
                + "VOLUME_UP=DISABLE\n"
                + "\n"
                + "[scanner]\n"
                + "intent_action=android.intent.action.SCANRESULT\n"
                + "intent_extra=value\n"
                + "intent_auto=0\n"
                + "\n"
                + "[screen]\n"
                + "auto_size=0\n"
                + "cols=40\n"
                + "rows=10\n"
                + "\n"
                + "[log]\n"
                + "enabled=0\n";
        cfgOut.write(cfgText.getBytes("UTF-8"));
        cfgOut.close();

        final TelnetConfig cfg = TelnetConfig.load(cfgFile.getAbsolutePath());
        check("конфиг прочитан без ошибок", cfg.error == null, String.valueOf(cfg.error));
        check("пары аутентификации из файла", cfg.authPairs.size() == 2, "size=" + cfg.authPairs.size());
        check("сервер и кодировка из файла", cfg.host.equals("127.0.0.1") && cfg.port == port && cfg.charsetRecv.equals("Cp866"),
                cfg.host + ":" + cfg.port + " " + cfg.charsetRecv);
        check("подмена клавиши из файла", cfg.keyOverrides.size() == 1, "override=" + cfg.keyOverrides.size());
        check("блокировка клавиши из файла", cfg.keyDisabled.contains(Integer.valueOf(24)), "disabled=" + cfg.keyDisabled);
        check("action сканера из файла", "android.intent.action.SCANRESULT".equals(cfg.scannerIntentAction), cfg.scannerIntentAction);
        check("intent_auto=0 прочитан", !cfg.scannerIntentAuto, "auto=" + cfg.scannerIntentAuto);
        check("имя extra сканера — value", "value".equals(cfg.scannerIntentExtra), cfg.scannerIntentExtra);

        final TerminalScreen screen = new TerminalScreen(cfg.cols, cfg.rows, cfg.scrollback);
        final AnsiParser parser = new AnsiParser(screen, null);
        parser.setCrOnLf(cfg.lfAsCrLf);
        final int[] connected = new int[1];
        final List<String> errors = new ArrayList<String>();

        TelnetClient client = new TelnetClient(cfg, new TelnetClient.Listener() {
            public void onState(int state, String message) {
                if (state == TelnetClient.STATE_CONNECTED) {
                    connected[0]++;
                }
                if (state == TelnetClient.STATE_ERROR) {
                    errors.add(message);
                }
            }

            public void onText(String text) {
                synchronized (screen) {
                    parser.feed(text);
                }
            }

            public void onAuthSent(String prompt, String answer) {
            }
        });
        client.setWindowSize(cfg.cols, cfg.rows);
        client.connect();

        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            if (received.size() >= 2 && row(screen, 0).contains("Склад")) {
                break;
            }
            Thread.sleep(100);
        }
        Thread.sleep(900);

        check("состояние «подключено» получено", connected[0] >= 1, "раз=" + connected[0]);
        check("ошибок соединения нет", errors.isEmpty(), errors.toString());
        check("логин отправлен из файла", received.size() >= 1 && received.get(0).equals("kladovshik"), received.toString());
        check("пароль отправлен из файла", received.size() >= 2 && received.get(1).equals("sekret"), received.toString());
        check("экран принял меню склада", row(screen, 0).contains("Склад"), "[" + row(screen, 0) + "]");
        check("инверсия на заголовке", (screen.rawFlags()[0] & TerminalScreen.FLAG_REVERSE) != 0, "flags=" + screen.rawFlags()[0]);
        check("пункт меню на месте", row(screen, 1).contains("Приёмка"), "[" + row(screen, 1) + "]");
        check("приглашение '>' в колонке 3, строка 5", at(screen, 4, 2) == '>', "символ=" + at(screen, 4, 2));
        check("IAC-команды не попали в текст", row(screen, 0).indexOf('\u00ff') < 0, "есть байт 0xFF");

        check("ответ WONT ECHO на DO ECHO", hasSequence(clientBytes, 255, 252, 1), "");
        check("ответ DO ECHO на WILL ECHO", hasSequence(clientBytes, 255, 253, 1), "");
        check("ответ WILL NAWS на DO NAWS", hasSequence(clientBytes, 255, 251, 31), "");
        check("размер экрана отправлен (NAWS 40x10)", hasSequence(clientBytes, 255, 250, 31, 0, 40, 0, 10, 255, 240), "");
        check("тип терминала отправлен (TTYPE IS VT100)",
                hasSequence(clientBytes, 255, 250, 24, 0, 86, 84, 49, 48, 48, 255, 240), "");

        StringBuilder hex = new StringBuilder();
        synchronized (clientBytes) {
            for (Integer b : clientBytes) {
                hex.append(String.format("%02X ", b));
            }
        }
        System.out.println("     строки от клиента: " + received);
        System.out.println("     байты от клиента:  " + hex.toString().trim());

        client.disconnect();
        server.close();
        cfgFile.delete();
    }
}
