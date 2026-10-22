package com.example.helloworld;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Telnet-клиент для складской системы.
 *
 * Что делает:
 *  - держит сокет и два потока (чтение и запись), UI никогда не блокируется;
 *  - разбирает переговоры telnet (IAC WILL/DO/WONT/DONT, SB/SE) и отвечает только за
 *    поддерживаемые опции: BINARY, SUPPRESS_GO_AHEAD, TERMINAL_TYPE, WINDOW_SIZE;
 *  - из принятого потока выкидывает управляющие команды telnet и отдаёт «чистый» текст
 *    разборщику ANSI;
 *  - автоматически отвечает на приглашения аутентификации значениями из конфига.
 *
 * Колбэки вызываются из потоков клиента — UI обязан переключаться через Handler.
 */
public class TelnetClient {

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;
    public static final int STATE_ERROR = 3;

    public interface Listener {
        void onState(int state, String message);

        void onText(String text);

        void onAuthSent(String prompt, String answer);
    }

    // команды telnet
    private static final int IAC = 255;
    private static final int DONT = 254;
    private static final int DO = 253;
    private static final int WONT = 252;
    private static final int WILL = 251;
    private static final int SB = 250;
    private static final int SE = 240;

    // опции
    private static final int OPT_BINARY = 0;
    private static final int OPT_ECHO = 1;
    private static final int OPT_SGA = 3;
    private static final int OPT_TTYPE = 24;
    private static final int OPT_NAWS = 31;

    private static final int TTYPE_IS = 0;
    private static final int TTYPE_SEND = 1;

    // состояния разбора IAC
    private static final int P_DATA = 0;
    private static final int P_IAC = 1;
    private static final int P_OPTION = 2;
    private static final int P_SB = 3;
    private static final int P_SB_IAC = 4;

    private final TelnetConfig cfg;
    private final Listener listener;

    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private Thread readerThread;
    private Thread writerThread;

    private volatile boolean running;
    private volatile boolean connected;

    private final List<byte[]> outQueue = new ArrayList<byte[]>();
    private final Object outLock = new Object();

    private int parseState = P_DATA;
    private int pendingCommand;
    private int subOption = -1;
    private boolean subExpectOption;
    private final StringBuilder subData = new StringBuilder();

    private final byte[] readBuffer = new byte[4096];
    private byte[] pendingBuf = new byte[8192];
    private int pendingLen;

    // аутентификация
    private boolean[] authAnswered = new boolean[0];
    private final StringBuilder tail = new StringBuilder(256);
    private final StringBuilder recent = new StringBuilder(256);

    private int windowCols = 80;
    private int windowRows = 24;
    private volatile boolean terminalTypeSent;

    public TelnetClient(TelnetConfig cfg, Listener listener) {
        this.cfg = cfg;
        this.listener = listener;
        this.authAnswered = new boolean[cfg.authPairs.size()];
    }

    public boolean isConnected() {
        return connected;
    }

    /** Размер экрана для опции NAWS — сервер узнаёт, сколько символов влезает. */
    public void setWindowSize(int cols, int rows) {
        boolean changed = cols != windowCols || rows != windowRows;
        windowCols = Math.max(1, cols);
        windowRows = Math.max(1, rows);
        if (changed && connected) {
            sendWindowSize();
        }
    }

    // ------------------------------------------------------------- подключение

    public void connect() {
        if (connected || running) {
            return;
        }
        authAnswered = new boolean[cfg.authPairs.size()];
        tail.setLength(0);
        recent.setLength(0);
        terminalTypeSent = false;
        pendingLen = 0;
        parseState = P_DATA;
        subOption = -1;
        subExpectOption = false;
        synchronized (outLock) {
            outQueue.clear();
        }
        running = true;
        state(STATE_CONNECTING, "подключение к " + cfg.host + ":" + cfg.port);
        Thread starter = new Thread(new Runnable() {
            public void run() {
                openSocket();
            }
        }, "telnet-connect");
        starter.setDaemon(true);
        starter.start();
    }

    private void openSocket() {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(cfg.host, cfg.port), Math.max(1, cfg.timeoutSec) * 1000);
            s.setTcpNoDelay(cfg.noDelay);
            s.setKeepAlive(cfg.keepAlive);
            s.setSoTimeout(0);
            socket = s;
            input = s.getInputStream();
            output = s.getOutputStream();
            connected = true;
            state(STATE_CONNECTED, "соединение установлено");
            LogKit.log("подключено к " + cfg.host + ":" + cfg.port);

            writerThread = new Thread(new Runnable() {
                public void run() {
                    writeLoop();
                }
            }, "telnet-writer");
            writerThread.setDaemon(true);
            writerThread.start();

            readerThread = new Thread(new Runnable() {
                public void run() {
                    readLoop();
                }
            }, "telnet-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            if (cfg.authOnConnect) {
                sendAllAuthAtOnce();
            }
        } catch (Exception e) {
            closeQuietly();
            String reason = e.getClass().getSimpleName();
            if (e.getMessage() != null) {
                reason = reason + ": " + e.getMessage();
            }
            LogKit.log("ошибка подключения: " + reason);
            state(STATE_ERROR, "не удалось подключиться: " + reason);
        }
    }

    private void sendAllAuthAtOnce() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                sleep(cfg.authDelayMs);
                List<TelnetConfig.AuthPair> pairs = cfg.authPairs;
                for (int i = 0; i < pairs.size(); i++) {
                    TelnetConfig.AuthPair pair = pairs.get(i);
                    if (!running) {
                        return;
                    }
                    sendLine(pair.answer);
                    if (i < authAnswered.length) {
                        authAnswered[i] = true;
                    }
                    authSent(pair.prompt, pair.answer);
                    sleep(cfg.authDelayMs);
                }
            }
        }, "telnet-auth");
        t.setDaemon(true);
        t.start();
    }

    /** Мягкое отключение: сначала прощаемся, потом закрываем сокет. */
    public void disconnect() {
        running = false;
        LogKit.log("отключение по запросу");
        synchronized (outLock) {
            outLock.notifyAll();
        }
        closeQuietly();
        state(STATE_DISCONNECTED, "отключено");
    }

    private void closeQuietly() {
        connected = false;
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // сокет уже мог быть закрыт
            }
        }
        input = null;
        output = null;
    }

    // ---------------------------------------------------------------- передача

    public void send(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        if (!connected) {
            return;
        }
        synchronized (outLock) {
            outQueue.add(data);
            outLock.notifyAll();
        }
    }

    public void sendText(String text) {
        if (text == null || text.length() == 0) {
            return;
        }
        send(CharsetKit.encode(text, cfg.charsetSend));
        LogKit.data("->", text);
    }

    /** Отправка строки с завершением ввода (Enter по конфигу). */
    public void sendLine(String text) {
        sendText(text);
        send(CharsetKit.encode(cfg.enterSequence, cfg.charsetSend));
        LogKit.data("->", text + cfg.enterSequence);
    }

    private void writeLoop() {
        while (running) {
            byte[] data = null;
            synchronized (outLock) {
                while (running && outQueue.isEmpty()) {
                    try {
                        outLock.wait(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!running) {
                    return;
                }
                if (!outQueue.isEmpty()) {
                    data = outQueue.remove(0);
                }
            }
            if (data != null) {
                OutputStream out = output;
                if (out == null) {
                    return;
                }
                try {
                    out.write(data);
                    out.flush();
                } catch (IOException e) {
                    if (running) {
                        LogKit.log("ошибка отправки: " + e.getMessage());
                        fail("потеря связи при отправке: " + e.getMessage());
                    }
                    return;
                }
            }
        }
    }

    private void sendCommand(int command, int option) {
        sendRaw(new byte[] { (byte) IAC, (byte) command, (byte) option });
    }

    private void sendRaw(byte[] data) {
        if (!connected) {
            return;
        }
        synchronized (outLock) {
            outQueue.add(data);
            outLock.notifyAll();
        }
    }

    private void sendWindowSize() {
        int cols = Math.min(65535, windowCols);
        int rows = Math.min(65535, windowRows);
        byte[] data = new byte[] {
                (byte) IAC, (byte) SB, (byte) OPT_NAWS,
                (byte) ((cols >> 8) & 0xff), (byte) (cols & 0xff),
                (byte) ((rows >> 8) & 0xff), (byte) (rows & 0xff),
                (byte) IAC, (byte) SE
        };
        sendRaw(data);
    }

    private void sendTerminalType() {
        byte[] type = CharsetKit.encode(cfg.terminalType, "US-ASCII");
        byte[] data = new byte[type.length + 6];
        int p = 0;
        data[p++] = (byte) IAC;
        data[p++] = (byte) SB;
        data[p++] = (byte) OPT_TTYPE;
        data[p++] = (byte) TTYPE_IS;
        for (int i = 0; i < type.length; i++) {
            data[p++] = type[i];
        }
        data[p++] = (byte) IAC;
        data[p++] = (byte) SE;
        sendRaw(data);
    }

    // ----------------------------------------------------------------- чтение

    private void readLoop() {
        try {
            while (running) {
                InputStream in = input;
                if (in == null) {
                    break;
                }
                int count = in.read(readBuffer);
                if (count < 0) {
                    break;
                }
                if (count > 0) {
                    parse(readBuffer, count);
                    flushPending();
                }
            }
            if (running) {
                fail("сервер закрыл соединение");
            }
        } catch (Exception e) {
            if (running) {
                fail("обрыв связи: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            }
        }
    }

    private void fail(String message) {
        running = false;
        connected = false;
        LogKit.log(message);
        closeQuietly();
        synchronized (outLock) {
            outLock.notifyAll();
        }
        state(STATE_ERROR, message);
    }

    /** Разбор потока telnet: выкидываем IAC-последовательности, остальное — текст. */
    private void parse(byte[] data, int length) {
        for (int i = 0; i < length; i++) {
            int b = data[i] & 0xff;
            switch (parseState) {
                case P_DATA:
                    if (b == IAC) {
                        parseState = P_IAC;
                    } else {
                        appendPending(b);
                    }
                    break;
                case P_IAC:
                    if (b == IAC) {
                        appendPending(IAC);
                        parseState = P_DATA;
                    } else if (b == WILL || b == WONT || b == DO || b == DONT) {
                        pendingCommand = b;
                        parseState = P_OPTION;
                    } else if (b == SB) {
                        subOption = -1;
                        subExpectOption = true;
                        subData.setLength(0);
                        parseState = P_SB;
                    } else {
                        parseState = P_DATA;
                    }
                    break;
                case P_OPTION:
                    handleOption(pendingCommand, b);
                    parseState = P_DATA;
                    break;
                case P_SB:
                    if (b == IAC) {
                        parseState = P_SB_IAC;
                    } else if (subExpectOption) {
                        subOption = b;
                        subExpectOption = false;
                    } else if (subData.length() < 128) {
                        subData.append((char) b);
                    }
                    break;
                case P_SB_IAC:
                    if (b == SE) {
                        handleSubnegotiation();
                        parseState = P_DATA;
                    } else if (b == IAC) {
                        if (subExpectOption) {
                            subOption = IAC;
                            subExpectOption = false;
                        } else if (subData.length() < 128) {
                            subData.append((char) IAC);
                        }
                        parseState = P_SB;
                    } else {
                        parseState = P_SB;
                    }
                    break;
                default:
                    parseState = P_DATA;
                    break;
            }
        }
    }

    /** Складывает принятые байты: для однобайтовых кодировок хватает прямого накопления. */
    private void appendPending(int b) {
        if (pendingLen >= pendingBuf.length) {
            byte[] bigger = new byte[pendingBuf.length * 2];
            System.arraycopy(pendingBuf, 0, bigger, 0, pendingLen);
            pendingBuf = bigger;
        }
        pendingBuf[pendingLen++] = (byte) b;
    }

    private void flushPending() {
        if (pendingLen == 0) {
            return;
        }
        int hold = 0;
        if ("UTF-8".equals(cfg.charsetRecv)) {
            hold = incompleteUtf8Tail();
        }
        int usable = pendingLen - hold;
        if (usable <= 0) {
            return;
        }
        String decoded = CharsetKit.decode(pendingBuf, 0, usable, cfg.charsetRecv);
        for (int i = 0; i < hold; i++) {
            pendingBuf[i] = pendingBuf[usable + i];
        }
        pendingLen = hold;
        LogKit.data("<-", decoded);
        onText(decoded);
    }

    /**
     * Сколько байт в хвосте образуют недописанный символ UTF-8: их нельзя декодировать
     * сейчас, иначе на экране получится «ромбик».
     */
    private int incompleteUtf8Tail() {
        int max = Math.min(4, pendingLen);
        for (int back = 1; back <= max; back++) {
            int b = pendingBuf[pendingLen - back] & 0xff;
            if ((b & 0xc0) == 0x80) {
                continue;
            }
            int need;
            if ((b & 0x80) == 0) {
                need = 1;
            } else if ((b & 0xe0) == 0xc0) {
                need = 2;
            } else if ((b & 0xf0) == 0xe0) {
                need = 3;
            } else if ((b & 0xf8) == 0xf0) {
                need = 4;
            } else {
                return 0;
            }
            return back < need ? back : 0;
        }
        return 0;
    }

    private void onText(String text) {
        listener.onText(text);
        if (cfg.authEnabled) {
            scanAuth(text);
        }
    }

    // ------------------------------------------------------------ переговоры

    private void handleOption(int command, int option) {
        switch (command) {
            case DO:
                if (option == OPT_ECHO) {
                    // эхо ввода оставляем серверу
                    sendCommand(WONT, option);
                } else if (supports(option)) {
                    sendCommand(WILL, option);
                    if (option == OPT_NAWS) {
                        sendWindowSize();
                    }
                } else {
                    sendCommand(WONT, option);
                }
                break;
            case DONT:
                if (supports(option)) {
                    sendCommand(WONT, option);
                }
                break;
            case WILL:
                if (option == OPT_ECHO || supports(option)) {
                    sendCommand(DO, option);
                } else {
                    sendCommand(DONT, option);
                }
                break;
            case WONT:
                if (supports(option)) {
                    sendCommand(DONT, option);
                }
                break;
            default:
                break;
        }
    }

    private boolean supports(int option) {
        return option == OPT_BINARY || option == OPT_SGA || option == OPT_TTYPE
                || option == OPT_NAWS || option == OPT_ECHO;
    }

    private void handleSubnegotiation() {
        if (subOption == OPT_TTYPE) {
            if (subData.length() > 0 && subData.charAt(0) == (char) TTYPE_SEND) {
                sendTerminalType();
                terminalTypeSent = true;
            }
        }
    }

    // ------------------------------------------------------- аутентификация

    /**
     * Ищем приглашение сервера в конце принятого текста. Последовательности ANSI из текста
     * вырезаются, иначе приглашение с подсветкой не совпадёт.
     */
    private void scanAuth(String text) {
        int len = text.length();
        boolean inEscape = false;
        boolean inCsi = false;
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (inEscape) {
                if (c == '[') {
                    inCsi = true;
                    inEscape = false;
                } else {
                    inEscape = false;
                }
                continue;
            }
            if (inCsi) {
                if (c >= '@' && c <= '~') {
                    inCsi = false;
                }
                continue;
            }
            if (c == 0x1b) {
                inEscape = true;
                continue;
            }
            if (c == '\r') {
                recent.setLength(0);
                tail.setLength(0);
                continue;
            }
            if (c == '\n') {
                recent.setLength(0);
                continue;
            }
            if (c == '\b') {
                if (tail.length() > 0) {
                    tail.setLength(tail.length() - 1);
                }
                continue;
            }
            if (c < 0x20) {
                continue;
            }
            tail.append(c);
            recent.append(c);
            if (tail.length() > 256) {
                tail.delete(0, tail.length() - 256);
            }
            if (recent.length() > 512) {
                recent.delete(0, recent.length() - 512);
            }
        }
        checkPrompts();
    }

    private void checkPrompts() {
        List<TelnetConfig.AuthPair> pairs = cfg.authPairs;
        if (pairs.isEmpty() || authAnswered.length != pairs.size()) {
            authAnswered = new boolean[pairs.size()];
        }
        String line = tail.toString().trim();
        String buffer = recent.toString().trim();
        for (int i = 0; i < pairs.size(); i++) {
            if (authAnswered[i]) {
                continue;
            }
            TelnetConfig.AuthPair pair = pairs.get(i);
            if (pair.prompt == null || pair.prompt.length() == 0) {
                continue;
            }
            String prompt = pair.prompt.trim();
            String promptLower = prompt.toLowerCase();
            boolean matched = line.length() > 0 && line.toLowerCase().endsWith(promptLower);
            if (!matched && buffer.length() > 0) {
                matched = buffer.toLowerCase().endsWith(promptLower);
            }
            if (!matched) {
                continue;
            }
            authAnswered[i] = true;
            answerWithDelay(pair);
        }
    }

    private void answerWithDelay(final TelnetConfig.AuthPair pair) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                sleep(cfg.authDelayMs);
                if (!running) {
                    return;
                }
                sendLine(pair.answer);
                authSent(pair.prompt, pair.answer);
                // на экране ТСД видно, что ответ ушёл, но сам пароль не светится
                state(STATE_CONNECTED, "аутентификация: ответ на \"" + pair.prompt.trim() + "\" отправлен");
            }
        }, "telnet-auth-answer");
        t.setDaemon(true);
        t.start();
    }

    private void authSent(String prompt, String answer) {
        listener.onAuthSent(prompt, answer);
        LogKit.log("аутентификация: \"" + prompt + "\" -> ответ отправлен (" + answer.length() + " симв.)");
    }

    private void state(int code, String message) {
        listener.onState(code, message);
    }

    private void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
