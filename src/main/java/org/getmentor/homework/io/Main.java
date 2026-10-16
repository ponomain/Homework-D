package org.getmentor.homework.io;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

public class Main {
    private Main() {}

    static void main(String[] args) {
        System.exit(run(args, System.err));
    }

    public static int run(String[] args, PrintStream err) {
        if (args.length != 3 || !(args[0].equals("pack") || args[0].equals("unpack"))) {
            err.println("Использование:\n  pack <папка> <архив>\n  unpack <архив> <папка>");
            return 2;
        }
        try {
            if (args[0].equals("pack")) Packer.pack(Path.of(args[1]), Path.of(args[2]));
            else Unpacker.unpack(Path.of(args[1]), Path.of(args[2]));
            return 0;
        } catch (IOException | RuntimeException e) {
            err.println("Ошибка: " + e.getMessage());
            return 1;
        }
    }
}
