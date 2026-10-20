package com.minigit.command;

import com.minigit.core.Index;
import com.minigit.core.Repository;
import com.minigit.storage.FileObjectStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class AddCommand {

    private final Repository repository;

    public AddCommand(Repository repository) {
        this.repository = repository;
    }

    public void execute(String filePath) throws IOException {

        if (!repository.isInitialized()) {
            throw new IllegalStateException(
                    "MiniGit repository is not initialized. "
                            + "Run 'init' first."
            );
        }

        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException(
                    "Please specify a file to add."
            );
        }

        Path workingDirectory = repository.getWorkingDirectory();

        Path requestedPath = Path.of(filePath);

        Path absolutePath = requestedPath.isAbsolute()
                ? requestedPath.normalize()
                : workingDirectory.resolve(requestedPath).normalize();

        if (!absolutePath.startsWith(workingDirectory)) {
            throw new IllegalArgumentException(
                    "Cannot add files outside the repository."
            );
        }

        if (absolutePath.startsWith(
                repository.getMetadataDirectory())) {
            throw new IllegalArgumentException(
                    "Cannot add files inside the .minigit directory."
            );
        }

        if (!Files.isRegularFile(absolutePath)) {
            throw new IllegalArgumentException(
                    "File does not exist or is not a regular file: "
                            + filePath
            );
        }

        String relativePath = workingDirectory
                .relativize(absolutePath)
                .toString()
                .replace('\\', '/');

        byte[] content = Files.readAllBytes(absolutePath);

        FileObjectStore objectStore = new FileObjectStore(
                repository.getMetadataDirectory()
        );

        String objectHash = objectStore.store(content);

        Index index = new Index(
                repository.getMetadataDirectory()
        );

        index.add(relativePath, objectHash);

        System.out.println(
                "Staged: " + relativePath
        );

        System.out.println(
                "Object hash: " + objectHash
        );
    }
}