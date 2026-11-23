package com.linkeddesk.schedule;

import com.linkeddesk.app.App;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;

/**
 * Registers a Windows Task Scheduler job that runs "javaw -jar LinkedDesk.jar --fetch" every day,
 * so news is downloaded even when the window is closed.
 *
 * The task is registered from an XML definition (schtasks /Create /XML) rather than /TR, because
 * the program and jar paths can contain spaces and quotes that /TR handles poorly. The XML also sets:
 *  - StartWhenAvailable: if the PC was off or asleep at the set time, run as soon as it is back;
 *  - RunOnlyIfNetworkAvailable: don't run (and fail) while offline;
 *  - IgnoreNew: never run two downloads at once; one-hour time limit.
 */
public final class WindowsTask {
    public static final String TASK_NAME = "The Linked Desk daily news download";

    private WindowsTask() {}

    public static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static String install(LocalTime time) throws Exception {
        Action a = currentAction();
        return install(time, a, userId(), "schtasks");
    }

    /** What the task runs: a program, its arguments and the folder to run in. */
    public record Action(String program, String arguments, String folder) {
        /** Running from LinkedDesk.jar: javaw -jar "LinkedDesk.jar" --fetch */
        static Action forJar(String javaw, String jar) {
            return new Action(javaw, "-jar \"" + jar + "\" --fetch", parentOf(jar));
        }
        /** Installed app: its own launcher (LinkedDesk.exe) with --fetch. */
        static Action forLauncher(String exe) {
            return new Action(exe, "--fetch", parentOf(exe));
        }
    }

    /** Works out how this copy of the app was started: installed launcher, or the jar. */
    public static Action currentAction() {
        String launcher = System.getProperty("jpackage.app-path");   // set by the installed app's launcher
        if (launcher != null && !launcher.isBlank()) return Action.forLauncher(launcher);
        Path jar = jarPath();
        if (jar == null || !jar.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IllegalStateException("Run the app from LinkedDesk.jar or the installed app to set up the daily task.");
        }
        return Action.forJar(javaw(), jar.toAbsolutePath().toString());
    }

    /** Testable core (jar form). */
    static String install(LocalTime time, String javaw, String jar, String userId, String schtasks) throws Exception {
        return install(time, Action.forJar(javaw, jar), userId, schtasks);
    }

    /** Testable core: writes the task XML and registers it with the given schtasks command. */
    static String install(LocalTime time, Action action, String userId, String schtasks) throws Exception {
        Path xml = Files.createTempFile("linkeddesk-task", ".xml");
        try {
            writeUtf16(xml, taskXml(time, action, userId, LocalDate.now()));
            return run(List.of(schtasks, "/Create", "/F", "/TN", TASK_NAME, "/XML", xml.toString()));
        } finally {
            Files.deleteIfExists(xml);
        }
    }

    public static String remove() throws Exception {
        return run(List.of("schtasks", "/Delete", "/F", "/TN", TASK_NAME));
    }

    /**
     * When you start a newer LinkedDesk-&lt;version&gt;.jar (or the installed app) than the one the daily task runs,
     * moves the task over to this copy at the same time of day. Returns what was done, or null if nothing needed doing.
     */
    public static String followThisCopy() {
        if (!supported()) return null;
        try {
            String xml = run(List.of("schtasks", "/Query", "/TN", TASK_NAME, "/XML"));
            Action now = currentAction();
            if (!pointsElsewhere(xml, now)) return null;
            LocalTime t = timeIn(xml);
            if (t == null) return null;
            install(t, now, userId(), "schtasks");
            return "The daily download task now runs this copy (" + now.arguments().replace(" --fetch", "") + ").";
        } catch (Exception e) {
            return null;        // no task set up, or Windows refused: nothing to move
        }
    }

    /** True if the task's command is not this copy of the app. */
    static boolean pointsElsewhere(String xml, Action now) {
        String args = tag(xml, "Arguments"), cmd = tag(xml, "Command");
        if (args == null || cmd == null) return false;
        return !unx(args).equals(now.arguments()) || !unx(cmd).equalsIgnoreCase(now.program());
    }

    /** The task's time of day, from its start boundary. */
    static LocalTime timeIn(String xml) {
        String sb = tag(xml, "StartBoundary");
        if (sb == null || sb.indexOf('T') < 0) return null;
        try { return LocalTime.parse(sb.substring(sb.indexOf('T') + 1, sb.indexOf('T') + 6)); } catch (RuntimeException e) { return null; }
    }

    private static String tag(String xml, String name) {
        int a = xml.indexOf("<" + name + ">"), b = xml.indexOf("</" + name + ">");
        return a < 0 || b < a ? null : xml.substring(a + name.length() + 2, b).trim();
    }

    private static String unx(String s) {
        return s.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    /** Status of the task as Windows reports it: next run, last run and last result. */
    public static String query() throws Exception {
        return run(List.of("schtasks", "/Query", "/TN", TASK_NAME, "/V", "/FO", "LIST"));
    }

    /** The Task Scheduler definition. Pure, so it can be checked on any OS. */
    static String taskXml(LocalTime time, String javaw, String jar, String userId, LocalDate startDay) {
        return taskXml(time, Action.forJar(javaw, jar), userId, startDay);
    }

    static String parentOf(String path) {
        int cut = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
        return cut > 0 ? path.substring(0, cut) : ".";
    }

    static String taskXml(LocalTime time, Action action, String userId, LocalDate startDay) {
        String start = startDay + "T" + String.format("%02d:%02d:00", time.getHour(), time.getMinute());
        String user = userId == null || userId.isBlank() ? "" : "      <UserId>" + x(userId) + "</UserId>\n";
        return """
                <?xml version="1.0" encoding="UTF-16"?>
                <Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
                  <RegistrationInfo>
                    <Description>Downloads world news for The Linked Desk every day.</Description>
                  </RegistrationInfo>
                  <Triggers>
                    <CalendarTrigger>
                      <StartBoundary>%s</StartBoundary>
                      <Enabled>true</Enabled>
                      <ScheduleByDay><DaysInterval>1</DaysInterval></ScheduleByDay>
                    </CalendarTrigger>
                  </Triggers>
                  <Principals>
                    <Principal id="Author">
                %s      <LogonType>InteractiveToken</LogonType>
                      <RunLevel>LeastPrivilege</RunLevel>
                    </Principal>
                  </Principals>
                  <Settings>
                    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
                    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
                    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
                    <StartWhenAvailable>true</StartWhenAvailable>
                    <RunOnlyIfNetworkAvailable>true</RunOnlyIfNetworkAvailable>
                    <ExecutionTimeLimit>PT1H</ExecutionTimeLimit>
                    <Enabled>true</Enabled>
                  </Settings>
                  <Actions Context="Author">
                    <Exec>
                      <Command>%s</Command>
                      <Arguments>%s</Arguments>
                      <WorkingDirectory>%s</WorkingDirectory>
                    </Exec>
                  </Actions>
                </Task>
                """.formatted(start, user, x(action.program()), x(action.arguments()), x(action.folder()));
    }

    private static String x(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** schtasks expects the XML file in UTF-16 with a byte-order mark. */
    private static void writeUtf16(Path f, String xml) throws IOException {
        Files.write(f, xml.getBytes(Charset.forName("UTF-16")));   // Java's UTF-16 writes a big-endian BOM
    }

    private static String run(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] raw = p.getInputStream().readAllBytes();
        int code = p.waitFor();
        String out = new String(raw, consoleCharset()).trim();
        if (code != 0) throw new IllegalStateException(out.isEmpty() ? "schtasks failed (" + code + ")" : out);
        return out;
    }

    private static Charset consoleCharset() {
        try {
            String enc = System.getProperty("native.encoding");
            return enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static String userId() {
        String user = System.getenv("USERNAME");
        String domain = System.getenv("USERDOMAIN");
        if (user == null || user.isBlank()) return "";
        return domain == null || domain.isBlank() ? user : domain + "\\" + user;
    }

    private static Path jarPath() {
        try {
            return Path.of(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            return null;
        }
    }

    private static String javaw() {
        String cmd = ProcessHandle.current().info().command().orElse("");
        if (!cmd.isEmpty()) {
            File f = new File(cmd);
            File w = new File(f.getParentFile(), "javaw.exe");
            return w.exists() ? w.getAbsolutePath() : f.getAbsolutePath();
        }
        return Path.of(System.getProperty("java.home"), "bin", "javaw.exe").toString();
    }
}
