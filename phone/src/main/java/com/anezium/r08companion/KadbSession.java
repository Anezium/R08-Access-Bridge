package com.anezium.r08companion;

import com.flyfishxu.kadb.Kadb;

import java.io.IOException;

final class KadbSession implements AdbSession {
    private final Kadb kadb;

    private KadbSession(Kadb kadb) {
        this.kadb = kadb;
    }

    static KadbSession connect(String host, int port) throws IOException {
        KadbSession session = new KadbSession(new Kadb(host, port, 5000, 15000));
        try {
            ShellResult probe = session.shell("echo r08");
            if (!probe.getOutput().trim().equals("r08")) {
                throw new IOException("unexpected ADB probe response: " + probe.getAllOutput());
            }
            return session;
        } catch (IOException exception) {
            session.close();
            throw exception;
        }
    }

    @Override
    public ShellResult shell(String command) throws IOException {
        try {
            com.flyfishxu.kadb.shell.AdbShellResponse response = kadb.shell(command);
            return new ShellResult(response.getOutput(),
                    response.getErrorOutput(),
                    response.getExitCode());
        } catch (RuntimeException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
    }

    @Override
    public void close() {
        try {
            kadb.close();
        } catch (RuntimeException ignored) {
            // Best-effort transport cleanup.
        }
    }
}
