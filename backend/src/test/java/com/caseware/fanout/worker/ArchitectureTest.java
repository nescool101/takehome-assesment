package com.caseware.fanout.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Architecture rule: the worker core is framework/cloud-free; adapters plug in via Ports. */
class ArchitectureTest {
    @Test
    void coreHasNoCloudOrFrameworkImports() throws IOException {
        Path core = Path.of("src/main/java/com/caseware/fanout/worker");
        try (Stream<Path> files = Files.walk(core)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                assertFalse(src.matches("(?s).*import\\s+(software\\.amazon|com\\.amazonaws|org\\.springframework)\\..*"),
                        f + " imports a cloud/framework class");
            }
        }
    }
}
