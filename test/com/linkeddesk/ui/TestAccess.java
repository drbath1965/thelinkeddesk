package com.linkeddesk.ui;

import com.linkeddesk.ai.ArchiveQA;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.service.NewsService;

/** Lets tests outside the ui package render pages the way the window does. */
public final class TestAccess {
    private TestAccess() {}

    public static String storyPage(StoryCluster c, NewsService svc) {
        return Html.story(c, true, null, true, new Html.Extras(false, svc.regions().spread(c), false, (a, b) -> svc.linkChecks().find(a, b)));
    }

    /** What Copy puts on the clipboard. */
    public record Clip(String html, String text) {
        public Clip(com.linkeddesk.export.Doc d) {
            this(get(d, java.awt.datatransfer.DataFlavor.allHtmlFlavor), get(d, java.awt.datatransfer.DataFlavor.stringFlavor));
        }
        static String get(com.linkeddesk.export.Doc d, java.awt.datatransfer.DataFlavor f) {
            try { return (String) ExportUi.selection(d).getTransferData(f); } catch (Exception e) { return ""; }
        }
    }

    public static String framingHtml(com.linkeddesk.ai.Framing.Result r) { return Html.framing(r, ""); }

    public static String answerPage(ArchiveQA.Answer a) { return Html.answer(a, ""); }
}
