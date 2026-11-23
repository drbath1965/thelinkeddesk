# The Linked Desk 1.4

A Windows desktop app that downloads world news every day, keeps every article, groups reports of the same event into one story, and links related stories across World, Finance, Politics, Society & Climate, and Tech & Science. Follow your own topics and search everything you've ever downloaded.

## Run it
**Easiest (no Java needed):** run `build-installer.bat` once on a PC with the JDK 21+, then use what it makes:
- `dist\The Linked Desk-1.4.0.msi` if the WiX Toolset 3 is installed (Start-menu entry, desktop shortcut, uninstaller; installs for your user, no admin rights), or
- `dist\TheLinkedDesk-1.4.0-windows.zip` otherwise: unzip anywhere and run `The Linked Desk.exe`.

**Or from the jar:** with Java 21+ installed, double-click **Run The Linked Desk.bat** (or `LinkedDesk.jar`).

Opening the app a second time just brings the open window to the front.

## What's in the window
- **Briefing** (opens first, and after each daily download; switch off in Settings): the top 5 stories of the last 24 hours in each desk, ranked by how many outlets cover them and how fast coverage is growing, plus links between desks that are new today and storylines that moved on. Click a story to read it beside the briefing.
- **Stories**: each event once, with every outlet's coverage, linked stories and the shared names that link them. Filter by time window, desk, words, cross-desk links only, or **Unread only**. Unread stories are bold with a dot; a story you read shows "new" when fresh coverage joins it. Each story shows **how its coverage spreads over regions** ("13 outlets in 4 regions"), with a warning for a **single source** or **one region** only.
- **Saved**: stories you starred (☆ Star on any story, or press S), as they stand now.
- **Storylines**: one event followed across days (day 1 "3 dead in factory fire", day 3 "death toll rises to 24"). Each story also shows **How this story developed**.
- **Threads**: topics that recur across several linked stories, with a **timeline** (one lane per desk; click a marker to open the story). Storylines have the same timeline.
- **Map**: the linked stories as a network of dots and lines, coloured by desk. Filter by desk or thread; drag, zoom, hover, click to read.
- **World**: where the stories are happening, on a simplified built-in world map (no internet needed) with a circle per country; click a country or pick it in the list to see its stories.
- **Ask** (needs an Anthropic API key): ask anything about your saved news ("What happened with the Strait of Hormuz this month?"). The app finds the matching stories and Claude answers from them only, citing each one as [S1], [S2]; click a citation to read the story. Nothing matching = Claude isn't asked.
- **Topics**: your own subjects (e.g. *hypersonic OR "glide vehicle" -game*). Each is searched across world news (Google News and Bing News) on every daily download. Topic stories also join the main Stories view and link to the rest. Add, edit, pause, delete, or download now. Deleting a topic keeps what it found.
- **Search**: searches every article ever saved (not just the time window). `"exact phrase"`, `OR`, `-leave out`, and filters: `source:bbc`, `source:"al jazeera"`, `desk:finance`, `after:2026-11-01`, `before:…`, `after:7d`, `region:europe`, `is:unread`, `is:starred` (also in the Stories filter box). **Search live news** fetches fresh articles for the same words now; **Save as topic** turns a search into a daily topic.
- **News > Feed health**: every feed's status, last success, failures in a row, new articles per day and last error. A feed failing 7 times in a row is paused and retried weekly. Retry, change address, remove, or **Test all feeds**.
- **View > Logs**: every download in the last 30 days with per-feed detail, plus the error log.
- **Correct the app** on any story: **✓ Right / ✗ Wrong** on each link (right links are always shown; wrong ones are hidden and the words behind them count less), **Not this story** on an article that was grouped wrongly, and **Move to another desk…** (this story, everything from an outlet, or every headline with a word; optionally shown in a second desk too).
- **Tools > Calibrate linking**: how well grouping and linking score on a labelled sample of real news and on your verdicts, and **Find best settings** per desk.
- **Tools > Words behind links**: which words create the most links; add a too-generic one to the stop words in one click. **Tools > Your corrections** lists your verdicts, desk rules and word lists (all removable).
- **Keyboard**: J/K next/previous story, / filter or search box, S star, U mark unread, F download, B briefing, M map, W world map, A ask, ? all shortcuts (Help menu).
- **Tools > Settings > Reading**: light or dark colours, text size 90-175% (on top of Windows' display scaling, which the app follows).
- **Download news now** turns into **Cancel download** while running; articles already downloaded are kept.

## Claude (optional, needs an Anthropic API key in Tools > Settings)
- **Daily digest**: after the daily download Claude writes "How today connects" at the top of the briefing: what the reports say, kept apart from Claude's own reading, and what to watch, each point citing its stories.
- **Link checks**: Claude checks the day's top new links (10 by default) and marks each "✓ Checked by Claude: real" or "⚠ Likely coincidence", with a reason. A link is never checked twice.
- **Ask Claude why** on any link, and the **Ask** tab.
- **Budget**: a monthly limit in US dollars (default $5). Before each request the app estimates its largest possible cost and doesn't send it if that would pass the limit; you see why instead. Costs are worked out from the tokens Anthropic reports and Anthropic's list prices (Claude Sonnet 5.5: $2 / $10 per million input / output tokens; enter your own prices if they differ). Answers are cached, so the same question is never paid for twice. **Tools > Claude usage** shows the month by feature. Typical use (digest + 10 link checks a day, a few questions) is about $1 a month.

## Daily downloads
- While the app is open it downloads once a day at the time set in **Tools > Settings** (default 06:30), on start-up if the news is more than 20 hours old, catches up if a whole day was missed, and retries every 30 minutes after a failure.
- **Settings > Run daily even when the app is closed** adds a Windows Task Scheduler job for your user (no admin rights). If the PC is off or asleep at that time, it runs as soon as the PC is back on and online. **Check daily task** shows what Windows reports (Last Result 0 = worked).
- Only one download runs at a time: if the scheduled task is running, the window waits for it, and vice versa.

## Your data (`%USERPROFILE%\LinkedDesk`)
- `feeds.txt` – feed list (64 to start; edit freely, no limit). `topics.txt` – your topics.
- `data\articles-YYYY-MM.tsv` – every article ever downloaded (opens in Excel). Nothing is deleted or capped. `data\backup\` – weekly copy of the current month. `data\topic-hits.tsv` – which topic found which article.
- `feed-health.tsv`, `settings.properties`, `logs\` (`fetch-YYYY-MM.log`, `errors.log`).
- `claude-usage.tsv` (every Claude request and its cost), `claude-cache.tsv` (answers already paid for), `link-checks.tsv` (Claude's verdicts on links), `digests\YYYY-MM-DD.txt` (each day's digest).
- `data\read.txt` (what you've read), `data\starred.tsv` (your stars), `regions.txt` (where outlets are based; add your own).
- How stories are linked (plain text, edit by hand if you like; changes apply straight away): `aliases.txt` (different names for one thing: *Federal Reserve = Fed*), `stopwords.txt` (words that never link stories), `link-feedback.tsv` (your Right/Wrong/Not this story verdicts), `desk-rules.tsv` (your desk moves), `calibration.properties` (thresholds per desk).
- The archive survives crashes and power cuts: a line cut off mid-write is skipped and the file is repaired on the next save.
- The Anthropic API key (optional, for "Ask Claude why") is stored **encrypted**: on Windows with your Windows account's protection (DPAPI).

## Command line
```
LinkedDesk.jar               open the window
LinkedDesk.jar --fetch       download once and exit (used by the daily task)
LinkedDesk.jar --summary     print stories, links and threads (--all for all history)
LinkedDesk.jar --print-task  show what the daily task will run
```

## Build and test
Only the JDK is needed (no downloads): `build.bat` (Windows) or `build.sh`. The build runs 358 automated checks (Claude is replaced by a local stand-in, so no key or cost is needed) and stops if any fail (`--skip-tests` to skip).
- Line coverage (JDK only): `java -cp out;out-test com.linkeddesk.Coverage com.linkeddesk.feeds.FeedParser com.linkeddesk.store.ArticleStore`
- Performance: `java -Xmx2g -cp out;out-test com.linkeddesk.Benchmark 250000`

Code layout (`src/com/linkeddesk`): `app` (entry point, settings, encrypted secrets, logs, single window), `feeds` (feed list, topics, downloader, parser, feed health), `store` (archive, topic hits), `analysis` (desks, names and aliases, grouping, storylines, linking, threads, your corrections, calibration, search words), `service` (download/search coordination), `schedule` (daily timer, Windows task), `ai` (Claude: budget and usage, cache, digest, link checks, questions), `ui` (window, tabs, dialogs). Tests in `test/`.
