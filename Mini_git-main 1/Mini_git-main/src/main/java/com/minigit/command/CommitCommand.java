package com.minigit.command;

import com.minigit.core.Index;
import com.minigit.core.Reflog;
import com.minigit.core.Repository;
import com.minigit.storage.FileObjectStore;
import com.minigit.util.HashUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class CommitCommand {

    private final Repository repository;

    public CommitCommand(Repository repository) {
        this.repository = repository;
    }

    public void execute(String message) throws IOException {

        if (!repository.isInitialized()) {
            throw new IllegalStateException(
                    "MiniGit repository is not initialized. Run 'init' first."
            );
        }

        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException(
                    "Commit message cannot be empty."
            );
        }

        Path metadataDirectory = repository.getMetadataDirectory();
        Path workingDirectory = repository.getWorkingDirectory()
                .toAbsolutePath()
                .normalize();

        Index index = new Index(metadataDirectory);

        Map<String, String> stagedFiles =
                new TreeMap<>(index.getEntries());

        if (stagedFiles.isEmpty()) {
            throw new IllegalStateException(
                    "Nothing to commit. Stage files using 'add' first."
            );
        }

        // Verify that staged files have not changed since staging.
        for (Map.Entry<String, String> entry : stagedFiles.entrySet()) {

            Path relativePath = Path.of(entry.getKey());

            if (relativePath.isAbsolute()
                    || relativePath.normalize().startsWith("..")
                    || entry.getKey().contains("\\")) {
                throw new IllegalStateException(
                        "Invalid path in staging index: " + entry.getKey()
                );
            }

            Path file = workingDirectory.resolve(relativePath).normalize();

            if (!file.startsWith(workingDirectory)) {
                throw new IllegalStateException(
                        "Invalid path in staging index: " + entry.getKey()
                );
            }

            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException(
                        "Staged file is missing: " + entry.getKey()
                );
            }

            String currentHash = HashUtil.sha256(
                    Files.readAllBytes(file)
            );

            if (!currentHash.equals(entry.getValue())) {
                throw new IllegalStateException(
                        "File changed after staging: " + entry.getKey()
                                + ". Run 'add' again before committing."
                );
            }
        }

        // Resolve HEAD and the current branch reference.
        Path headFile = metadataDirectory.resolve("HEAD");

        String head = Files.readString(
                headFile,
                StandardCharsets.UTF_8
        ).trim();

        if (!head.startsWith("ref: ")) {
            throw new IOException("Invalid HEAD file.");
        }

        String reference = head.substring(5);

        Path refsDirectory = metadataDirectory.resolve("refs")
                .toAbsolutePath()
                .normalize();

        Path branchFile = metadataDirectory.resolve(reference)
                .toAbsolutePath()
                .normalize();

        if (!branchFile.startsWith(refsDirectory)) {
            throw new IOException("Invalid branch reference in HEAD.");
        }

        String parentHash = "";

        if (Files.exists(branchFile)) {
            parentHash = Files.readString(
                    branchFile,
                    StandardCharsets.UTF_8
            ).trim();
        }

        FileObjectStore objectStore =
                new FileObjectStore(metadataDirectory);

        // Reconstruct the existing committed snapshot.
        // Older MiniGit commits store only changed/staged files, so walk
        // backward through history and apply commits oldest to newest.
        Map<String, String> snapshot = new TreeMap<>();

        if (!parentHash.isEmpty()) {

            Set<String> visited = new HashSet<>();
            String currentHash = parentHash;

            while (!currentHash.isEmpty()) {

                if (!currentHash.matches("[a-fA-F0-9]{64}")) {
                    throw new IOException(
                            "Invalid commit hash in history: " + currentHash
                    );
                }

                if (!visited.add(currentHash)) {
                    throw new IOException(
                            "Circular commit history detected."
                    );
                }

                byte[] parentContent;

                try {
                    parentContent = objectStore.read(currentHash);
                } catch (IOException e) {
                    throw new IOException(
                            "Unable to read commit object: " + currentHash,
                            e
                    );
                }

                String commitText = new String(
                        parentContent,
                        StandardCharsets.UTF_8
                );

                String[] lines = commitText.split("\n", -1);

                if (lines.length == 0
                        || !"MINIGIT_COMMIT_V1".equals(lines[0])) {
                    throw new IOException(
                            "Invalid commit object: " + currentHash
                    );
                }

                String nextParent = null;
                int fileCount = -1;
                int lineIndex = 1;

                // Read commit metadata.
                while (lineIndex < lines.length) {

                    String line = lines[lineIndex++];

                    if (line.startsWith("parent=")) {
                        nextParent = line.substring("parent=".length());
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

                if (nextParent == null || fileCount < 0) {
                    throw new IOException(
                            "Incomplete commit metadata: " + currentHash
                    );
                }

                if (!nextParent.isEmpty()
                        && !nextParent.matches("[a-fA-F0-9]{64}")) {
                    throw new IOException(
                            "Invalid parent commit hash: " + nextParent
                    );
                }

                Map<String, String> commitFiles = new TreeMap<>();

                // Read the files stored in this commit.
                for (int n = 0; n < fileCount; n++) {

                    if (lineIndex >= lines.length) {
                        throw new IOException(
                                "Missing commit file entry: " + currentHash
                        );
                    }

                    String entry = lines[lineIndex++];
                    int separator = entry.lastIndexOf('\t');

                    if (separator <= 0) {
                        throw new IOException(
                                "Invalid commit file entry: " + currentHash
                        );
                    }

                    String filePath;

                    try {
                        filePath = new String(
                                Base64.getDecoder().decode(
                                        entry.substring(0, separator)
                                ),
                                StandardCharsets.UTF_8
                        );
                    } catch (IllegalArgumentException e) {
                        throw new IOException(
                                "Invalid encoded file path in commit.",
                                e
                        );
                    }

                    String blobHash = entry.substring(separator + 1);

                    Path parsedPath;

                    try {
                        parsedPath = Path.of(filePath);
                    } catch (RuntimeException e) {
                        throw new IOException(
                                "Invalid file path in commit: " + filePath,
                                e
                        );
                    }

                    if (filePath.isBlank()
                            || filePath.startsWith("/")
                            || filePath.contains("\\")
                            || parsedPath.isAbsolute()
                            || parsedPath.normalize().startsWith("..")
                            || !blobHash.matches("[a-fA-F0-9]{64}")) {
                        throw new IOException(
                                "Invalid file entry in commit: " + filePath
                        );
                    }

                    if (commitFiles.put(filePath, blobHash) != null) {
                        throw new IOException(
                                "Duplicate file entry in commit: " + filePath
                        );
                    }
                }

                /*
                 * The history is traversed newest to oldest.
                 * Only populate missing entries here, so the newest
                 * version of a file takes precedence over older versions.
                 */
                for (Map.Entry<String, String> entry
                        : commitFiles.entrySet()) {

                    snapshot.putIfAbsent(
                            entry.getKey(),
                            entry.getValue()
                    );
                }

                currentHash = nextParent;
            }
        }

        // Apply staged additions and modifications.
        snapshot.putAll(stagedFiles);

        // Build a commit containing the complete file snapshot.
        StringBuilder content = new StringBuilder();

        content.append("MINIGIT_COMMIT_V1\n");
        content.append("parent=").append(parentHash).append('\n');
        content.append("timestamp=").append(Instant.now()).append('\n');

        String encodedMessage = Base64.getEncoder().encodeToString(
                message.getBytes(StandardCharsets.UTF_8)
        );

        content.append("message=").append(encodedMessage).append('\n');
        content.append("files=").append(snapshot.size()).append('\n');

        for (Map.Entry<String, String> entry : snapshot.entrySet()) {

            String encodedPath = Base64.getEncoder().encodeToString(
                    entry.getKey().getBytes(StandardCharsets.UTF_8)
            );

            content.append(encodedPath)
                    .append('\t')
                    .append(entry.getValue())
                    .append('\n');
        }

        byte[] commitContent = content.toString()
                .getBytes(StandardCharsets.UTF_8);

        // Store the new commit object.
        String commitHash = objectStore.store(commitContent);

        // Prepare the branch reference update.
        Files.createDirectories(branchFile.getParent());

        Path temporaryFile = Files.createTempFile(
                branchFile.getParent(),
                "branch-",
                ".tmp"
        );

        try {
            Files.writeString(
                    temporaryFile,
                    commitHash + "\n",
                    StandardCharsets.UTF_8
            );

            try {
                Files.move(
                        temporaryFile,
                        branchFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (IOException e) {
                Files.move(
                        temporaryFile,
                        branchFile,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

        } finally {
            Files.deleteIfExists(temporaryFile);
        }

        // Record the branch movement so it can be recovered later.
        try {
            Reflog.append(
                    metadataDirectory,
                    "commit",
                    reference.startsWith("refs/heads/")
                            ? reference.substring("refs/heads/".length())
                            : reference,
                    parentHash,
                    commitHash,
                    message
            );
        } catch (IOException e) {
            System.err.println(
                    "Warning: could not write the reflog: "
                            + e.getMessage()
            );
        }

        /*
         * Clear the staging index only after the branch reference
         * has been updated successfully.
         */
        index.clear();

        System.out.println("Commit created successfully!");
        System.out.println("Commit hash: " + commitHash);
        System.out.println(
                "Branch: " + reference.substring("refs/heads/".length())
        );
        System.out.println("Files committed: " + snapshot.size());
        System.out.println("Message: " + message);
    }
}