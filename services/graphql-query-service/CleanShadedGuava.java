import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;

/**
 * Strips shaded Guava metadata (pom.properties and pom.xml) from graphql-java dependencies
 * in the local Maven repository prior to Spring Boot repackaging.
 *
 * com.graphql-java:graphql-java shades Guava internally under graphql.com.google.common.*,
 * but vendor releases retain META-INF/maven/com.google.guava/guava/pom.properties with a legacy
 * Guava version (32.1.2-jre), triggering false-positive Trivy scanner alerts (CVE-2026-102554).
 * SwiftDrop manages the actual direct Guava classpath dependency at 33.7.2-jre.
 */
public final class CleanShadedGuava {

    public static void main(String[] args) {
        Path root = Paths.get(System.getProperty("user.home"), ".m2", "repository", "com", "graphql-java");
        if (!Files.exists(root)) {
            return;
        }
        try {
            Files.walk(root)
                .filter(p -> p.toString().endsWith(".jar") && p.getFileName().toString().startsWith("graphql-java-"))
                .forEach(CleanShadedGuava::stripMetadata);
        } catch (Exception e) {
            System.err.println("Notice: Shaded Guava cleanup encountered non-fatal error: " + e.getMessage());
        }
    }

    private static void stripMetadata(Path jarPath) {
        try {
            Map<String, String> env = Collections.emptyMap();
            URI uri = URI.create("jar:" + jarPath.toUri());
            try (FileSystem fs = FileSystems.newFileSystem(uri, env)) {
                Path pomProps = fs.getPath("/META-INF/maven/com.google.guava/guava/pom.properties");
                if (Files.exists(pomProps)) {
                    Files.delete(pomProps);
                }
                Path pomXml = fs.getPath("/META-INF/maven/com.google.guava/guava/pom.xml");
                if (Files.exists(pomXml)) {
                    Files.delete(pomXml);
                }
            }
            System.out.println("Stripped false-positive shaded Guava metadata from " + jarPath.getFileName());
        } catch (Exception e) {
            System.err.println("Warning: Unable to inspect " + jarPath + ": " + e.getMessage());
        }
    }
}
