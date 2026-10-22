package com.example.helloworld;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Лог в файл на карте памяти: на ТСД нет отладчика, поэтому все подключения,
 * ошибки и (по желанию) содержимое обмена пишутся в /sdcard/telnet.log.
 */
public final class LogKit {

    private static final long MAX_SIZE = 512 * 1024;

    private static boolean enabled;
    private static boolean logData;
    private static String path = "/sdcard/telnet.log";
    private static final SimpleDateFormat FMT = new SimpleDateFormat("dd.MM HH:mm:ss", Locale.US);

    private LogKit() {
    }

    public static void configure(boolean on, boolean data, String file) {
        enabled = on;
        logData = data;
        if (file != null && file.trim().length() > 0) {
            path = file.trim();
        }
        if (enabled) {
            log("---- запуск приложения, лог открыт ----");
        }
    }

    public static boolean isDataEnabled() {
        return enabled && logData;
    }

    public static void log(String message) {
        write("", message);
    }

    public static void data(String direction, String payload) {
        if (!enabled || !logData) {
            return;
        }
        write(direction, quote(payload));
    }

    private static String quote(String payload) {
        if (payload == null) {
            return "<null>";
        }
        StringBuilder sb = new StringBuilder(payload.length() + 16);
        int len = payload.length();
        for (int i = 0; i < len; i++) {
            char c = payload.charAt(i);
            if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\n') {
                sb.append("\\n\n            ");
            } else if (c == 0x1b) {
                sb.append("\\e");
            } else if (c < 0x20) {
                sb.append('\\').append((int) c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static synchronized void write(String tag, String message) {
        if (!enabled) {
            return;
        }
        try {
            File file = new File(path);
            if (file.exists() && file.length() > MAX_SIZE) {
                file.delete();
            }
            FileOutputStream out = new FileOutputStream(file, true);
            try {
                String line = "[" + FMT.format(new Date()) + "] "
                        + (tag.length() > 0 ? tag + " " : "")
                        + message + "\r\n";
                out.write(line.getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Exception e) {
            // логировать некуда — молчим, приложение из-за лога падать не должно
        }
    }
}
