package com.linkeddesk.alerts;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Sends one email through your mail provider's SMTP server, using only the JDK (ALR-F04). Supports STARTTLS
 * (usually port 587), SSL (port 465) and, only if you choose it, an unencrypted connection. The server's
 * certificate and name are checked. The message has a plain-text and an HTML version.
 */
public final class Mailer {
    public record Config(String host, int port, String security, String user, String password, String from, List<String> to) {
        /** Recipients from "a@x.com, b@y.com". */
        public static List<String> list(String s) {
            List<String> out = new ArrayList<>();
            for (String x : s.split("[,;\\s]+")) if (x.contains("@")) out.add(x.strip());
            return out;
        }
    }

    /** The server said no, with a plain-language reason. */
    public static final class MailException extends IOException {
        MailException(String m) { super(m); }
    }

    private final SSLSocketFactory ssl;

    public Mailer() { this((SSLSocketFactory) SSLSocketFactory.getDefault()); }

    /** For tests: a factory that trusts a test certificate. */
    public Mailer(SSLSocketFactory ssl) { this.ssl = ssl; }

    private Socket sock;
    private BufferedReader in;
    private OutputStream out;
    private final List<String> transcript = new ArrayList<>();

    /** The conversation with the server (passwords hidden), for the log when something goes wrong. */
    public List<String> transcript() { return transcript; }

    public void send(Config c, String subject, String text, String html) throws IOException {
        if (c.host().isBlank()) throw new MailException("Enter your email provider's SMTP server in Settings (for Gmail: smtp.gmail.com).");
        if (c.to().isEmpty()) throw new MailException("Enter at least one address to send the briefing to.");
        if (c.from().isBlank() || !c.from().contains("@")) throw new MailException("Enter the address the email comes from.");
        String sec = c.security().toUpperCase(Locale.ROOT);
        transcript.clear();
        try {
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(c.host(), c.port()), 20_000);
            raw.setSoTimeout(30_000);
            sock = sec.equals("SSL") ? secure(raw, c.host(), c.port()) : raw;
            open();
            expect(220, "connect");
            String caps = ehlo();
            if (sec.equals("STARTTLS")) {
                if (!caps.toUpperCase(Locale.ROOT).contains("STARTTLS")) throw new MailException("The server " + c.host() + " doesn't offer STARTTLS. Try security \"SSL\" with port 465.");
                cmd("STARTTLS", 220);
                sock = secure(sock, c.host(), c.port());
                open();
                caps = ehlo();
            }
            if (!c.user().isBlank()) {
                if (sec.equals("NONE")) throw new MailException("Refusing to send your password over an unencrypted connection. Choose STARTTLS or SSL.");
                String up = caps.toUpperCase(Locale.ROOT);
                if (up.contains("PLAIN")) {
                    String token = Base64.getEncoder().encodeToString(("\0" + c.user() + "\0" + c.password()).getBytes(StandardCharsets.UTF_8));
                    cmd("AUTH PLAIN " + token, 235, "AUTH PLAIN ****");
                } else {
                    cmd("AUTH LOGIN", 334);
                    cmd(Base64.getEncoder().encodeToString(c.user().getBytes(StandardCharsets.UTF_8)), 334, "(user name)");
                    cmd(Base64.getEncoder().encodeToString(c.password().getBytes(StandardCharsets.UTF_8)), 235, "(password)");
                }
            }
            cmd("MAIL FROM:<" + c.from() + ">", 250);
            for (String t : c.to()) cmd("RCPT TO:<" + t + ">", 250, 251);
            cmd("DATA", 354);
            write(message(c, subject, text, html));
            write(".\r\n");
            expect(250, "message");
            try { cmd("QUIT", 221); } catch (IOException ignored) { }
        } catch (javax.net.ssl.SSLException e) {
            throw new MailException("A secure connection to " + c.host() + " couldn't be made (" + e.getMessage() + "). Check the server name and the security setting.");
        } catch (java.net.UnknownHostException e) {
            throw new MailException("The server \"" + c.host() + "\" wasn't found. Check its name in Settings.");
        } catch (java.net.SocketTimeoutException | java.net.ConnectException e) {
            throw new MailException("Couldn't reach " + c.host() + ":" + c.port() + " (" + e.getMessage() + "). Check the server, port and your connection.");
        } finally {
            if (sock != null) try { sock.close(); } catch (IOException ignored) { }
        }
    }

    private SSLSocket secure(Socket s, String host, int port) throws IOException {
        SSLSocket t = (SSLSocket) ssl.createSocket(s, host, port, true);
        SSLParameters p = t.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS");   // the certificate must be for this server's name
        t.setSSLParameters(p);
        t.startHandshake();
        return t;
    }

    private void open() throws IOException {
        in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
        out = sock.getOutputStream();
    }

    private String ehlo() throws IOException {
        String name = "linkeddesk";
        try { name = java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception ignored) { }
        write("EHLO " + name + "\r\n");
        transcript.add("> EHLO " + name);
        return reply(250, "EHLO");
    }

    private void cmd(String line, int... ok) throws IOException { cmd(line, ok.length == 1 ? ok[0] : ok[0], null, ok); }

    private void cmd(String line, int ok, String shown) throws IOException { cmd(line, ok, shown, new int[]{ok}); }

    private void cmd(String line, int ok, String shown, int[] oks) throws IOException {
        write(line + "\r\n");
        transcript.add("> " + (shown != null ? shown : line));
        String r = readReply();
        int code = code(r);
        for (int o : oks) if (code == o) return;
        throw new MailException(friendly(code, r, line));
    }

    private void expect(int ok, String what) throws IOException { reply(ok, what); }

    private String reply(int ok, String what) throws IOException {
        String r = readReply();
        if (code(r) != ok) throw new MailException(friendly(code(r), r, what));
        return r;
    }

    private String readReply() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            String l = in.readLine();
            if (l == null) throw new MailException("The mail server closed the connection.");
            transcript.add("< " + l);
            sb.append(l).append('\n');
            if (l.length() < 4 || l.charAt(3) != '-') break;
        }
        return sb.toString();
    }

    private static int code(String r) {
        try { return Integer.parseInt(r.substring(0, 3)); } catch (RuntimeException e) { return 0; }
    }

    private void write(String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    static String friendly(int code, String reply, String during) {
        String server = reply.strip().replace("\n", " ");
        String what = switch (code) {
            case 535, 534, 530 -> "The email server didn't accept the user name or password. With Gmail, use an app password "
                    + "(Google account > Security > App passwords), not your normal password.";
            case 550, 553, 554 -> "The email server refused the message or an address.";
            case 421 -> "The email server is busy or closed the connection. Try again later.";
            default -> "The email server answered with an error.";
        };
        return what + " (server said: " + server + ")";
    }

    // ------------------------------------------------------------------ the message

    static String message(Config c, String subject, String text, String html) {
        String boundary = "ld-" + UUID.randomUUID();
        StringBuilder sb = new StringBuilder();
        sb.append("From: ").append(c.from()).append("\r\n");
        sb.append("To: ").append(String.join(", ", c.to())).append("\r\n");
        sb.append("Subject: ").append(header(subject)).append("\r\n");
        sb.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())).append("\r\n");
        sb.append("Message-ID: <").append(UUID.randomUUID()).append("@linkeddesk>\r\n");
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: multipart/alternative; boundary=\"").append(boundary).append("\"\r\n\r\n");
        part(sb, boundary, "text/plain", text);
        part(sb, boundary, "text/html", html);
        sb.append("--").append(boundary).append("--\r\n");
        // Lines starting with "." are doubled (SMTP); base64 bodies never start with one, headers rarely do.
        return sb.toString().replace("\r\n.", "\r\n..");
    }

    private static void part(StringBuilder sb, String boundary, String type, String body) {
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Type: ").append(type).append("; charset=UTF-8\r\n");
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n");
        String b64 = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(body.getBytes(StandardCharsets.UTF_8));
        sb.append(b64).append("\r\n");
    }

    /** A header value in plain ASCII as is, otherwise encoded (RFC 2047). */
    static String header(String s) {
        if (s.chars().allMatch(ch -> ch >= 32 && ch < 127)) return s;
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)) + "?=";
    }
}
