package com.minigit.command;

import com.minigit.core.Index;
import com.minigit.core.Repository;
import com.minigit.util.HashUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class StatusCommand {

    private final Repository repository;

    public StatusCommand(Repository repository) {
        this.repository = repository;
    }

    public void execute() throws IOException {

        if (!repository.isInitialized()) {
            throw new IllegalStateException(
                    "MiniGit repository is not initialized. "
                            + "Run 'init' first."
            );
        }

        Index index = new Index(
                repository.getMetadataDirectory()
        );

        Map<String, String> stagedFiles = index.getEntries();

        Set<String> discoveredFiles = new HashSet<>();

        try (Stream<Path> paths = Files.walk(
                repository.getWorkingDirectory())) {

            paths.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(
                            repository.getMetadataDirectory()))
                    .filter(path -> !path.toString().contains(
                            "/target/"))
                    .forEach(path -> discoveredFiles.add(
                            repository.getWorkingDirectory()
                                    .relativize(path)
                                    .toString()
                                    .replace('\\', '/')
                    ));
        }

        boolean foundChanges = false;

        for (String file : discoveredFiles) {

            if (!stagedFiles.containsKey(file)) {
                System.out.println("Untracked: " + file);
                foundChanges = true;
                continue;
            }

            Path filePath = repository.getWorkingDirectory()
                    .resolve(file);

            String currentHash = HashUtil.sha256(
                    Files.readAllBytes(filePath)
            );

            String stagedHash = stagedFiles.get(file);

            if (!currentHash.equals(stagedHash)) {
                System.out.println("Modified: " + file);
                foundChanges = true;
            }
        }

        for (String stagedFile : stagedFiles.keySet()) {
            if (!discoveredFiles.contains(stagedFile)) {
                System.out.println("Missing: " + stagedFile);
                foundChanges = true;
            }
        }

        if (!foundChanges) {
            System.out.println("Working tree clean.");
        }
    }
}