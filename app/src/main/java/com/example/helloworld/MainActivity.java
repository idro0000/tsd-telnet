package com.example.helloworld;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * Экран telnet-клиента для терминала сбора данных.
 *
 * Логика: конфиг из файла на карте памяти → сокет и два потока в TelnetClient →
 * разбор ANSI в TerminalScreen → отрисовка в TerminalView. Клавиши ТСД уходят на сервер
 * напрямую (кроме исключений из конфига), штрихкод приходит либо как клавиатурный ввод,
 * либо широковещательным intent от сервиса производителя.
 */
public class MainActivity extends Activity implements TelnetClient.Listener, TerminalView.Listener {

    private static final String CONFIG_PATH = "/sdcard/telnet.ini";

    private static final int MENU_CONNECT = 1;
    private static final int MENU_DISCONNECT = 2;
    private static final int MENU_RELOAD = 3;
    private static final int MENU_SHOW = 4;
    private static final int MENU_KEYBOARD = 5;
    private static final int MENU_ABOUT = 6;
    private static final int MENU_DIAG = 7;
    private static final int MENU_CHARSET = 8;
    private static final int MENU_FONT_UP = 9;
    private static final int MENU_FONT_DOWN = 10;

    /** Порядок перебора кодировок в меню. */
    private static final String[] CHARSETS = { "cp866", "windows-1251", "koi8-r", "utf-8" };

    private TerminalView view;
    private TextView status;
    private EditText input;
    private TerminalScreen screen;
    private AnsiParser parser;
    private TelnetConfig config;
    private TelnetClient client;
    private final Handler ui = new Handler();

    private boolean userDisconnected;
    private boolean inputDiagnostics;
    private int lastCols = -1;
    private int lastRows = -1;
    private String stateText = "инициализация";
    private BroadcastReceiver scannerReceiver;

    private final Runnable reconnectTask = new Runnable() {
        public void run() {
            if (config != null && config.autoReconnect && !userDisconnected) {
                printLocal("\r\n*** переподключение...\r\n");
                connect();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        view = (TerminalView) findViewById(R.id.terminal);
        status = (TextView) findViewById(R.id.status);
        input = (EditText) findViewById(R.id.input);
        view.setListener(this);
        view.requestFocus();

        // строка ввода нужна только для экранной клавиатуры: физические клавиши
        // уходят на сервер напрямую через dispatchKeyEvent
        input.setOnKeyListener(new android.view.View.OnKeyListener() {
            public boolean onKey(android.view.View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER)) {
                    submitInput();
                    return true;
                }
                return false;
            }
        });

        loadConfig(true);

        view.setScreen(screen);
        view.configure(config.fontSize, config.maxFontSize, config.autoSize,
                config.lightTheme, config.asciiBox, config.brightBold, config.builtinFont,
                config.minCols, config.minRows);

        // после разметки сообщаем серверу размер экрана (опция NAWS)
        view.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            public void onGlobalLayout() {
                syncWindowSize();
            }
        });

        if (config.error != null) {
            printLocal("\r\n*** " + config.error + "\r\n");
        }
        printLocal("ТСД telnet: " + config.host + ":" + config.port
                + "  (меню — клавиша MENU, долгое нажатие по экрану)\r\n");

        if (config.autoConnect) {
            connect();
        } else {
            updateStatus();
        }
    }

    // ------------------------------------------------------------------ конфиг

    private void loadConfig(boolean createSample) {
        File file = new File(CONFIG_PATH);
        if (!file.exists() && createSample) {
            if (TelnetConfig.writeSample(CONFIG_PATH)) {
                toast("создан файл настроек " + CONFIG_PATH);
            }
        }
        TelnetConfig loaded = TelnetConfig.load(CONFIG_PATH);
        config = loaded;
        LogKit.configure(config.logEnabled, config.logData, config.logPath);

        int cols = config.autoSize ? Math.max(20, config.cols) : config.cols;
        int rows = config.autoSize ? Math.max(6, config.rows) : config.rows;
        if (screen == null) {
            screen = new TerminalScreen(cols, rows, config.scrollback);
        } else {
            screen.resize(cols, rows);
        }
        parser = new AnsiParser(screen, new AnsiParser.Reply() {
            public void onReply(String text) {
                TelnetClient c = client;
                if (c != null && c.isConnected()) {
                    c.send(CharsetKit.encode(text, config.charsetSend));
                }
            }
        });
        parser.setCrOnLf(config.lfAsCrLf);
    }

    /** Перечитать файл настроек и применить без перезапуска приложения. */
    /**
     * Крупнее/мельче шрифт. В авто-режиме размер следует из минимальной сетки,
     * поэтому меняется число колонок: меньше колонок — крупнее буквы.
     * Подобранное значение печатается готовыми строками для файла настроек.
     */
    private void zoomFont(int direction) {
        if (view == null || config == null) {
            return;
        }
        view.zoom(direction);
        config.minCols = view.getMinCols();
        config.minRows = view.getMinRows();
        syncWindowSize();
        int cols;
        int rows;
        if (screen != null) {
            synchronized (screen) {
                cols = screen.getCols();
                rows = screen.getRows();
            }
        } else {
            cols = config.cols;
            rows = config.rows;
        }
        printLocal("\r\n*** шрифт " + view.getFontPx() + " px, сетка " + cols + "x" + rows
                + "\r\n*** закрепить в файле: min_cols=" + view.getMinCols()
                + ", min_rows=" + view.getMinRows() + "\r\n");
    }

    /**
     * Перебор кодировок на месте: подбирать текст проще, чем править файл и перезапускать.
     * Меняется только на время работы приложения; в файле значение остаётся прежним.
     */
    private void cycleCharset() {
        if (config == null) {
            return;
        }
        int index = 0;
        for (int i = 0; i < CHARSETS.length; i++) {
            if (CHARSETS[i].equalsIgnoreCase(config.charsetRecv)) {
                index = i;
                break;
            }
        }
        String next = CHARSETS[(index + 1) % CHARSETS.length];
        config.charsetRecv = next;
        config.charsetSend = next;
        updateStatus();
        toast("кодировка: " + next);
        printLocal("\r\n*** кодировка: " + next + " (до перезапуска, файл не изменён)\r\n");
        if (client != null) {
            if (screen != null) {
                synchronized (screen) {
                    screen.reset();
                }
            }
            connect();
        }
    }

    private void reloadConfig() {
        boolean wasConnected = client != null && client.isConnected();
        LogKit.log("перезагрузка конфига " + CONFIG_PATH);
        int oldFont = view.getFontPx();
        loadConfig(false);
        view.configure(config.fontSize, config.maxFontSize, config.autoSize,
                config.lightTheme, config.asciiBox, config.brightBold, config.builtinFont,
                config.minCols, config.minRows);
        synchronized (screen) {
            view.setScreen(screen);
        }
        printLocal("\r\n*** настройки перечитаны (шрифт был " + oldFont + ")\r\n"
                + config.describe() + "\r\n");
        updateStatus();
        if (scannerWanted() && scannerReceiver == null) {
            registerScanner();
        } else if (!scannerWanted()) {
            unregisterScanner();
        }
        if (wasConnected) {
            printLocal("*** изменения хоста/порта применятся при следующем подключении\r\n");
        }
    }

    // -------------------------------------------------------------- подключение

    private void connect() {
        ui.removeCallbacks(reconnectTask);
        userDisconnected = false;
        TelnetClient old = client;
        if (old != null) {
            old.disconnect();
        }
        // клиент всегда создаётся заново: так в него попадают свежие настройки из файла
        client = new TelnetClient(config, this);
        lastCols = -1;
        lastRows = -1;
        printLocal("\r\n*** " + config.host + ":" + config.port + " — " + config.charsetRecv + "\r\n");
        syncWindowSize();
        client.connect();
        view.requestFocus();
    }

    private void disconnect(boolean byUser) {
        userDisconnected = byUser;
        ui.removeCallbacks(reconnectTask);
        if (client != null) {
            client.disconnect();
        }
    }

    private void syncWindowSize() {
        if (screen == null || client == null) {
            return;
        }
        int cols;
        int rows;
        synchronized (screen) {
            cols = screen.getCols();
            rows = screen.getRows();
        }
        if (cols != lastCols || rows != lastRows) {
            lastCols = cols;
            lastRows = rows;
            client.setWindowSize(cols, rows);
            updateStatus();
        }
    }

    // ------------------------------------------------------ колбэки TelnetClient

    public void onText(final String text) {
        TerminalScreen target = screen;
        AnsiParser p = parser;
        if (target == null || p == null) {
            return;
        }
        synchronized (target) {
            p.feed(text);
        }
        TerminalView v = view;
        if (v != null) {
            v.postInvalidate();
        }
    }

    public void onState(final int state, final String message) {
        ui.post(new Runnable() {
            public void run() {
                applyState(state, message);
            }
        });
    }

    public void onAuthSent(final String prompt, final String answer) {
        ui.post(new Runnable() {
            public void run() {
                String masked = answer.length() > 0 ? "****" : "<пусто>";
                stateText = "аутентификация: \"" + prompt.trim() + "\" → " + masked;
                printLocal("*** отправлен ответ на \"" + prompt.trim() + "\" " + masked + "\r\n");
                updateStatus();
            }
        });
    }

    private void applyState(int state, String message) {
        switch (state) {
            case TelnetClient.STATE_CONNECTING:
                stateText = "подключение...";
                break;
            case TelnetClient.STATE_CONNECTED:
                stateText = connectedText(message);
                break;
            case TelnetClient.STATE_DISCONNECTED:
                stateText = "отключено";
                break;
            case TelnetClient.STATE_ERROR:
                stateText = message;
                printLocal("\r\n*** " + message + "\r\n");
                if (config.autoReconnect && !userDisconnected) {
                    printLocal("*** повтор через " + config.reconnectDelaySec + " с\r\n");
                    ui.removeCallbacks(reconnectTask);
                    ui.postDelayed(reconnectTask, Math.max(1, config.reconnectDelaySec) * 1000L);
                }
                break;
            default:
                break;
        }
        if (state == TelnetClient.STATE_CONNECTED && !"соединение установлено".equals(message)) {
            printLocal("*** " + message + "\r\n");
        }
        updateStatus();
    }

    private String connectedText(String message) {
        return "соединение установлено".equals(message) ? "подключено" : message;
    }

    private void updateStatus() {
        if (status == null || config == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(config.host).append(':').append(config.port).append(" | ").append(stateText);
        sb.append(" | ").append(lastCols > 0 ? lastCols + "x" + lastRows : config.cols + "x" + config.rows);
        sb.append(" | ").append(config.charsetRecv);
        if (view != null && view.isScrolledBack()) {
            sb.append(" | история");
        }
        status.setText(sb.toString());
    }

    private void printLocal(String text) {
        TerminalScreen target = screen;
        AnsiParser p = parser;
        if (target == null || p == null) {
            return;
        }
        synchronized (target) {
            p.feed(text);
        }
        TerminalView v = view;
        if (v != null) {
            v.postInvalidate();
        }
    }

    // ------------------------------------------------------------------- ввод

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (config == null) {
            return super.dispatchKeyEvent(event);
        }
        int code = event.getKeyCode();
        boolean overridden = config.keyOverrides.containsKey(Integer.valueOf(code));
        boolean disabled = config.keyDisabled.contains(Integer.valueOf(code));

        // диагностика: показываем код клавиши — по нему составляются исключения в [keys]
        if (inputDiagnostics && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            printLocal("клавиша: " + KeyMapper.keyName(code) + " (код " + code
                    + "), meta=" + event.getMetaState()
                    + ", unicode=" + event.getUnicodeChar(0)
                    + (overridden ? ", подмена в конфиге" : (disabled ? ", заглушена" : ""))
                    + "\r\n");
        }

        // пока открыта строка ввода, клавиши идут в неё, а не на сервер
        if (inputLineVisible() && code != KeyEvent.KEYCODE_MENU && code != KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event);
        }

        if (!overridden && !disabled) {
            if (code == KeyEvent.KEYCODE_MENU) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    openOptionsMenu();
                    return true;
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    handleBack();
                }
                return true;
            }
        }

        if (event.getAction() == KeyEvent.ACTION_MULTIPLE) {
            // Сканер в режиме клавиатуры часто присылает весь штрихкод одним пакетом
            // символов, а не отдельными нажатиями.
            String chars = event.getCharacters();
            if (chars != null && chars.length() > 0) {
                if (inputDiagnostics) {
                    printLocal("пакет символов: \"" + chars + "\"\r\n");
                }
                if (client != null) {
                    client.sendText(chars);
                    if (view != null && view.isScrolledBack()) {
                        view.scrollToBottom();
                    }
                }
                return true;
            }
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            byte[] data = KeyMapper.map(event, config);
            if (data != null && client != null) {
                client.send(data);
                if (data.length == 1 && data[0] >= 0x20 && data[0] < 0x7f) {
                    LogKit.data("->", String.valueOf((char) data[0]));
                }
                if (view != null && view.isScrolledBack()) {
                    view.scrollToBottom();
                }
                return true;
            }
            if (disabled) {
                return true; // клавишу заглушили, чтобы система её не обрабатывала
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            byte[] data = KeyMapper.map(event, config);
            if (data != null || disabled) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void handleBack() {
        if (client != null && client.isConnected()) {
            disconnect(true);
            toast("соединение закрыто");
        } else {
            finish();
        }
    }

    // ------------------------------------------- ввод с экранной клавиатуры

    private boolean inputLineVisible() {
        return input != null && input.getVisibility() == android.view.View.VISIBLE;
    }

    private void toggleInputLine() {
        if (inputLineVisible()) {
            hideInputLine();
        } else {
            showInputLine();
        }
    }

    private void showInputLine() {
        if (input == null) {
            return;
        }
        input.setVisibility(android.view.View.VISIBLE);
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(input, InputMethodManager.SHOW_FORCED);
        }
    }

    private void hideInputLine() {
        if (input == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
        input.setText("");
        input.setVisibility(android.view.View.GONE);
        view.requestFocus();
    }

    /** Отправляет набранное в строке ввода на сервер и очищает её. */
    private void submitInput() {
        if (input == null) {
            return;
        }
        String text = input.getText().toString();
        input.setText("");
        if (view.isScrolledBack()) {
            view.scrollToBottom();
        }
        if (client != null && client.isConnected()) {
            client.sendLine(text);
        } else {
            toast("нет соединения с сервером");
        }
    }

    /** Долгое нажатие по терминалу — то же меню, что по клавише MENU. */
    public void onLongPress() {
        openOptionsMenu();
    }

    // -------------------------------------------------------------------- меню

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_CONNECT, 1, "Подключить");
        menu.add(Menu.NONE, MENU_DISCONNECT, 2, "Отключиться");
        menu.add(Menu.NONE, MENU_RELOAD, 3, "Перечитать настройки");
        menu.add(Menu.NONE, MENU_SHOW, 4, "Показать настройки");
        menu.add(Menu.NONE, MENU_KEYBOARD, 5, "Экранная клавиатура");
        menu.add(Menu.NONE, MENU_FONT_UP, 6, "Шрифт крупнее");
        menu.add(Menu.NONE, MENU_FONT_DOWN, 7, "Шрифт мельче");
        menu.add(Menu.NONE, MENU_CHARSET, 8, "Кодировка: следующая");
        menu.add(Menu.NONE, MENU_DIAG, 9, "Диагностика ввода");
        menu.add(Menu.NONE, MENU_ABOUT, 10, "О программе");
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean connected = client != null && client.isConnected();
        MenuItem item = menu.findItem(MENU_CONNECT);
        if (item != null) {
            item.setEnabled(!connected);
        }
        item = menu.findItem(MENU_DISCONNECT);
        if (item != null) {
            item.setEnabled(connected);
        }
        item = menu.findItem(MENU_KEYBOARD);
        if (item != null) {
            item.setTitle(inputLineVisible() ? "Скрыть строку ввода" : "Экранная клавиатура");
        }
        item = menu.findItem(MENU_CHARSET);
        if (item != null && config != null) {
            item.setTitle("Кодировка: " + config.charsetRecv + " → следующая");
        }
        item = menu.findItem(MENU_FONT_UP);
        if (item != null && view != null) {
            item.setTitle("Шрифт крупнее (сейчас " + view.getFontPx() + " px)");
        }
        item = menu.findItem(MENU_DIAG);
        if (item != null) {
            item.setTitle(inputDiagnostics ? "Диагностика ввода: вкл" : "Диагностика ввода: выкл");
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_CONNECT:
                connect();
                return true;
            case MENU_DISCONNECT:
                disconnect(true);
                toast("соединение закрыто");
                return true;
            case MENU_RELOAD:
                reloadConfig();
                return true;
            case MENU_SHOW:
                printLocal("\r\n" + config.describe() + "\r\n");
                return true;
            case MENU_KEYBOARD:
                toggleInputLine();
                return true;
            case MENU_FONT_UP:
                zoomFont(1);
                return true;
            case MENU_FONT_DOWN:
                zoomFont(-1);
                return true;
            case MENU_CHARSET:
                cycleCharset();
                return true;
            case MENU_DIAG:
                inputDiagnostics = !inputDiagnostics;
                toast(inputDiagnostics ? "диагностика включена" : "диагностика выключена");
                if (inputDiagnostics) {
                    printLocal("\r\n*** диагностика ввода включена\r\n"
                            + "*** нажимайте клавиши ТСД и сканируйте — коды и рассылки появятся здесь\r\n"
                            + "*** клавиши для конфига: строка вида \\\"имя клавиши = код\\\"\r\n\r\n");
                } else {
                    printLocal("\r\n*** диагностика ввода выключена\r\n\r\n");
                }
                return true;
            case MENU_ABOUT:
                printLocal("\r\ntelnet-клиент для терминала сбора данных\r\n"
                        + "настройки: " + CONFIG_PATH + "\r\n"
                        + "сборка под Android 4.0.4 (API 15) без внешних библиотек\r\n"
                        + "шрифт сетки: " + view.getFontPx() + " px\r\n\r\n");
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    // ------------------------------------------------------------ ввод сканера

    private class ScannerReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || config == null || client == null) {
                return;
            }
            if (inputDiagnostics) {
                printLocal("рассылка сканера: action=" + intent.getAction()
                        + " extras{" + describeExtras(intent) + "}\r\n");
            }
            String data = extractBarcode(intent);
            if (data == null || data.length() == 0) {
                if (inputDiagnostics) {
                    printLocal("*** в рассылке нет штрихкода — проверьте intent_extra\r\n");
                }
                return;
            }
            LogKit.log("сканер (intent " + intent.getAction() + "): " + data);
            client.sendText(data);
            client.send(CharsetKit.encode(config.scannerSuffix, config.charsetSend));
            if (view != null) {
                view.scrollToBottom();
            }
        }
    }

    /**
     * Достаёт штрихкод из рассылки сканера. Имена extra у производителей разные,
     * поэтому сначала смотрим настроенное, затем список известных, и лишь потом —
     * «первая строка среди extras» как крайний случай.
     */
    private String extractBarcode(Intent intent) {
        String[] known = new String[] {
                config.scannerIntentExtra,          // из файла настроек
                "value",                            // iData, Urovo, Chainway, Newland
                "scannerdata",
                "data",                             // Honeywell
                "com.symbol.datawedge.data_string", // Zebra DataWedge
                "barcode",
                "barcode_string",
                "decode_data",
                Intent.EXTRA_TEXT,
                "text"
        };
        for (int i = 0; i < known.length; i++) {
            String key = known[i];
            if (key == null || key.length() == 0) {
                continue;
            }
            String value = intent.getStringExtra(key);
            if (value != null && value.length() > 0) {
                return value;
            }
        }
        Bundle extras = intent.getExtras();
        if (extras != null) {
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                if (!(value instanceof String)) {
                    continue;
                }
                // служебные поля сканера штрихкодом не являются
                String lower = key.toLowerCase();
                if (lower.contains("type") || lower.contains("length") || lower.contains("charset")
                        || lower.contains("source") || lower.contains("mode") || lower.contains("count")) {
                    continue;
                }
                String text = (String) value;
                if (text.length() > 0) {
                    return text;
                }
            }
        }
        return null;
    }

    /** Все поля рассылки — для диагностики на реальном ТСД. */
    private String describeExtras(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(key).append('=');
            sb.append(value == null ? "null" : String.valueOf(value));
        }
        return sb.toString();
    }

    /**
     * Надо ли слушать рассылки сканера.
     *
     * Подписываемся, если задан intent_action и не запрещено авто-подхватывание
     * (intent_auto=1, по умолчанию). Так работает и «чистый» режим клавиатуры:
     * если iScan настроен на рассылку, штрихкод всё равно дойдёт. Явное intent=1
     * по-прежнему включает приём; intent_auto=0 его отключает.
     */
    private boolean scannerWanted() {
        return config != null
                && (config.scannerIntent || config.scannerIntentAuto)
                && config.scannerIntentAction != null
                && config.scannerIntentAction.trim().length() > 0;
    }

    private void registerScanner() {
        if (scannerReceiver != null || config == null) {
            return;
        }
        String action = config.scannerIntentAction;
        if (action == null || action.trim().length() == 0) {
            printLocal("*** в конфиге не задан intent_action для сканера\r\n");
            return;
        }
        scannerReceiver = new ScannerReceiver();
        IntentFilter filter = new IntentFilter();
        // Категории фильтру не задаём намеренно: если она указана, устройство обязано
        // прислать рассылку именно с этой категорией. У iData, Zebra и прочих сервисов
        // сканера категории обычно нет — с фильтром «с DEFAULT» приём не сработает.
        String[] actions = action.split("[,;\\s]+");
        int registered = 0;
        for (int i = 0; i < actions.length; i++) {
            String single = actions[i].trim();
            if (single.length() == 0) {
                continue;
            }
            filter.addAction(single);
            registered++;
        }
        if (registered == 0) {
            printLocal("*** в конфиге не задан intent_action для сканера\r\n");
            scannerReceiver = null;
            return;
        }
        registerReceiver(scannerReceiver, filter);
        // В обычном режиме экран не засоряем: строка нужна только для настройки и разбора.
        if (inputDiagnostics || config.scannerIntent) {
            printLocal("*** сканер: слушаю рассылки " + action.trim() + "\r\n");
        }
        LogKit.log("подписка на сканер: " + action);
    }

    private void unregisterScanner() {
        if (scannerReceiver == null) {
            return;
        }
        try {
            unregisterReceiver(scannerReceiver);
        } catch (IllegalArgumentException ignored) {
            // уже снят
        }
        scannerReceiver = null;
    }

    // ------------------------------------------------------------- жизненный цикл

    @Override
    protected void onResume() {
        super.onResume();
        if (config != null && scannerWanted()) {
            registerScanner();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterScanner();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(reconnectTask);
        unregisterScanner();
        if (client != null) {
            client.disconnect();
            client = null;
        }
        LogKit.log("приложение закрыто");
        super.onDestroy();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
