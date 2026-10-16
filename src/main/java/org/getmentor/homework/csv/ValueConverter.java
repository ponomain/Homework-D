package org.getmentor.homework.csv;

import com.sun.jdi.Value;

import java.util.Objects;
import java.util.Set;

/** Преобразование ячейки CSV в значение поля и обратно для поддерживаемых типов. */
final class ValueConverter {

    private static final Set<Class<?>> SUPPORTED = Set.of(
            int.class, Integer.class,
            long.class, Long.class,
            double.class, Double.class,
            boolean.class, Boolean.class,
            String.class
    );

    private ValueConverter() {}

    static boolean isSupported(Class<?> type) {
        return SUPPORTED.contains(type);
    }

    /** Пустая ячейка: для обёрток и String это null, для примитива это ошибка. */
    static Object parse(String text, Class<?> type) {
        if (!isSupported(type)) {
            throw new IllegalArgumentException("неподдерживаемый тип " + type.getName());
        }
        if(text.isEmpty()) {
            if (type.isPrimitive()) {
                throw new IllegalArgumentException("пустое значение недопустимо для примитива " + type.getSimpleName());
            }
            return null;
        }
        if (type == String.class) {
            return text;
        }
        if (type == int.class || type == Integer.class) {
            return Integer.parseInt(text);
        }
        if (type == long.class || type == Long.class) {
            return Long.parseLong(text);
        }
        if (type == double.class || type == Double.class) {
            return Double.parseDouble(text);
        }
        return parseBoolean(text);
    }

    private static boolean parseBoolean(String text) {
        if (text.equalsIgnoreCase("true")) {
            return true;
        }
        if (text.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException("ожидалось true или false, получено '" + text + "'");
    }

    /** В базовой версии запятые и переводы строк в значениях не поддерживаются, поэтому это ошибка. */
    static String format(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (text.indexOf(',') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("значение содержит запятую или перевод строки: '" + text + "'");
        }
        return text;
    }
}
