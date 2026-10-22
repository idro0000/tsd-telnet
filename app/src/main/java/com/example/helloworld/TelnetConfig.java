package com.example.helloworld;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Конфигурация из текстового файла на карте памяти (по умолчанию /sdcard/telnet.ini).
 *
 * Файл читается заново перед каждым подключением, поэтому пароли и раскладку клавиш
 * можно менять прямо на ТСД, без пересборки APK.
 *
 * Формат — секции и пары ключ=значение:
 *
 *   [server]
 *   host=192.168.0.10
 *   port=23
 *   ...
 */
public class TelnetConfig {

    /** Пара «приглашение сервера → ответ». */
    public static final class AuthPair {
        public final String prompt;
        public final String answer;

        AuthPair(String prompt, String answer) {
            this.prompt = prompt;
            this.answer = answer;
        }
    }

    public String path = "/sdcard/telnet.ini";
    /** Текст ошибки разбора, null если всё в порядке. */
    public String error;

    // ---- [server]
    public String host = "192.168.0.1";
    public int port = 23;
    public int timeoutSec = 10;
    public boolean autoConnect = true;
    public boolean autoReconnect = true;
    public int reconnectDelaySec = 5;
    public boolean keepAlive = true;
    public boolean noDelay = true;
    public String charsetRecv = "Cp866";
    public String charsetSend = "Cp866";
    public String terminalType = "VT100";
    /** LF без CR превращать в перевод строки с возвратом каретки. */
    public boolean lfAsCrLf = true;

    // ---- [auth]
    public boolean authEnabled = true;
    public boolean authOnConnect = false;
    public int authDelayMs = 300;
    public final List<AuthPair> authPairs = new ArrayList<AuthPair>();

    // ---- [keys]
    public final Map<Integer, byte[]> keyOverrides = new HashMap<Integer, byte[]>();
    public final Set<Integer> keyDisabled = new HashSet<Integer>();
    public String enterSequence = "\r";

    // ---- [scanner]
    public boolean scannerKeyboard = true;
    public boolean scannerIntent = false;
    /** Подхватывать рассылки сканера автоматически, даже если intent=0. */
    public boolean scannerIntentAuto = true;
    public String scannerIntentAction = "";
    /** Имя extra со штрихкодом. У iData это value. */
    public String scannerIntentExtra = "value";
    public String scannerSuffix = "\r";

    // ---- [screen]
    public int fontSize = 12;
    public int maxFontSize = 20;
    public boolean autoSize = true;
    public int cols = 80;
    public int rows = 24;
    /** В авто-режиме: сетка, которая обязана влезть. Меньше колонок — крупнее шрифт. */
    public int minCols = 40;
    public int minRows = 12;
    public int scrollback = 500;
    public boolean lightTheme = false;
    public boolean asciiBox = false;
    public boolean brightBold = true;
    /** Свой моношрифт с кириллицей и рамками; 0 — системный моношрифт ТСД. */
    public boolean builtinFont = true;

    // ---- [log]
    public boolean logEnabled = false;
    public boolean logData = false;
    public String logPath = "/sdcard/telnet.log";

    public static TelnetConfig load(String filePath) {
        TelnetConfig cfg = new TelnetConfig();
        cfg.path = filePath;
        File file = new File(filePath);
        if (!file.exists()) {
            cfg.error = "файл " + filePath + " не найден";
            return cfg;
        }
        try {
            cfg.parse(file);
        } catch (Exception e) {
            cfg.error = "ошибка чтения " + filePath + ": " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage());
        }
        return cfg;
    }

    private void parse(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"), 8192);
        String section = "";
        int authCounter = 0;
        List<String> problems = new ArrayList<String>();
        Map<Integer, String> authPrompts = new HashMap<Integer, String>();
        Map<Integer, String> authAnswers = new HashMap<Integer, String>();
        String login = null;
        String password = null;
        String promptLogin = "login:";
        String promptPassword = "Password:";
        int lineNo = 0;
        try {
            String raw;
            while ((raw = reader.readLine()) != null) {
                lineNo++;
                String line = raw;
                if (line.length() > 0 && line.charAt(0) == 0xfeff) {
                    line = line.substring(1); // BOM от блокнота
                }
                String trimmed = line.trim();
                if (trimmed.length() == 0 || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                    continue;
                }
                if (trimmed.startsWith("[")) {
                    int end = trimmed.indexOf(']');
                    section = (end > 0 ? trimmed.substring(1, end) : trimmed.substring(1)).trim().toLowerCase();
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    problems.add("строка " + lineNo + " без '='");
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1);
                String lkey = key.toLowerCase();

                if (section.equals("server")) {
                    applyServer(lkey, value);
                } else if (section.equals("auth")) {
                    if (lkey.equals("login")) {
                        login = value.trim();
                    } else if (lkey.equals("password")) {
                        password = value;
                    } else if (lkey.equals("prompt_login")) {
                        promptLogin = value;
                    } else if (lkey.equals("prompt_password")) {
                        promptPassword = value;
                    } else if (lkey.startsWith("prompt")) {
                        Integer n = number(lkey.substring(6));
                        if (n != null) {
                            authPrompts.put(n, value);
                        } else {
                            problems.add("строка " + lineNo + ": ключ " + key);
                        }
                    } else if (lkey.startsWith("answer")) {
                        Integer n = number(lkey.substring(6));
                        if (n != null) {
                            authAnswers.put(n, value);
                        } else {
                            problems.add("строка " + lineNo + ": ключ " + key);
                        }
                    }
                } else if (section.equals("keys") || section.equals("skeys")) {
                    applyKey(key, value, problems, lineNo);
                } else if (section.equals("scanner")) {
                    if (lkey.equals("keyboard")) {
                        scannerKeyboard = yes(value);
                    } else if (lkey.equals("intent")) {
                        scannerIntent = yes(value);
                    } else if (lkey.equals("intent_auto")) {
                        scannerIntentAuto = yes(value);
                    } else if (lkey.equals("intent_action")) {
                        scannerIntentAction = value.trim();
                    } else if (lkey.equals("intent_extra")) {
                        scannerIntentExtra = value.trim();
                    } else if (lkey.equals("suffix")) {
                        scannerSuffix = unescape(value);
                    }
                } else if (section.equals("screen")) {
                    applyScreen(lkey, value);
                } else if (section.equals("log")) {
                    if (lkey.equals("enabled")) {
                        logEnabled = yes(value);
                    } else if (lkey.equals("data")) {
                        logData = yes(value);
                    } else if (lkey.equals("file")) {
                        logPath = value.trim();
                    }
                } else {
                    problems.add("строка " + lineNo + ": неизвестная секция [" + section + "]");
                }
            }
        } finally {
            reader.close();
            in.close();
        }

        // пары: сначала пронумерованные, затем стандартная login/password
        Set<Integer> numbers = new HashSet<Integer>();
        numbers.addAll(authPrompts.keySet());
        numbers.addAll(authAnswers.keySet());
        List<Integer> sorted = new ArrayList<Integer>(numbers);
        java.util.Collections.sort(sorted);
        for (Integer n : sorted) {
            String p = authPrompts.get(n);
            String a = authAnswers.get(n);
            if (p == null || a == null) {
                problems.add("неполная пара аутентификации " + n + " (нужны prompt" + n + " и answer" + n + ")");
                continue;
            }
            authPairs.add(new AuthPair(p, unescape(a)));
            authCounter++;
        }
        if (login != null && login.length() > 0) {
            authPairs.add(new AuthPair(promptLogin, unescape(login)));
            if (password != null) {
                authPairs.add(new AuthPair(promptPassword, unescape(password)));
            }
            authCounter += 2;
        }

        charsetRecv = CharsetKit.normalize(charsetRecv);
        charsetSend = CharsetKit.normalize(charsetSend);
        if (authCounter == 0) {
            authEnabled = false;
        }
        if (problems.size() > 0) {
            StringBuilder sb = new StringBuilder("замечания к конфигу: ");
            for (int i = 0; i < problems.size(); i++) {
                if (i > 0) {
                    sb.append("; ");
                }
                sb.append(problems.get(i));
            }
            error = sb.toString();
        }
    }

    private void applyServer(String key, String value) {
        if (key.equals("host")) {
            host = value.trim();
        } else if (key.equals("port")) {
            port = (int) number2(value, port);
        } else if (key.equals("timeout")) {
            timeoutSec = (int) number2(value, timeoutSec);
        } else if (key.equals("auto_connect") || key.equals("autoconnect")) {
            autoConnect = yes(value);
        } else if (key.equals("auto_reconnect") || key.equals("autoreconnect")) {
            autoReconnect = yes(value);
        } else if (key.equals("reconnect_delay")) {
            reconnectDelaySec = (int) number2(value, reconnectDelaySec);
        } else if (key.equals("keepalive")) {
            keepAlive = yes(value);
        } else if (key.equals("nodelay")) {
            noDelay = yes(value);
        } else if (key.equals("charset_recv") || key.equals("charset")) {
            charsetRecv = value.trim();
        } else if (key.equals("charset_send")) {
            charsetSend = value.trim();
        } else if (key.equals("terminal_type")) {
            terminalType = value.trim();
        } else if (key.equals("lf_as_crlf") || key.equals("cr_on_lf")) {
            lfAsCrLf = yes(value);
        } else if (key.equals("enter")) {
            enterSequence = unescape(value);
        }
    }

    private void applyScreen(String key, String value) {
        if (key.equals("font_size")) {
            fontSize = (int) number2(value, fontSize);
        } else if (key.equals("max_font_size")) {
            maxFontSize = (int) number2(value, maxFontSize);
        } else if (key.equals("auto_size")) {
            autoSize = yes(value);
        } else if (key.equals("cols")) {
            cols = (int) number2(value, cols);
        } else if (key.equals("rows")) {
            rows = (int) number2(value, rows);
        } else if (key.equals("min_cols")) {
            minCols = (int) number2(value, minCols);
        } else if (key.equals("min_rows")) {
            minRows = (int) number2(value, minRows);
        } else if (key.equals("scrollback")) {
            scrollback = (int) number2(value, scrollback);
        } else if (key.equals("theme")) {
            String v = value.trim().toLowerCase();
            lightTheme = v.equals("light") || v.equals("white") || v.equals("1");
        } else if (key.equals("box_drawing")) {
            String v = value.trim().toLowerCase();
            asciiBox = v.equals("ascii") || v.equals("1") || v.equals("yes");
        } else if (key.equals("bright_bold")) {
            brightBold = yes(value);
        } else if (key.equals("builtin_font")) {
            builtinFont = yes(value);
        }
    }

    private void applyKey(String key, String value, List<String> problems, int lineNo) {
        String name = key.trim();
        if (name.length() == 0) {
            problems.add("строка " + lineNo + ": пустое имя клавиши");
            return;
        }
        if (name.equalsIgnoreCase("enter")) {
            enterSequence = unescape(value);
            return;
        }
        Integer code = KeyMapper.keyCodeByName(name);
        if (code == null) {
            problems.add("строка " + lineNo + ": неизвестная клавиша " + name);
            return;
        }
        String v = value.trim();
        if (v.equalsIgnoreCase("disable") || v.equalsIgnoreCase("ignore")
                || v.equalsIgnoreCase("none") || v.equalsIgnoreCase("off")
                || v.equalsIgnoreCase("no") || v.equals("---")) {
            keyOverrides.remove(code);
            keyDisabled.add(code);
            return;
        }
        keyDisabled.remove(code);
        keyOverrides.put(code, CharsetKit.encode(unescape(value), charsetSend));
    }

    // ------------------------------------------------------------- утилиты

    private static Integer number(String s) {
        try {
            return Integer.valueOf(Integer.parseInt(s.trim()));
        } catch (Exception e) {
            return null;
        }
    }

    private static long number2(String value, long def) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public static boolean yes(String value) {
        String v = value.trim().toLowerCase();
        return v.equals("1") || v.equals("yes") || v.equals("true") || v.equals("on") || v.equals("да");
    }

    /**
     * Разбор escape-последовательностей в значениях конфига:
     * \\e \\x1b \\033 \\r \\n \\t \\0 и слова CR/LF/CRLF/TAB/ESC/NUL.
     */
    public static String unescape(String value) {
        if (value == null) {
            return "";
        }
        String word = value.trim().toUpperCase();
        if (word.equals("CR")) {
            return "\r";
        }
        if (word.equals("LF")) {
            return "\n";
        }
        if (word.equals("CRLF")) {
            return "\r\n";
        }
        if (word.equals("TAB")) {
            return "\t";
        }
        if (word.equals("ESC")) {
            return "\u001b";
        }
        if (word.equals("NUL")) {
            return "\u0000";
        }
        if (word.equals("SP")) {
            return " ";
        }
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        int i = 0;
        int len = value.length();
        while (i < len) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= len) {
                sb.append(c);
                i++;
                continue;
            }
            char n = value.charAt(i + 1);
            i += 2;
            if (n == 'n' || n == 'N') {
                sb.append('\n');
            } else if (n == 'r' || n == 'R') {
                sb.append('\r');
            } else if (n == 't' || n == 'T') {
                sb.append('\t');
            } else if (n == 'e' || n == 'E') {
                sb.append('\u001b');
            } else if (n == 'a') {
                sb.append('\u0007');
            } else if (n == 'b') {
                sb.append('\b');
            } else if (n == 'f') {
                sb.append('\f');
            } else if (n == 'v') {
                sb.append('\u000b');
            } else if (n == 'x' || n == 'X') {
                int v = 0;
                int digits = 0;
                while (i < len && digits < 2 && isHex(value.charAt(i))) {
                    v = v * 16 + hex(value.charAt(i));
                    i++;
                    digits++;
                }
                sb.append((char) v);
            } else if (n == '\\') {
                sb.append('\\');
            } else if (n >= '0' && n <= '7') {
                int v = n - '0';
                int digits = 1;
                while (i < len && digits < 3 && value.charAt(i) >= '0' && value.charAt(i) <= '7') {
                    v = v * 8 + (value.charAt(i) - '0');
                    i++;
                    digits++;
                }
                sb.append((char) v);
            } else {
                sb.append(n);
            }
        }
        return sb.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        return c - 'A' + 10;
    }

    /** Короткое описание конфига для вывода на экран терминала. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("конфиг: ").append(path).append("\r\n");
        sb.append("сервер: ").append(host).append(':').append(port)
                .append("  таймаут ").append(timeoutSec).append(" с\r\n");
        sb.append("кодировки: приём ").append(charsetRecv).append(", отправка ").append(charsetSend).append("\r\n");
        sb.append("терминал: ").append(terminalType)
                .append(", экран ").append(cols).append('x').append(rows)
                .append(autoSize ? " (авто)" : " (по конфигу)").append("\r\n");
        sb.append("шрифт ").append(fontSize).append("..").append(maxFontSize)
                .append(", тема ").append(lightTheme ? "светлая" : "тёмная")
                .append(", псевдографика ").append(asciiBox ? "ascii" : "unicode").append("\r\n");
        sb.append("автоподключение: ").append(autoConnect ? "да" : "нет")
                .append(", переподключение: ").append(autoReconnect ? ("через " + reconnectDelaySec + " с") : "нет").append("\r\n");
        sb.append("аутентификация: ").append(authEnabled ? "да" : "нет").append(", пар: ").append(authPairs.size());
        for (int i = 0; i < authPairs.size(); i++) {
            AuthPair pair = authPairs.get(i);
            sb.append("\r\n  ").append(i + 1).append(") \"").append(pair.prompt).append("\" -> ").append(mask(pair.answer));
        }
        sb.append("\r\n");
        sb.append("подмены клавиш: ").append(keyOverrides.size())
                .append(", заблокировано: ").append(keyDisabled.size()).append("\r\n");
        sb.append("ввод сканера: клавиатура ").append(scannerKeyboard ? "вкл" : "выкл")
                .append(", intent ").append(scannerIntent ? "вкл" : "выкл")
                .append(", авто-подхват ").append(scannerIntentAuto ? "вкл" : "выкл");
        if (scannerIntentAction.length() > 0) {
            sb.append(" (").append(scannerIntentAction).append(')');
        }
        sb.append("\r\n");
        sb.append("лог: ").append(logEnabled ? path : "выкл").append("\r\n");
        if (error != null) {
            sb.append("ВНИМАНИЕ: ").append(error).append("\r\n");
        }
        return sb.toString();
    }

    private static String mask(String secret) {
        if (secret == null || secret.length() == 0) {
            return "****";
        }
        return "**** (" + secret.length() + " симв.)";
    }

    /** Создаёт файл-шаблон, если конфига на устройстве ещё нет. */
    public static boolean writeSample(String filePath) {
        try {
            File file = new File(filePath);
            if (file.exists()) {
                return false;
            }
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(SAMPLE.getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static final String SAMPLE =
            "# Конфигурация telnet-клиента для ТСД (Android 4.0.4)\n"
                    + "# Файл читается заново при каждом подключении — можно править на устройстве.\n"
                    + "# Комментарии начинаются с # или ;. Пробелы в значениях сохраняются!\n"
                    + "#\n"
                    + "# Escape-последовательности: \\e = ESC, \\r = CR, \\n = LF, \\t = TAB, \\xNN, \\033\n"
                    + "\n"
                    + "[server]\n"
                    + "# адрес сервера складской системы и порт telnet\n"
                    + "host=192.168.0.10\n"
                    + "port=23\n"
                    + "timeout=10\n"
                    + "# подключаться сразу при запуске и восстанавливать связь при обрыве\n"
                    + "auto_connect=1\n"
                    + "auto_reconnect=1\n"
                    + "reconnect_delay=5\n"
                    + "# важно для ТСД: без nodelay символы копятся и «залипают»\n"
                    + "nodelay=1\n"
                    + "keepalive=1\n"
                    + "# кодировки: cp866 / windows-1251 / koi8-r / utf-8\n"
                    + "charset_recv=cp866\n"
                    + "charset_send=cp866\n"
                    + "terminal_type=VT100\n"
                    + "# сервер присылает только LF без CR (типично для меню на Linux) — включаем автоперевод\n"
                    + "lf_as_crlf=1\n"
                    + "# что отправлять на Enter: CR, LF или CRLF\n"
                    + "enter=CR\n"
                    + "\n"
                    + "[auth]\n"
                    + "# логин и пароль берутся отсюда и подставляются в ответ на приглашение сервера\n"
                    + "login=kladovshik\n"
                    + "password=secret\n"
                    + "# тексты приглашений, после которых отправляется ответ выше\n"
                    + "prompt_login=login:\n"
                    + "prompt_password=Password:\n"
                    + "# произвольные пары «приглашение → ответ», если сервер спрашивает что-то ещё\n"
                    + "# prompt1=Код склада:\n"
                    + "# answer1=01\n"
                    + "# ответ отправлять сразу после подключения (без ожидания приглашения)\n"
                    + "send_on_connect=0\n"
                    + "# пауза перед ответом, чтобы сервер успел включить чтение строки\n"
                    + "delay_ms=300\n"
                    + "\n"
                    + "[keys]\n"
                    + "# Только исключения: всё остальное уходит на сервер как есть.\n"
                    + "# Клавиша=строка либо DISABLE, чтобы заглушить клавишу.\n"
                    + "# Имена клавиш: как в Android (KEYCODE_F1) или без префикса (F1), либо числовой код.\n"
                    + "# Подобрать код клавиши ТСД: включите в приложении меню > Диагностика ввода и нажмите клавишу —\n"
                    + "# на экране появится её имя и код, его и вписывайте сюда.\n"
                    + "# F1 -> ESC O P, стрелки -> ESC [ A/B/C/D\n"
                    + "#F1=\\eOP\n"
                    + "#F2=\\eOQ\n"
                    + "#VOLUME_UP=DISABLE\n"
                    + "#VOLUME_DOWN=DISABLE\n"
                    + "#BACKSPACE=\\x08\n"
                    + "#ENTER=CR\n"
                    + "\n"
                    + "[scanner]\n"
                    + "# keyboard — сканер работает как клавиатура (цифры + Enter), intent — приём штрихкода от iScan\n"
                    + "keyboard=1\n"
                    + "intent=0\n"
                    + "# слушать рассылки iScan, даже если intent=0 (0 — не слушать)\n"
                    + "intent_auto=1\n"
                    + "# Что включить в самом iScan (МЕНЮ > Настройки > Настройки сканирования > Дополнительно):\n"
                    + "#   Режим передачи результата = Рассылка / Intent (Broadcast)\n"
                    + "#   у iScan должна быть разрешена работа в фоне, иначе рассылка не дойдёт до приложения\n"
                    + "# action iData и имя extra со штрихкодом\n"
                    + "intent_action=android.intent.action.SCANRESULT\n"
                    + "intent_extra=value\n"
                    + "# можно перечислить несколько action через запятую — на случай смены прошивки:\n"
                    + "#intent_action=android.intent.action.SCANRESULT,nlscan.action.SCANNER_RESULT,com.symbol.datawedge.api.RESULT_ACTION\n"
                    + "# что дописать после штрихкода\n"
                    + "suffix=CR\n"
                    + "\n"
                    + "[screen]\n"
                    + "# auto_size=1 — сетка символов считается по размеру экрана (для широких меню).\n"
                    + "# Если сервер отдаёт строго 80x24, поставьте auto_size=0, cols=80, rows=24 —\n"
                    + "# приложение подберёт шрифт так, чтобы всё влезло.\n"
                    + "auto_size=1\n"
                    + "cols=80\n"
                    + "rows=24\n"
                    + "# в режиме auto_size=1: сетка, которая обязана влезть.\n"
                    + "# Меньше колонок — крупнее шрифт (40x12 обычно, 32x10 — крупнее, 48x16 — мельче)\n"
                    + "min_cols=40\n"
                    + "min_rows=12\n"
                    + "font_size=12\n"
                    + "max_font_size=20\n"
                    + "scrollback=500\n"
                    + "theme=dark\n"
                    + "# встроенный моношрифт с кириллицей и рамками (DejaVu Sans Mono).\n"
                    + "# Если системный шрифт ТСД рисует буквы ромбиками — оставьте 1.\n"
                    + "# 0 — использовать системный моношрифт\n"
                    + "builtin_font=1\n"
                    + "# ascii — если шрифт ТСД не рисует псевдографику\n"
                    + "box_drawing=unicode\n"
                    + "bright_bold=1\n"
                    + "\n"
                    + "[log]\n"
                    + "# лог в файл — помогает разбирать проблемы прямо на терминале\n"
                    + "enabled=0\n"
                    + "data=0\n"
                    + "file=/sdcard/telnet.log\n";
}
