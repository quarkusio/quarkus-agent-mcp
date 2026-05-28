package io.quarkus.agent.mcp;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LogFileReader {

    private LogFileReader() {
    }

    static List<String> readTail(Path file, int lineCount) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            if (length == 0) {
                return List.of();
            }

            List<String> result = new ArrayList<>();
            long pos = length - 1;

            raf.seek(pos);
            if (raf.readByte() == '\n') {
                pos--;
            }

            while (pos >= 0 && result.size() < lineCount) {
                raf.seek(pos);
                if (raf.readByte() == '\n') {
                    result.add(readLineAt(raf, pos + 1));
                }
                pos--;
            }
            if (result.size() < lineCount) {
                result.add(readLineAt(raf, 0));
            }

            Collections.reverse(result);
            return result;
        }
    }

    private static String readLineAt(RandomAccessFile raf, long start) throws IOException {
        raf.seek(start);
        String line = raf.readLine();
        return line != null ? new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8) : "";
    }
}
