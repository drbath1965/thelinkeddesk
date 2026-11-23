package com.linkeddesk.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Makes sure only one window is open. The first window holds a lock file and listens on a local-only
 * port; a second launch finds the lock, asks the first window to come to the front, and exits.
 */
public final class SingleInstance {
    private static FileChannel channel;
    private static FileLock lock;
    private static ServerSocket server;

    private SingleInstance() {}

    /**
     * Returns true if this process is now the only window. Otherwise signals the existing window
     * (which calls {@code onShow}) and returns false.
     */
    public static synchronized boolean claim(Path home, Runnable onShow) throws IOException {
        Files.createDirectories(home);
        if (lock != null && lock.isValid()) return true;           // this process already owns the window
        channel = FileChannel.open(home.resolve(".window.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { lock = channel.tryLock(); } catch (java.nio.channels.OverlappingFileLockException e) { lock = null; }
        Path portFile = home.resolve(".window.port");
        if (lock == null) {
            channel.close();
            signal(portFile);
            return false;
        }
        server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Files.writeString(portFile, Integer.toString(server.getLocalPort()));
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept(); InputStream in = s.getInputStream()) {
                    String msg = new String(in.readNBytes(16), StandardCharsets.US_ASCII).trim();
                    if (msg.equals("show")) onShow.run();
                } catch (IOException ignored) { }
            }
        }, "single-instance");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private static void signal(Path portFile) {
        try {
            int port = Integer.parseInt(Files.readString(portFile).trim());
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port); OutputStream out = s.getOutputStream()) {
                out.write("show".getBytes(StandardCharsets.US_ASCII));
            }
        } catch (Exception ignored) { /* first window is still starting; nothing else to do */ }
    }

    public static synchronized void release() {
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        try { if (lock != null) lock.release(); } catch (IOException ignored) { }
        lock = null;
        try { if (channel != null) channel.close(); } catch (IOException ignored) { }
    }
}
