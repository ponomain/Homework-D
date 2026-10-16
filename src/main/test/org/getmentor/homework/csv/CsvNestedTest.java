package org.getmentor.homework.csv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CsvNestedTest {
    private static final String FULL_HEADER = "id,full_name,home.street,home.zip_code,home.geo.lat,home.geo.lon,"
            + "work.street,work.zip_code,work.geo.lat,work.geo.lon";

    private final CsvParser parser = new CsvParser();

    @TempDir
    Path dir;

    // ---------- модели: три уровня вложенности ----------

    public static class Geo {
        double lat;
        double lon;

        public Geo() {
        }
    }

    public static class Address {
        String street;
        @CsvName("zip_code")
        String zip;
        Geo geo;

        public Address() {
        }
    }

    public static class Customer {
        int id;
        @CsvName("full_name")
        String name;
        @CsvName("home")
        Address address;
        Address work;

        public Customer() {
        }
    }

    public static class Node {
        int value;
        Node next;

        public Node() {
        }
    }

    public static class NoDefault {
        int x;

        public NoDefault(int x) {
            this.x = x;
        }
    }

    public static class HoldsNoDefault {
        NoDefault inner;

        public HoldsNoDefault() {
        }
    }

    public static class Inner {
        int b;

        public Inner() {
        }
    }

    public static class ClashingPaths {
        @CsvName("a.b")
        int x;
        Inner a;

        public ClashingPaths() {
        }
    }

    public static class UnsupportedInside {
        public static class Bad {
            Object value;

            public Bad() {
            }
        }

        Bad bad;

        public UnsupportedInside() {
        }
    }

    // ---------- тесты ----------

    @Test
    void headersAreBuiltFromPathWithAnnotationsAtEveryLevel() throws IOException {
        parser.saveToCsv(file("c.csv"), List.of(sampleCustomer()), Customer.class);
        String firstLine = Files.readString(dir.resolve("c.csv")).split("\n")[0];
        assertEquals(FULL_HEADER, firstLine);
    }

    @Test
    void roundTripsThreeLevelsInEveryFormat() throws IllegalAccessException {
        for (String name : List.of("c.csv", "c.csv.gz", "c.zip")) {
            Customer expected = sampleCustomer();
            parser.saveToCsv(file(name), List.of(expected), Customer.class);
            List<Customer> read = parser.parseFromCsv(file(name), Customer.class);
            assertEquals(1, read.size(), name);
            assertDeepEquals(expected, read.get(0), name);
        }
    }

    @Test
    void nullNestedObjectsRoundTripAsNull() throws IllegalAccessException {
        Customer expected = new Customer();
        expected.id = 2;
        expected.name = "X";
        expected.work = new Address();      // home == null, у work нет geo и zip
        expected.work.street = "S";

        parser.saveToCsv(file("c.csv"), List.of(expected), Customer.class);
        Customer actual = parser.parseFromCsv(file("c.csv"), Customer.class).get(0);

        assertNull(actual.address);
        assertNotNull(actual.work);
        assertNull(actual.work.geo);
        assertNull(actual.work.zip);
        assertDeepEquals(expected, actual, "customer");
    }

    @Test
    void nestedColumnsMayBeReordered() throws IOException, IllegalAccessException {
        Customer expected = sampleCustomer();
        parser.saveToCsv(file("c.csv"), List.of(expected), Customer.class);
        String reversed = reverseColumns(Files.readString(dir.resolve("c.csv")));
        Files.writeString(dir.resolve("r.csv"), reversed);

        assertDeepEquals(expected, parser.parseFromCsv(file("r.csv"), Customer.class).get(0), "customer");
    }

    @Test
    void emptyPrimitiveInsideExistingNestedObjectIsError() throws IOException {
        // home.geo.lat заполнен, home.geo.lon пустой: geo существует, значит пустой примитив это ошибка
        Files.writeString(dir.resolve("c.csv"), FULL_HEADER + "\n1,Ivan,S,Z,1.5" + ",".repeat(5) + "\n");
        CsvException ex = assertThrows(CsvException.class, () -> parser.parseFromCsv(file("c.csv"), Customer.class));
        assertTrue(ex.getMessage().contains("home.geo.lon"), ex.getMessage());
        assertTrue(ex.getMessage().contains("строка 2"), ex.getMessage());
    }

    @Test
    void invalidValueInNestedColumnNamesFullHeader() throws IOException {
        Files.writeString(dir.resolve("c.csv"), FULL_HEADER + "\n1,Ivan,S,Z,abc,2.0" + ",".repeat(4) + "\n");
        CsvException ex = assertThrows(CsvException.class, () -> parser.parseFromCsv(file("c.csv"), Customer.class));
        assertTrue(ex.getMessage().contains("столбец 'home.geo.lat'"), ex.getMessage());
    }

    @Test
    void rejectsCyclicClasses() {
        CsvException ex = assertThrows(CsvException.class,
                () -> parser.saveToCsv(file("n.csv"), List.of(), Node.class));
        assertTrue(ex.getMessage().contains("циклическ"), ex.getMessage());
    }

    @Test
    void rejectsNestedClassWithoutNoArgConstructor() {
        assertThrows(CsvException.class, () -> parser.saveToCsv(file("h.csv"), List.of(), HoldsNoDefault.class));
    }

    @Test
    void rejectsHeadersThatCollideAfterFlattening() {
        CsvException ex = assertThrows(CsvException.class,
                () -> parser.saveToCsv(file("x.csv"), List.of(), ClashingPaths.class));
        assertTrue(ex.getMessage().contains("a.b"), ex.getMessage());
    }

    @Test
    void rejectsUnsupportedTypeInsideNestedClass() {
        CsvException ex = assertThrows(CsvException.class,
                () -> parser.saveToCsv(file("u.csv"), List.of(), UnsupportedInside.class));
        assertTrue(ex.getMessage().contains("value"), ex.getMessage());
    }

    // ---------- helpers ----------

    private String file(String name) {
        return dir.resolve(name).toString();
    }

    private static Customer sampleCustomer() {
        Customer customer = new Customer();
        customer.id = 1;
        customer.name = "Мария 😀";
        customer.address = address("Lenina 1", "101000", 55.75, 37.62);
        customer.work = address("Tverskaya 7", null, -1.5, 0.0);
        return customer;
    }

    private static Address address(String street, String zip, double lat, double lon) {
        Address address = new Address();
        address.street = street;
        address.zip = zip;
        address.geo = new Geo();
        address.geo.lat = lat;
        address.geo.lon = lon;
        return address;
    }

    private static String reverseColumns(String csv) {
        StringBuilder sb = new StringBuilder();
        for (String line : csv.split("\n")) {
            List<String> cells = new ArrayList<>(Arrays.asList(line.split(",", -1)));
            Collections.reverse(cells);
            sb.append(String.join(",", cells)).append('\n');
        }
        return sb.toString();
    }

    private static void assertDeepEquals(Object expected, Object actual, String path) throws IllegalAccessException {
        if (expected == null) {
            assertNull(actual, path);
            return;
        }
        assertNotNull(actual, path);
        if (expected instanceof String || expected instanceof Number || expected instanceof Boolean) {
            assertEquals(expected, actual, path);
            return;
        }
        for (Field field : expected.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            assertDeepEquals(field.get(expected), field.get(actual), path + "." + field.getName());
        }
    }
}
