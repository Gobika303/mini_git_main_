package com.minigit.api;

import com.minigit.core.Index;
import com.minigit.core.Repository;
import com.minigit.core.RepositoryManager;
import com.minigit.storage.FileObjectStore;
import com.minigit.util.HashUtil;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

@WebServlet("/api/status")
public class StatusServlet extends HttpServlet {

    @Override
    protected void doGet(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        response.setHeader(
                "Access-Control-Allow-Origin",
                "http://localhost:5173"
        );

        Path repositoryPath = RepositoryManager.getRepositoryPath();
        Repository repository = new Repository(repositoryPath);

        if (!repository.isInitialized()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);

            response.getWriter().write("""
                    {
                      "initialized": false,
                      "error": "MiniGit repository is not initialized"
                    }
                    """);

            return;
        }

        Path metadata = repository.getMetadataDirectory();
        Path workingDirectory = repository.getWorkingDirectory()
                .toAbsolutePath()
                .normalize();

        Index index = new Index(metadata);
        Map<String, String> stagedFiles = index.getEntries();

        FileObjectStore objectStore = new FileObjectStore(metadata);

        /*
         * Read the current HEAD commit and reconstruct its complete
         * committed snapshot.
         */
        Map<String, String> committedFiles = readHeadSnapshot(
                metadata,
                objectStore
        );

        Set<String> discoveredFiles = new HashSet<>();

        try (Stream<Path> paths = Files.walk(workingDirectory)) {

            paths.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(metadata))
                    .filter(path -> !path.toString().contains("/target/"))
                    .filter(path -> !path.toString().contains("/.git/"))
                    .filter(path -> !path.toString().contains("/node_modules/"))
                    .forEach(path -> discoveredFiles.add(
                            workingDirectory.relativize(path)
                                    .toString()
                                    .replace('\\', '/')
                    ));
        }

        Set<String> allPaths = new HashSet<>();
        allPaths.addAll(discoveredFiles);
        allPaths.addAll(stagedFiles.keySet());
        allPaths.addAll(committedFiles.keySet());

        List<String> filesJson = new ArrayList<>();

        for (String file : allPaths.stream().sorted().toList()) {

            boolean existsInWorkingDirectory =
                    discoveredFiles.contains(file);

            String committedHash = committedFiles.get(file);
            String stagedHash = stagedFiles.get(file);

            if (!existsInWorkingDirectory) {

                // A committed or staged file is no longer on disk.
                if (committedHash != null || stagedHash != null) {
                    filesJson.add(fileJson(file, "MISSING"));
                }

                continue;
            }

            Path filePath = workingDirectory.resolve(file).normalize();

            if (!filePath.startsWith(workingDirectory)) {
                continue;
            }

            String currentHash = HashUtil.sha256(
                    Files.readAllBytes(filePath)
            );

            String status = null;

            if (stagedHash != null) {

                /*
                 * A staged version different from HEAD represents
                 * a staged addition or modification.
                 */
                if (!stagedHash.equals(committedHash)) {

                    status = currentHash.equals(stagedHash)
                            ? "STAGED"
                            : "MODIFIED";

                } else if (!currentHash.equals(committedHash)) {

                    // Staged content matches HEAD, but working file changed.
                    status = "MODIFIED";
                }

            } else if (committedHash != null) {

                // Tracked file without staged changes.
                if (!currentHash.equals(committedHash)) {
                    status = "MODIFIED";
                }

            } else {

                // File is neither committed nor staged.
                status = "UNTRACKED";
            }

            // Clean files are omitted from the status list.
            if (status != null) {
                filesJson.add(fileJson(file, status));
            }
        }

        Path headFile = metadata.resolve("HEAD");
        String branch = "unknown";

        if (Files.isRegularFile(headFile)) {

            String head = Files.readString(
                    headFile,
                    StandardCharsets.UTF_8
            ).trim();

            if (head.startsWith("ref: refs/heads/")) {
                branch = head.substring("ref: refs/heads/".length());
            }
        }

        String files = String.join(",", filesJson);

        String json = """
                {
                  "initialized": true,
                  "path": "%s",
                  "currentBranch": "%s",
                  "clean": %s,
                  "files": [%s]
                }
                """.formatted(
                escapeJson(repositoryPath.toString()),
                escapeJson(branch),
                filesJson.isEmpty(),
                files
        );

        response.getWriter().write(json);
    }

    /**
     * Reconstruct the current branch's committed file snapshot.
     *
     * Older MiniGit commits contain only staged changes. Newer commits
     * contain complete snapshots. Walking from newest to oldest and
     * keeping the first occurrence of each path supports both formats.
     */
    private Map<String, String> readHeadSnapshot(
            Path metadata,
            FileObjectStore objectStore
    ) throws IOException {

        Path headFile = metadata.resolve("HEAD");

        if (!Files.isRegularFile(headFile)) {
            return Map.of();
        }

        String head = Files.readString(
                headFile,
                StandardCharsets.UTF_8
        ).trim();

        if (!head.startsWith("ref: ")) {
            throw new IOException("Invalid HEAD file.");
        }

        String reference = head.substring(5);

        Path refsDirectory = metadata.resolve("refs")
                .toAbsolutePath()
                .normalize();

        Path branchFile = metadata.resolve(reference)
                .toAbsolutePath()
                .normalize();

        if (!branchFile.startsWith(refsDirectory)) {
            throw new IOException("Invalid branch reference in HEAD.");
        }

        if (!Files.isRegularFile(branchFile)) {
            return Map.of();
        }

        String currentHash = Files.readString(
                branchFile,
                StandardCharsets.UTF_8
        ).trim();

        Map<String, String> snapshot = new TreeMap<>();
        Set<String> visited = new HashSet<>();

        while (!currentHash.isEmpty()) {

            if (!currentHash.matches("[a-fA-F0-9]{64}")) {
                throw new IOException(
                        "Invalid commit hash: " + currentHash
                );
            }

            if (!visited.add(currentHash)) {
                throw new IOException(
                        "Circular commit history detected."
                );
            }

            byte[] objectBytes = objectStore.read(currentHash);

            String[] lines = new String(
                    objectBytes,
                    StandardCharsets.UTF_8
            ).split("\n", -1);

            if (lines.length == 0
                    || !"MINIGIT_COMMIT_V1".equals(lines[0])) {
                throw new IOException(
                        "Invalid commit object: " + currentHash
                );
            }

            String parentHash = null;
            int fileCount = -1;
            int lineIndex = 1;

            while (lineIndex < lines.length) {

                String line = lines[lineIndex++];

                if (line.startsWith("parent=")) {
                    parentHash = line.substring("parent=".length());

                } else if (line.startsWith("files=")) {

                    try {
                        fileCount = Integer.parseInt(
                                line.substring("files=".length())
                        );
                    } catch (NumberFormatException e) {
                        throw new IOException(
                                "Invalid commit file count.",
                                e
                        );
                    }

                    break;
                }
            }

            if (parentHash == null || fileCount < 0) {
                throw new IOException(
                        "Incomplete commit metadata: " + currentHash
                );
            }

            if (!parentHash.isEmpty()
                    && !parentHash.matches("[a-fA-F0-9]{64}")) {
                throw new IOException("Invalid parent commit hash.");
            }

            Map<String, String> currentCommitFiles = new LinkedHashMap<>();

            for (int i = 0; i < fileCount; i++) {

                if (lineIndex >= lines.length) {
                    throw new IOException(
                            "Missing commit file entry."
                    );
                }

                String entry = lines[lineIndex++];
                int separator = entry.lastIndexOf('\t');

                if (separator <= 0) {
                    throw new IOException(
                            "Invalid commit file entry."
                    );
                }

                String path;

                try {
                    path = new String(
                            Base64.getDecoder().decode(
                                    entry.substring(0, separator)
                            ),
                            StandardCharsets.UTF_8
                    );
                } catch (IllegalArgumentException e) {
                    throw new IOException(
                            "Invalid encoded file path.",
                            e
                    );
                }

                String blobHash = entry.substring(separator + 1);

                Path parsedPath;

                try {
                    parsedPath = Path.of(path);
                } catch (RuntimeException e) {
                    throw new IOException(
                            "Invalid file path: " + path,
                            e
                    );
                }

                if (path.isBlank()
                        || path.startsWith("/")
                        || path.contains("\\")
                        || parsedPath.isAbsolute()
                        || parsedPath.normalize().startsWith("..")
                        || !blobHash.matches("[a-fA-F0-9]{64}")) {
                    throw new IOException(
                            "Invalid commit file entry: " + path
                    );
                }

                if (currentCommitFiles.put(path, blobHash) != null) {
                    throw new IOException(
                            "Duplicate commit file entry: " + path
                    );
                }
            }

            /*
             * Newest commit wins. This also reconstructs the snapshots
             * from older commits that stored only changed files.
             */
            for (Map.Entry<String, String> entry
                    : currentCommitFiles.entrySet()) {

                snapshot.putIfAbsent(
                        entry.getKey(),
                        entry.getValue()
                );
            }

            currentHash = parentHash;
        }

        return snapshot;
    }

    private String fileJson(String path, String status) {
        return """
                {
                  "path": "%s",
                  "status": "%s"
                }
                """.formatted(
                escapeJson(path),
                escapeJson(status)
        ).trim();
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}