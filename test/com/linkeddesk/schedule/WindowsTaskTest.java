package com.linkeddesk.schedule;

import com.linkeddesk.Check;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;

/** OPS-F03: the Task Scheduler definition and the schtasks call, checked with a stand-in schtasks. */
public final class WindowsTaskTest {
    private static String text(Document d, String tag) {
        return d.getElementsByTagNameNS("*", tag).item(0).getTextContent();
    }

    public static void run(Check c) throws Exception {
        c.suite("Windows daily task (OPS-F03)");
        String javaw = "C:\\Program Files\\Eclipse Adoptium\\jre-21.0.5.11-hotspot\\bin\\javaw.exe";
        String jar = "C:\\Users\\David Bath\\R&D Apps\\TheLinkedDesk\\LinkedDesk.jar";
        String xml = WindowsTask.taskXml(LocalTime.of(6, 30), javaw, jar, "DESKTOP-01\\David", LocalDate.of(2026, 11, 23));

        byte[] utf16 = xml.getBytes(Charset.forName("UTF-16"));
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document d = f.newDocumentBuilder().parse(new ByteArrayInputStream(utf16));
        c.eq("Task", d.getDocumentElement().getLocalName(), "XML is well-formed and parses as UTF-16");
        c.eq(javaw, text(d, "Command"), "program path with spaces kept exactly");
        c.eq("-jar \"" + jar + "\" --fetch", text(d, "Arguments"), "jar path quoted; '&' survives escaping");
        c.eq("C:\\Users\\David Bath\\R&D Apps\\TheLinkedDesk", text(d, "WorkingDirectory").replace('/', '\\'), "runs in the app's folder");
        c.eq("2026-11-23T06:30:00", text(d, "StartBoundary"), "daily at the set time");
        c.eq("1", text(d, "DaysInterval"), "every day");
        c.eq("true", text(d, "StartWhenAvailable"), "runs a missed download when the PC is back on");
        c.eq("true", text(d, "RunOnlyIfNetworkAvailable"), "waits for a network connection");
        c.eq("IgnoreNew", text(d, "MultipleInstancesPolicy"), "never two downloads at once");
        // A newer LinkedDesk-<version>.jar: the task moves to it (same time of day).
        String newJar = "C:\\Users\\David Bath\\R&D Apps\\TheLinkedDesk\\LinkedDesk-1.7.1.jar";
        c.that(WindowsTask.pointsElsewhere(xml, WindowsTask.Action.forJar(javaw, newJar)), "a task set up from an older jar is noticed when a newer one runs");
        c.that(!WindowsTask.pointsElsewhere(xml, WindowsTask.Action.forJar(javaw, jar)), "… and left alone when it already runs this copy");
        c.eq(LocalTime.of(6, 30), WindowsTask.timeIn(xml), "it keeps the time of day you set");
        c.eq("false", text(d, "DisallowStartIfOnBatteries"), "runs on laptop battery too");
        c.eq("InteractiveToken", text(d, "LogonType"), "runs as you, no password stored");
        c.eq("DESKTOP-01\\David", text(d, "UserId"), "registered for the current Windows user");
        c.eq("LeastPrivilege", text(d, "RunLevel"), "no administrator rights needed");
        c.that(!WindowsTask.taskXml(LocalTime.of(7, 5), javaw, jar, "", LocalDate.of(2026, 1, 2)).contains("<UserId>"),
                "UserId left out when Windows doesn't report a user");

        if (WindowsTask.supported()) {
            System.out.println("  (stand-in schtasks checks skipped on Windows; use Settings > Check daily task)");
            return;
        }

        // ---- install(): a stand-in schtasks records its arguments and the XML it was given.
        Path dir = Files.createTempDirectory("ld-schtasks");
        Path log = dir.resolve("args.txt");
        Path saved = dir.resolve("task.xml");
        Path fake = dir.resolve("schtasks");
        Files.writeString(fake, "#!/bin/sh\nprintf '%s\\n' \"$@\" > '" + log + "'\n"
                + "for a in \"$@\"; do case \"$a\" in *.xml) cp \"$a\" '" + saved + "';; esac; done\n"
                + "echo 'SUCCESS: The scheduled task \"The Linked Desk daily news download\" has successfully been created.'\n");
        fake.toFile().setExecutable(true);
        String out = WindowsTask.install(LocalTime.of(6, 30), javaw, jar, "DESKTOP-01\\David", fake.toString());
        c.that(out.startsWith("SUCCESS"), "install reports schtasks' success message");
        String args = Files.readString(log);
        c.that(args.startsWith("/Create\n/F\n/TN\nThe Linked Desk daily news download\n/XML\n"), "calls schtasks /Create /F /TN <name> /XML <file>");
        byte[] written = Files.readAllBytes(saved);
        c.that((written[0] & 0xFF) == 0xFE && (written[1] & 0xFF) == 0xFF, "XML file written as UTF-16 with a byte-order mark");
        c.that(new String(written, StandardCharsets.UTF_16).contains("<Command>" + javaw.replace("&", "&amp;") + "</Command>"), "file holds the same definition");
        String tmpXml = args.lines().filter(l -> l.endsWith(".xml")).findFirst().orElse("");
        c.that(!tmpXml.isEmpty() && !Files.exists(Path.of(tmpXml)), "temporary XML file is cleaned up");

        Path failing = dir.resolve("schtasks-fail");
        Files.writeString(failing, "#!/bin/sh\necho 'ERROR: Access is denied.'\nexit 1\n");
        failing.toFile().setExecutable(true);
        String err = "";
        try { WindowsTask.install(LocalTime.of(6, 30), javaw, jar, "", failing.toString()); }
        catch (IllegalStateException e) { err = e.getMessage(); }
        c.eq("ERROR: Access is denied.", err, "a schtasks error is shown to you word for word");
    }
}
