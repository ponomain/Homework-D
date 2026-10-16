package org.getmentor.homework.csv;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

public class CsvParser {

    private static final String SEPARATOR = ",";
    private static final String NEW_LINE = "\n";

    /**
     * Столбец CSV: заголовок и путь по полям от корневого объекта до листового значения.
     * Для обычного поля путь состоит из одного элемента.
     */
    private record Column(String header, List<Field> path) {
        Field leaf() {
            return path.getLast();
        }
    }

    // ---------- чтение ----------

    public <T> List<T> parseFromCsv(String filename, Class<T> klass) {
        Map<Class<?>, Constructor<?>> constructors = new HashMap<>();
        List<Column> columns = columnsOf(klass, constructors);
        Constructor<T> constructor = noArgConstructor(klass);
        try (BufferedReader reader = CsvStreams.openReader(filename)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new CsvException(filename + ": файл пуст, ожидалась строка заголовков");
            }
            Column[] byIndex = mapHeaders(filename, stripBom(headerLine), columns);

            List<T> result = new ArrayList<>();
            long lineNumber = 1;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isEmpty()) {
                    continue;
                }
                result.add(parseRow(filename + ", строка " + lineNumber, line, byIndex, constructor, constructors));
            }
            return result;
        } catch (IOException e) {
            throw new CsvException(filename + ": ошибка чтения: " + e.getMessage(), e);
        }
    }

    /** Сопоставляет столбцы файла с полями по заголовкам: result[i] это столбец модели для i-го столбца файла. */
    private static Column[] mapHeaders(String filename, String headerLine, List<Column> columns) {
        Map<String, Column> byHeader = new HashMap<>();
        for (Column column : columns) {
            byHeader.put(column.header(), column);
        }

        String[] headers = headerLine.split(SEPARATOR, -1);
        Column[] byIndex = new Column[headers.length];
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < headers.length; i++) {
            String header = headers[i];
            if (!seen.add(header)) {
                throw new CsvException(filename + ", строка 1: повторный заголовок '" + header + "'");
            }
            Column column = byHeader.get(header);
            if (column == null) {
                throw new CsvException(filename + ", строка 1: неизвестный заголовок '" + header + "'");
            }
            byIndex[i] = column;
        }
        for (Column column : columns) {
            if (!seen.contains(column.header())) {
                throw new CsvException(filename + ", строка 1: нет столбца '" + column.header()
                        + "' для поля " + column.leaf().getName());
            }
        }
        return byIndex;
    }

    private static <T> T parseRow(String where, String line, Column[] byIndex,
                                  Constructor<T> constructor, Map<Class<?>, Constructor<?>> constructors) {
        String[] cells = line.split(SEPARATOR, -1);
        if (cells.length != byIndex.length) {
            throw new CsvException(where + ": ожидалось значений " + byIndex.length + ", получено " + cells.length);
        }
        T root = instantiate(where, constructor);

        // Пустые ячейки примитивов проверяем после остальных: они ошибка только если их объект не null.
        List<Column> emptyPrimitives = new ArrayList<>();
        for (int i = 0; i < cells.length; i++) {
            Column column = byIndex[i];
            Class<?> type = column.leaf().getType();
            try {
                if (cells[i].isEmpty()) {
                    if (type.isPrimitive()) {
                        emptyPrimitives.add(column);
                    }
                    continue;
                }
                setValue(root, column, ValueConverter.parse(cells[i], type), constructors);
            } catch (IllegalArgumentException e) {
                throw new CsvException(where + ", столбец '" + column.header() + "': " + e.getMessage(), e);
            } catch (ReflectiveOperationException e) {
                throw new CsvException(where + ", столбец '" + column.header()
                        + "': не удалось создать вложенный объект или записать поле", e);
            }
        }

        for (Column column : emptyPrimitives) {
            try {
                if (parentOf(root, column) != null) {
                    ValueConverter.parse("", column.leaf().getType());   // бросает ошибку про примитив
                }
            } catch (IllegalArgumentException e) {
                throw new CsvException(where + ", столбец '" + column.header() + "': " + e.getMessage(), e);
            } catch (IllegalAccessException e) {
                throw new CsvException(where + ", столбец '" + column.header() + "': нет доступа к полю", e);
            }
        }
        return root;
    }

    private static <T> T instantiate(String where, Constructor<T> constructor) {
        try {
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new CsvException(where + ": не удалось создать объект "
                    + constructor.getDeclaringClass().getName(), e);
        }
    }

    /** Записывает значение листового поля, создавая по пути недостающие вложенные объекты. */
    private static void setValue(Object root, Column column, Object value,
                                 Map<Class<?>, Constructor<?>> constructors) throws ReflectiveOperationException {
        Object target = root;
        List<Field> path = column.path();
        for (int i = 0; i < path.size() - 1; i++) {
            Field field = path.get(i);
            Object next = field.get(target);
            if (next == null) {
                next = constructors.get(field.getType()).newInstance();
                field.set(target, next);
            }
            target = next;
        }
        column.leaf().set(target, value);
    }

    /** Объект, которому принадлежит листовое поле столбца, или null, если вложенный объект по пути равен null. */
    private static Object parentOf(Object root, Column column) throws IllegalAccessException {
        Object target = root;
        List<Field> path = column.path();
        for (int i = 0; i < path.size() - 1 && target != null; i++) {
            target = path.get(i).get(target);
        }
        return target;
    }

    private static String stripBom(String line) {
        return line.startsWith("\uFEFF") ? line.substring(1) : line;
    }

    // ---------- запись ----------

    public <T> void saveToCsv(String filename, Collection<T> collection, Class<T> klass) {
        Objects.requireNonNull(collection, "collection");
        List<Column> columns = columnsOf(klass, new HashMap<>());
        CsvStreams.write(filename, writer -> {
            StringJoiner header = new StringJoiner(SEPARATOR);
            for (Column column : columns) {
                header.add(column.header());
            }
            writer.write(header.toString());
            writer.write(NEW_LINE);

            long row = 0;
            for (T item : collection) {
                row++;
                if (item == null) {
                    throw new CsvException(filename + ": элемент коллекции №" + row + " равен null");
                }
                writer.write(rowOf(filename + ", запись " + row, item, columns));
                writer.write(NEW_LINE);
            }
        });
    }

    private static String rowOf(String where, Object item, List<Column> columns) {
        StringJoiner row = new StringJoiner(SEPARATOR);
        for (Column column : columns) {
            try {
                Object parent = parentOf(item, column);
                row.add(ValueConverter.format(parent == null ? null : column.leaf().get(parent)));
            } catch (IllegalArgumentException e) {
                throw new CsvException(where + ", столбец '" + column.header() + "': " + e.getMessage(), e);
            } catch (IllegalAccessException e) {
                throw new CsvException(where + ", столбец '" + column.header() + "': нет доступа к полю", e);
            }
        }
        return row.toString();
    }

    // ---------- reflection ----------

    /** Плоский список столбцов класса. Заодно запоминает конструкторы вложенных классов. */
    private static List<Column> columnsOf(Class<?> klass, Map<Class<?>, Constructor<?>> constructors) {
        List<Column> columns = new ArrayList<>();
        collectColumns(klass, "", List.of(), new HashSet<>(), constructors, columns);

        Set<String> headers = new HashSet<>();
        for (Column column : columns) {
            if (!headers.add(column.header())) {
                throw new CsvException(klass.getName() + ": заголовок '" + column.header()
                        + "' получился у нескольких полей");
            }
        }
        return columns;
    }

    private static void collectColumns(Class<?> klass, String prefix, List<Field> parentPath,
                                       Set<Class<?>> visiting, Map<Class<?>, Constructor<?>> constructors,
                                       List<Column> out) {
        if (!visiting.add(klass)) {
            throw new CsvException(klass.getName() + ": циклическая вложенность классов не поддерживается");
        }
        for (Field field : klass.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()) {
                continue;
            }
            String where = klass.getName() + "." + field.getName();
            CsvName annotation = field.getAnnotation(CsvName.class);
            String name = annotation != null ? annotation.value() : field.getName();
            if (name.isBlank() || name.contains(SEPARATOR)) {
                throw new CsvException(where + ": недопустимое имя столбца '" + name + "'");
            }
            field.setAccessible(true);

            List<Field> path = new ArrayList<>(parentPath);
            path.add(field);
            String header = prefix + name;
            Class<?> type = field.getType();

            if (ValueConverter.isSupported(type)) {
                out.add(new Column(header, List.copyOf(path)));
            } else if (isNestedClass(type)) {
                constructors.computeIfAbsent(type, t -> noArgConstructor(t));
                collectColumns(type, header + ".", path, visiting, constructors, out);
            } else {
                throw new CsvException(where + ": неподдерживаемый тип поля " + type.getName());
            }
        }
        visiting.remove(klass);
    }

    /** Пользовательский класс, поля которого разворачиваются в отдельные столбцы. */
    private static boolean isNestedClass(Class<?> type) {
        return !type.isPrimitive() && !type.isArray() && !type.isInterface() && !type.isEnum()
                && !type.getPackageName().startsWith("java");
    }

    private static <T> Constructor<T> noArgConstructor(Class<T> klass) {
        try {
            Constructor<T> constructor = klass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException e) {
            throw new CsvException(klass.getName() + ": нужен конструктор без аргументов", e);
        }
    }
}