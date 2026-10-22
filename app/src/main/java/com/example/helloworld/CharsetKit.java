package com.example.helloworld;

import java.io.UnsupportedEncodingException;

/**
 * Работа с кодировками: складские системы на 1С/Unix часто гоняют OEM-866 или
 * windows-1251, поэтому имена кодировок и преобразование байтов собраны здесь.
 */
public final class CharsetKit {

    private CharsetKit() {
    }

    /** Приводит имя кодировки из конфига к имени, понятному Java/Android. */
    public static String normalize(String name) {
        if (name == null) {
            return "UTF-8";
        }
        String t = name.trim();
        if (t.length() == 0) {
            return "UTF-8";
        }
        String u = t.toUpperCase().replace('_', '-').replace(" ", "");
        if (u.equals("CP866") || u.equals("IBM866") || u.equals("866") || u.equals("DOS") || u.equals("OEM")) {
            return "Cp866";
        }
        if (u.equals("CP1251") || u.equals("WIN1251") || u.equals("WINDOWS-1251") || u.equals("1251")) {
            return "windows-1251";
        }
        if (u.equals("KOI8R") || u.equals("KOI8-R") || u.equals("KOI8")) {
            return "KOI8-R";
        }
        if (u.equals("UTF8") || u.equals("UTF-8") || u.equals("UNICODE")) {
            return "UTF-8";
        }
        if (u.equals("CP1252") || u.equals("WIN1252")) {
            return "windows-1252";
        }
        if (u.equals("LATIN1") || u.equals("ISO8859-1") || u.equals("ISO-8859-1")) {
            return "ISO-8859-1";
        }
        if (u.equals("ASCII") || u.equals("US-ASCII")) {
            return "US-ASCII";
        }
        return t;
    }

    public static byte[] encode(String text, String charset) {
        if (text == null) {
            return new byte[0];
        }
        try {
            return text.getBytes(charset);
        } catch (UnsupportedEncodingException e) {
            return text.getBytes();
        }
    }

    public static String decode(byte[] data, int offset, int length, String charset) {
        try {
            return new String(data, offset, length, charset);
        } catch (UnsupportedEncodingException e) {
            return new String(data, offset, length);
        }
    }

    public static String decode(byte[] data, String charset) {
        return decode(data, 0, data.length, charset);
    }
}
