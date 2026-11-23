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

    public static String answerPage(ArchiveQA.Answer a) { return Html.answer(a, ""); }
}
