package com.linkeddesk;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;

import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Line coverage with only the JDK: runs the test suite under the Java Debug Interface, sets a breakpoint
 * on every line of the classes being measured, and records each line the tests reach.
 * Usage: java -cp out:out-test com.linkeddesk.Coverage com.linkeddesk.feeds.FeedParser com.linkeddesk.store.ArticleStore
 */
public final class Coverage {
    public static void main(String[] targets) throws Exception {
        LaunchingConnector lc = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> args = lc.defaultArguments();
        args.get("main").setValue("com.linkeddesk.TestMain");
        args.get("options").setValue("-cp " + System.getProperty("java.class.path"));
        VirtualMachine vm = lc.launch(args);
        drain(vm.process().getInputStream());
        drain(vm.process().getErrorStream());

        Map<String, Set<Integer>> all = new TreeMap<>(), hit = new TreeMap<>();
        for (String t : targets) {
            for (String pattern : new String[]{t, t + "$*"}) {
                ClassPrepareRequest cpr = vm.eventRequestManager().createClassPrepareRequest();
                cpr.addClassFilter(pattern);
                cpr.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                cpr.enable();
            }
        }
        vm.resume();
        boolean running = true;
        while (running) {
            EventSet set = vm.eventQueue().remove();
            for (Event e : set) {
                if (e instanceof ClassPrepareEvent cp) {
                    ReferenceType type = cp.referenceType();
                    String outer = type.name().split("\\$")[0];
                    for (Method m : type.methods()) {
                        try {
                            for (Location loc : m.allLineLocations()) {
                                all.computeIfAbsent(outer, k -> new TreeSet<>()).add(loc.lineNumber());
                                BreakpointRequest br = vm.eventRequestManager().createBreakpointRequest(loc);
                                br.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                                br.enable();
                            }
                        } catch (com.sun.jdi.AbsentInformationException ignored) { }
                    }
                } else if (e instanceof BreakpointEvent bp) {
                    Location loc = bp.location();
                    hit.computeIfAbsent(loc.declaringType().name().split("\\$")[0], k -> new TreeSet<>()).add(loc.lineNumber());
                    bp.request().disable();
                } else if (e instanceof VMDeathEvent || e instanceof VMDisconnectEvent) {
                    running = false;
                }
            }
            try { set.resume(); } catch (com.sun.jdi.VMDisconnectedException ex) { running = false; }
        }
        System.out.println("Line coverage while running the test suite:");
        for (var en : all.entrySet()) {
            Set<Integer> h = hit.getOrDefault(en.getKey(), Set.of());
            Set<Integer> missed = new TreeSet<>(en.getValue());
            missed.removeAll(h);
            System.out.printf("  %-40s %3d of %3d lines  %5.1f%%   not reached: %s%n", en.getKey(), h.size(), en.getValue().size(),
                    100.0 * h.size() / en.getValue().size(), missed);
        }
    }

    private static void drain(InputStream in) {
        Thread t = new Thread(() -> { try { in.transferTo(java.io.OutputStream.nullOutputStream()); } catch (Exception ignored) { } });
        t.setDaemon(true);
        t.start();
    }
}
