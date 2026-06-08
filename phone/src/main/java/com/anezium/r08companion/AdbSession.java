package com.anezium.r08companion;

import java.io.IOException;

interface AdbSession extends AutoCloseable {
    ShellResult shell(String command) throws IOException;

    @Override
    void close();

    default String runChecked(String command) throws IOException {
        ShellResult response = shell(command);
        if (response.getExitCode() != 0) {
            throw new IOException(command + "\n"
                    + response.getErrorOutput()
                    + response.getOutput());
        }
        return response.getOutput();
    }
}

final class ShellResult {
    private final String output;
    private final String errorOutput;
    private final int exitCode;

    ShellResult(String output, String errorOutput, int exitCode) {
        this.output = output == null ? "" : output;
        this.errorOutput = errorOutput == null ? "" : errorOutput;
        this.exitCode = exitCode;
    }

    String getOutput() {
        return output;
    }

    String getErrorOutput() {
        return errorOutput;
    }

    int getExitCode() {
        return exitCode;
    }

    String getAllOutput() {
        return output + errorOutput;
    }
}
