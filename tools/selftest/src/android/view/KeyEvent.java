package android.view;

/**
 * Минимальная заглушка KeyEvent — нужна ТОЛЬКО для прогона логики конфига на JVM.
 * В приложение не попадает (лежит во временном каталоге теста).
 */
public class KeyEvent {

    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MULTIPLE = 2;

    public static final int META_CTRL_ON = 0x1000;
    public static final int META_CTRL_LEFT_ON = 0x2000;
    public static final int META_CTRL_RIGHT_ON = 0x4000;

    public static final int KEYCODE_ENTER = 66;
    public static final int KEYCODE_DPAD_CENTER = 23;
    public static final int KEYCODE_NUMPAD_ENTER = 160;
    public static final int KEYCODE_DEL = 67;
    public static final int KEYCODE_FORWARD_DEL = 112;
    public static final int KEYCODE_TAB = 61;
    public static final int KEYCODE_ESCAPE = 111;
    public static final int KEYCODE_DPAD_UP = 19;
    public static final int KEYCODE_DPAD_DOWN = 20;
    public static final int KEYCODE_DPAD_LEFT = 21;
    public static final int KEYCODE_DPAD_RIGHT = 22;
    public static final int KEYCODE_MOVE_HOME = 122;
    public static final int KEYCODE_MOVE_END = 123;
    public static final int KEYCODE_PAGE_UP = 92;
    public static final int KEYCODE_PAGE_DOWN = 93;
    public static final int KEYCODE_INSERT = 124;
    public static final int KEYCODE_F1 = 131;
    public static final int KEYCODE_F2 = 132;
    public static final int KEYCODE_F3 = 133;
    public static final int KEYCODE_F4 = 134;
    public static final int KEYCODE_F5 = 135;
    public static final int KEYCODE_F6 = 136;
    public static final int KEYCODE_F7 = 137;
    public static final int KEYCODE_F8 = 138;
    public static final int KEYCODE_F9 = 139;
    public static final int KEYCODE_F10 = 140;
    public static final int KEYCODE_F11 = 141;
    public static final int KEYCODE_F12 = 142;
    public static final int KEYCODE_MENU = 82;
    public static final int KEYCODE_SEARCH = 84;
    public static final int KEYCODE_POWER = 26;
    public static final int KEYCODE_CAMERA = 27;
    public static final int KEYCODE_SHIFT_LEFT = 59;
    public static final int KEYCODE_SHIFT_RIGHT = 60;
    public static final int KEYCODE_ALT_LEFT = 57;
    public static final int KEYCODE_ALT_RIGHT = 58;
    public static final int KEYCODE_CTRL_LEFT = 113;
    public static final int KEYCODE_CTRL_RIGHT = 114;
    public static final int KEYCODE_CAPS_LOCK = 115;
    public static final int KEYCODE_SYM = 63;
    public static final int KEYCODE_FUNCTION = 119;
    public static final int KEYCODE_NUM_LOCK = 143;
    public static final int KEYCODE_VOLUME_UP = 24;
    public static final int KEYCODE_VOLUME_DOWN = 25;
    public static final int KEYCODE_BACK = 4;
    public static final int KEYCODE_A = 29;
    public static final int KEYCODE_Z = 54;
    public static final int KEYCODE_0 = 7;
    public static final int KEYCODE_9 = 16;
    public static final int KEYCODE_SPACE = 62;

    private final int keyCode;
    private final int metaState;
    private final int unicode;
    private final int repeatCount;

    public KeyEvent(int keyCode, int unicode, int metaState, int repeatCount) {
        this.keyCode = keyCode;
        this.unicode = unicode;
        this.metaState = metaState;
        this.repeatCount = repeatCount;
    }

    public int getKeyCode() {
        return keyCode;
    }

    public int getUnicodeChar(int meta) {
        return unicode;
    }

    public int getMetaState() {
        return metaState;
    }

    public int getRepeatCount() {
        return repeatCount;
    }

    public boolean isShiftPressed() {
        return (metaState & 1) != 0;
    }
}
