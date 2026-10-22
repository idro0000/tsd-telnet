package com.example.helloworld;

import android.view.KeyEvent;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Преобразование нажатий клавиш ТСД в байты для сервера.
 *
 * По умолчанию на сервер уходит всё «как есть» (печатные символы, Enter, стрелки, F1..F12),
 * из конфига берутся только исключения: подмена клавиши нужной строкой или её блокировка.
 */
public final class KeyMapper {

    private static final byte ESC = 0x1b;
    private static final byte CR = 0x0d;
    private static final byte LF = 0x0a;
    private static final byte DEL = 0x7f;
    private static final byte BS = 0x08;
    private static final byte TAB = 0x09;

    /** Имена KEYCODE_* из android.view.KeyEvent: и полное имя, и короткое. */
    private static Map<String, Integer> keyNames;

    private KeyMapper() {
    }

    private static synchronized Map<String, Integer> names() {
        if (keyNames == null) {
            Map<String, Integer> map = new HashMap<String, Integer>();
            try {
                Field[] fields = KeyEvent.class.getFields();
                for (int i = 0; i < fields.length; i++) {
                    Field field = fields[i];
                    if (field.getType() != int.class) {
                        continue;
                    }
                    String name = field.getName();
                    if (!name.startsWith("KEYCODE_")) {
                        continue;
                    }
                    try {
                        Integer value = Integer.valueOf(field.getInt(null));
                        map.put(name, value);
                        map.put(name.substring("KEYCODE_".length()), value);
                    } catch (Exception ignored) {
                        // недоступное поле — просто пропускаем
                    }
                }
            } catch (Throwable ignored) {
                // на всякий случай: без таблицы имён останутся только числовые коды
            }
            keyNames = map;
        }
        return keyNames;
    }

    /** Числовой код клавиши по имени из конфига (KEYCODE_F1, F1, VOLUME_UP) или по числу. */
    public static Integer keyCodeByName(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim();
        if (n.length() == 0) {
            return null;
        }
        boolean numeric = true;
        for (int i = 0; i < n.length(); i++) {
            if (!Character.isDigit(n.charAt(i))) {
                numeric = false;
                break;
            }
        }
        if (numeric) {
            try {
                return Integer.valueOf(Integer.parseInt(n));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        String upper = n.toUpperCase().replace(' ', '_');
        if (!upper.startsWith("KEYCODE_")) {
            Integer direct = names().get(upper);
            if (direct != null) {
                return direct;
            }
            return names().get("KEYCODE_" + upper);
        }
        return names().get(upper);
    }

    /** Читаемое имя клавиши для сообщений на экране. */
    public static String keyName(int code) {
        Map<String, Integer> map = names();
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            if (entry.getKey().startsWith("KEYCODE_") && entry.getValue().intValue() == code) {
                return entry.getKey();
            }
        }
        return "код " + code;
    }

    /**
     * Что отправить на сервер при нажатии.
     *
     * @return байты для отправки или null, если клавишу обрабатывает приложение/система
     */
    public static byte[] map(KeyEvent event, TelnetConfig cfg, boolean isRepeat) {
        if (event == null || cfg == null) {
            return null;
        }
        int code = event.getKeyCode();

        // 1. исключения из конфига
        if (cfg.keyDisabled.contains(Integer.valueOf(code))) {
            return null;
        }
        byte[] override = cfg.keyOverrides.get(Integer.valueOf(code));
        if (override != null) {
            return override;
        }

        // 2. клавиши, которые приложение забирает себе
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SEARCH
                || code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_CAMERA) {
            return null;
        }

        // 3. модификаторы и служебные — на сервер не идут
        if (isModifier(code)) {
            return null;
        }

        // 4. стандартные соответствия
        switch (code) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return CharsetKit.encode(cfg.enterSequence, cfg.charsetSend);
            case KeyEvent.KEYCODE_DEL:
                return new byte[] { DEL };
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return new byte[] { ESC, '[', '3', '~' };
            case KeyEvent.KEYCODE_TAB:
                return new byte[] { TAB };
            case KeyEvent.KEYCODE_ESCAPE:
                return new byte[] { ESC };
            case KeyEvent.KEYCODE_DPAD_UP:
                return new byte[] { ESC, '[', 'A' };
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return new byte[] { ESC, '[', 'B' };
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return new byte[] { ESC, '[', 'C' };
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return new byte[] { ESC, '[', 'D' };
            case KeyEvent.KEYCODE_MOVE_HOME:
                return new byte[] { ESC, '[', 'H' };
            case KeyEvent.KEYCODE_MOVE_END:
                return new byte[] { ESC, '[', 'F' };
            case KeyEvent.KEYCODE_PAGE_UP:
                return new byte[] { ESC, '[', '5', '~' };
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return new byte[] { ESC, '[', '6', '~' };
            case KeyEvent.KEYCODE_INSERT:
                return new byte[] { ESC, '[', '2', '~' };
            default:
                break;
        }
        if (code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F4) {
            byte fin = (byte) ('P' + (code - KeyEvent.KEYCODE_F1));
            return new byte[] { ESC, 'O', fin };
        }
        if (code >= KeyEvent.KEYCODE_F5 && code <= KeyEvent.KEYCODE_F12) {
            int num = 15 + (code - KeyEvent.KEYCODE_F5);
            return CharsetKit.encode("\u001b[" + num + "~", cfg.charsetSend);
        }

        // 5. Ctrl+буква — управляющий код, как в обычном терминале
        int meta = event.getMetaState();
        if ((meta & KeyEvent.META_CTRL_ON) != 0 || (meta & KeyEvent.META_CTRL_LEFT_ON) != 0
                || (meta & KeyEvent.META_CTRL_RIGHT_ON) != 0) {
            int plain = event.getUnicodeChar(meta & ~(KeyEvent.META_CTRL_ON
                    | KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_RIGHT_ON));
            char lower = Character.toLowerCase((char) plain);
            if (lower >= 'a' && lower <= 'z') {
                return new byte[] { (byte) (lower - 'a' + 1) };
            }
        }

        // 6. печатные символы
        int unicode = event.getUnicodeChar(meta);
        if (unicode <= 0 || unicode == 0xFFFD) {
            // На части ТСД карта клавиш прошивки не отдаёт буквы (getUnicodeChar = 0),
            // хотя код клавиши приходит правильный. Тогда символ берём из кода клавиши.
            unicode = fallbackChar(event);
        }
        if (unicode > 0 && unicode != '\n' && unicode != '\r' && unicode != 0x0a) {
            char ch = (char) unicode;
            if (isPrintable(ch)) {
                return CharsetKit.encode(String.valueOf(ch), cfg.charsetSend);
            }
        }
        if (unicode == '\n') {
            return CharsetKit.encode(cfg.enterSequence, cfg.charsetSend);
        }
        return null;
    }

    private static boolean isPrintable(char c) {
        if (c < 0x20) {
            return false;
        }
        return c != 0x7f;
    }

    /**
     * Запасное сопоставление, если карта клавиш ТСД не выдала символ:
     * буквы A..Z, цифры 0..9 и пробел — по коду клавиши.
     */
    private static int fallbackChar(KeyEvent event) {
        int code = event.getKeyCode();
        char c = 0;
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) {
            c = (char) ('a' + (code - KeyEvent.KEYCODE_A));
        } else if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            c = (char) ('0' + (code - KeyEvent.KEYCODE_0));
        } else if (code == KeyEvent.KEYCODE_SPACE) {
            c = ' ';
        }
        if (c == 0) {
            return 0;
        }
        if (c >= 'a' && c <= 'z' && event.isShiftPressed()) {
            c = Character.toUpperCase(c);
        }
        return c;
    }

    private static boolean isModifier(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_CAPS_LOCK:
            case KeyEvent.KEYCODE_SYM:
            case KeyEvent.KEYCODE_FUNCTION:
            case KeyEvent.KEYCODE_NUM_LOCK:
                return true;
            default:
                return false;
        }
    }

    /** Запасной вариант: то же самое, но с явным «села ли клавиша». */
    public static byte[] map(KeyEvent event, TelnetConfig cfg) {
        return map(event, cfg, event != null && event.getRepeatCount() > 0);
    }
}
