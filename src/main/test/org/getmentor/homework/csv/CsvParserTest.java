package org.getmentor.homework.csv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

public class CsvParserTest {
    private static final String HEADER = "id,full_name,age\n";

    private final CsvParser parser = new CsvParser();

    @TempDir
    Path dir;

    // ---------- модели ----------

    public static class Person {
        private int id;

        @CsvName("full_name")
        private String name;

        private int age;

        public Person() {
        }

        public Person(int id, String name, int age) {
            this.id = id;
            this.name = name;
            this.age = age;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Person p && id == p.id && age == p.age && Objects.equals(name, p.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, name, age);
        }

        @Override
        public String toString() {
            return "Person{" + id + ", " + name + ", " + age + "}";
        }
    }

    public static class AllTypes {
        int i;
        Integer boxedI;
        long l;
        Long boxedL;
        double d;
        Double boxedD;
        boolean b;
        Boolean boxedB;
        String s;

        public AllTypes() {
        }
    }

    public static class Unsupported {
        Object value;

        public Unsupported() {
        }
    }

    public static class NoDefaultConstructor {
        int x;

        public NoDefaultConstructor(int x) {
            this.x = x;
        }
    }

    public static class ClashingNames {
        @CsvName("x")
        int a;
        @CsvName("x")
        int b;

        public ClashingNames() {
        }
    }

    // ---------- форматы ----------

    @ParameterizedTest
    @ValueSource(strings = {"people.csv", "people.csv.gz", "people.gz", "people.zip"})
    void roundTripsEveryFormat(String name) {
        List<Person> people = List.of(new Person(1, "Ivan Ivanov", 25), new Person(2, "Мария 😀", 30));
        parser.saveToCsv(file(name), people, Person.class);
        assertEquals(people, parser.parseFromCsv(file(name), Person.class));
    }

    @Test
    void parsesExampleFromAssignment() throws IOException {
        Files.writeString(dir.resolve("people.csv"), HEADER + "1,Ivan Ivanov,25\n2,Maria Petrova,30\n");
        assertEquals(List.of(new Person(1, "Ivan Ivanov", 25), new Person(2, "Maria Petrova", 30)),
                parser.parseFromCsv(file("people.csv"), Person.class));
    }

    @Test
    void handlesCrlfBomAndBlankLines() throws IOException {
        Files.writeString(dir.resolve("p.csv"), "\uFEFF" + "id,full_name,age\r\n1,Ivan,25\r\n\r\n2,Maria,30\r\n");
        assertEquals(List.of(new Person(1, "Ivan", 25), new Person(2, "Maria", 30)),
                parser.parseFromCsv(file("p.csv"), Person.class));
    }

    // ---------- заголовки и аннотации ----------

    @Test
    void columnsMayBeReordered() throws IOException {
        Files.writeString(dir.resolve("p.csv"), "age,id,full_name\n25,1,Ivan\n");
        assertEquals(List.of(new Person(1, "Ivan", 25)), parser.parseFromCsv(file("p.csv"), Person.class));
    }

    @Test
    void csvNameReplacesFieldNameInHeader() throws IOException {
        parser.saveToCsv(file("p.csv"), List.of(new Person(1, "Ivan", 25)), Person.class);
        assertEquals(HEADER + "1,Ivan,25\n", Files.readString(dir.resolve("p.csv")));
    }

    @Test
    void fieldNameIsNotAcceptedWhenAnnotationRenamesIt() throws IOException {
        Files.writeString(dir.resolve("p.csv"), "id,name,age\n1,Ivan,25\n");
        CsvException ex = assertThrows(CsvException.class, () -> parser.parseFromCsv(file("p.csv"), Person.class));
        assertTrue(ex.getMessage().contains("неизвестный заголовок 'name'"), ex.getMessage());
    }

    @Test
    void rejectsDuplicateHeader() throws IOException {
        Files.writeString(dir.resolve("p.csv"), "id,id,age\n1,1,25\n");
        CsvException ex = assertThrows(CsvException.class, () -> parser.parseFromCsv(file("p.csv"), Person.class));
        assertTrue(ex.getMessage().contains("повторный заголовок 'id'"), ex.getMessage());
    }

    @Test
    void rejectsMissingColumn() throws IOException {
        Files.writeString(dir.resolve("p.csv"), "id,age\n1,25\n");
        CsvException ex = assertThrows(CsvException.class, () -> parser.parseFromCsv(file("p.csv"), Person.class));
        assertTrue(ex.getMessage().contains("нет столбца 'full_name'"), ex.getMessage());
    }

    // ---------- пустые данные ----------

    @Test
    void headerOnlyGivesEmptyList() throws IOException {
        Files.writeString(dir.resolve("p.csv"), HEADER);
        assertEquals(List.of(), parser.parseFromCsv(file("p.csv"), Person.class));
    }

    @Test
    void savingEmptyCollectionWritesHeaderOnly() throws IOException {
        parser.saveToCsv(file("p.csv"), List.of(), Person.class);
        assertEquals(HEADER, Files.readString(dir.resolve("p.csv")));
        assertEquals(List.of(), parser.parseFromCsv(file("p.csv"), Person.class));
    }

    @Test
    void rejectsCompletelyEmptyFile() throws IOException {
        Files.writeString(dir.resolve("p.csv"), "");
        assertThrows(CsvException.class, () -> parser.parseFromCsv(file("p.csv"), Person.class));
    }

    // ---------- значения ----------

    @Test
    void allSupportedTypesRoundTrip() throws Exception {
        AllTypes full = new AllTypes();
        full.i = -7;
        full.boxedI = 8;
        full.l = Long.MAX_VALUE;
        full.boxedL = Long.MIN_VALUE;
        full.d = 1.5;
        full.boxedD = -2.25e10;
        full.b = true;
        full.boxedB = false;
        full.s = "Привет 😀";

        AllTypes nulls = new AllTypes();   // у обёрток и String null

        parser.saveToCsv(file("t.csv"), List.of(full, nulls), AllTypes.class);
        List<AllTypes> read = parser.parseFromCsv(file("t.csv"), AllTypes.class);

        assertEquals(2, read.size());
        assertSameFields(full, read.get(0));
        assertSameFields(nulls, read.get(1));
        assertNull(read.get(1).boxedI);
        assertNull(read.get(1).s);
    }

    @Test
    void invalidNumberReportsLineAndColumn() throws IOException {
        Files.writeString(dir.resolve("p.csv"), HEADER + "1,Ivan,25\n2,Maria,abc\n");
        CsvException ex = assertThrows(CsvException.class, () -> parser.parseFromCsv(file("p.csv"), Person.class));
        assertTrue(ex.getMessage().contains("строка 3"), ex.getMessage());
        assertTrue(ex.getMessage().contains("столбец 'age'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("abc"), ex.getMessage());
    }

    @Test
    void rejectsInvalidBoolean() throws IOException {
        Files.writeString(dir.resolve("t.csv"), "i,boxedI,l,boxedL,d,boxedD,b,boxedB,s\n1,1,1,1,1,1,yes,true,x\n");
        CsvException ex = assertThrows(CsvException.class, () -> parser.parseFromCsv(file("t.csv"), AllTypes.class));
        assertTrue(ex.getMessage().contains("столбец 'b'"), ex.getMessage());
    }

    @Test
    void rejectsEmptyValueForPrimitive() throws IOException {
        Files.writeString(dir.resolve("p.csv"), HEADER + "1,Ivan,\n");
        CsvException ex = assertThrows(CsvException.class, () -> parser.parseFromCsv(file("p.csv"), Person.class));
        assertTrue(ex.getMessage().contains("столбец 'age'"), ex.getMessage());
    }

    @Test
    void rejectsWrongNumberOfCells() throws IOException {
        Files.writeString(dir.resolve("p.csv"), HEADER + "1,Ivan\n");
        CsvException ex = assertThrows(CsvException.class, () -> parser.parseFromCsv(file("p.csv"), Person.class));
        assertTrue(ex.getMessage().contains("строка 2"), ex.getMessage());
    }

    @Test
    void savingValueWithCommaFailsAndLeavesNoFile() {
        assertThrows(CsvException.class,
                () -> parser.saveToCsv(file("p.csv"), List.of(new Person(1, "Ivan, Jr", 2)), Person.class));
        assertFalse(Files.exists(dir.resolve("p.csv")));
    }

    @Test
    void rejectsNullElementOnSave() {
        List<Person> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        assertThrows(CsvException.class, () -> parser.saveToCsv(file("p.csv"), withNull, Person.class));
        assertFalse(Files.exists(dir.resolve("p.csv")));
    }

    // ---------- классы ----------

    @Test
    void rejectsUnsupportedFieldType() {
        assertThrows(CsvException.class, () -> parser.parseFromCsv(file("p.csv"), Unsupported.class));
    }

    @Test
    void rejectsClassWithoutNoArgConstructor() {
        assertThrows(CsvException.class, () -> parser.parseFromCsv(file("p.csv"), NoDefaultConstructor.class));
    }

    @Test
    void rejectsTwoFieldsWithSameHeader() {
        assertThrows(CsvException.class, () -> parser.saveToCsv(file("p.csv"), List.of(), ClashingNames.class));
    }

    // ---------- плохие файлы ----------

    @Test
    void missingFileIsReportedAsCsvException() {
        assertThrows(CsvException.class, () -> parser.parseFromCsv(file("nope.csv"), Person.class));
    }

    @Test
    void invalidUtf8IsRejected() throws IOException {
        Files.write(dir.resolve("p.csv"), new byte[] {'i', 'd', (byte) 0xFF, '\n'});
        assertThrows(CsvException.class, () -> parser.parseFromCsv(file("p.csv"), Person.class));
    }

    @Test
    void garbageGzipIsRejected() throws IOException {
        Files.writeString(dir.resolve("p.csv.gz"), "это не gzip");
        assertThrows(CsvException.class, () -> parser.parseFromCsv(file("p.csv.gz"), Person.class));
    }

    @Test
    void zipWithTwoCsvFilesIsRejected() throws IOException {
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(dir.resolve("p.zip")))) {
            for (String name : List.of("a.csv", "b.csv")) {
                zip.putNextEntry(new java.util.zip.ZipEntry(name));
                zip.write(HEADER.getBytes());
                zip.closeEntry();
            }
        }
        CsvException ex = assertThrows(CsvException.class, () -> parser.parseFromCsv(file("p.zip"), Person.class));
        assertTrue(ex.getMessage().contains("больше одной записи"), ex.getMessage());
    }

    // ---------- helpers ----------

    private String file(String name) {
        return dir.resolve(name).toString();
    }

    private static void assertSameFields(Object expected, Object actual) throws IllegalAccessException {
        for (Field field : expected.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            assertEquals(field.get(expected), field.get(actual), field.getName());
        }
    }
}
