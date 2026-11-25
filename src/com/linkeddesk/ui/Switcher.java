package com.linkeddesk.ui;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One tab holding several views, with a row of buttons to switch between them (UI-F16: fewer tabs).
 * The first view's label is also the tab's title.
 */
final class Switcher extends JPanel {
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
    private final ButtonGroup group = new ButtonGroup();
    private final List<Component> views = new ArrayList<>();
    private final List<JToggleButton> buttons = new ArrayList<>();
    private Component current;
    Consumer<Component> onSwitch = c -> { };

    Switcher() {
        super(new BorderLayout());
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.lineColor()));
        add(bar, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
    }

    Switcher addView(String label, Component view) {
        String key = "v" + views.size();
        views.add(view);
        body.add(view, key);
        JToggleButton b = new JToggleButton(label);
        b.setFocusable(false);
        b.addActionListener(e -> show(view));
        group.add(b);
        buttons.add(b);
        bar.add(b);
        if (current == null) { current = view; b.setSelected(true); }
        return this;
    }

    boolean holds(Component c) { return views.contains(c); }
    Component current() { return current; }
    boolean isFirst(Component c) { return !views.isEmpty() && views.get(0) == c; }

    void show(Component view) {
        int i = views.indexOf(view);
        if (i < 0) return;
        cards.show(body, "v" + i);
        buttons.get(i).setSelected(true);
        current = view;
        onSwitch.accept(view);
    }

    void label(Component view, String text) {
        int i = views.indexOf(view);
        if (i >= 0) buttons.get(i).setText(text);
    }
}
