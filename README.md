# The Linked Desk 1.7

A Windows desktop app that downloads world news every day, keeps every article, groups reports of the same event into one story, and links related stories across World, Finance, Politics, Society & Climate, and Tech & Science. Follow your own topics and search everything you've ever downloaded.

## Run it
**Easiest (no Java needed):** run `build-installer.bat` once on a PC with the JDK 21+, then use what it makes:
- `dist\The Linked Desk-1.7.0.msi` if the WiX Toolset 3 is installed (Start-menu entry, desktop shortcut, uninstaller; installs for your user, no admin rights), or
- `dist\TheLinkedDesk-1.7.0-windows.zip` otherwise: unzip anywhere and run `The Linked Desk.exe`.

**Or from the jar:** with Java 21+ installed, double-click **Run The Linked Desk.bat** (or `LinkedDesk.jar`).

Opening the app a second time just brings the open window to the front.

## What's in the window
- **Briefing** (opens first, and after each daily download; switch off in Settings): the top 5 stories of the last 24 hours in each desk, ranked by how many outlets cover them and how fast coverage is growing, plus links between desks that are new today and storylines that moved on. Click a story to read it beside the briefing.
- **Stories**: each event once, with every outlet's coverage, linked stories and the shared names that link them. Filter by time window, desk, words, cross-desk links only, or **Unread only**. Unread stories are bold with a dot; a story you read shows "new" when fresh coverage joins it. Each story shows **how its coverage spreads over regions** ("13 outlets in 4 regions"), with a warning for a **single source** or **one region** only.
- **Saved**: stories you starred (☆ Star on any story, or press S), as they stand now.
- **Watchlist** (press L): subjects you follow across all desks, such as *Fed*, *"strait of hormuz" OR tankers* or *Lockheed -stock*. Names match their aliases too (a watch on *Fed* also finds *Federal Reserve*). Each watch shows how many stories are **new since you last looked**; matching stories are marked in **Stories** ("watching: Fed") and the new ones are tinted. Add one with **Watch this subject…** on any story or **Tools > Watch a subject…**.
- **Markets**: tickers you follow (NVDA, LMT, BA…). Stories that name the company or its symbol (`$NVDA`, `(NVDA)`, `NASDAQ: NVDA`, *Nvidia*) are listed by day, and also shown on the **Finance** desk. Well-known symbols fill in their company names; add your own names for others.
- **Storylines**: one event followed across days (day 1 "3 dead in factory fire", day 3 "death toll rises to 24"). Each story also shows **How this story developed**.
- **Threads**: topics that recur across several linked stories, with a **timeline** (one lane per desk; click a marker to open the story). Storylines have the same timeline.
- **Map**: the linked stories as a network of dots and lines, coloured by desk. Filter by desk or thread; drag, zoom, hover, click to read.
- **World**: where the stories are happening, on a simplified built-in world map (no internet needed) with a circle per country; click a country or pick it in the list to see its stories.
- **Ask** (needs an Anthropic API key): ask anything about your saved news ("What happened with the Strait of Hormuz this month?"). The app finds the matching stories and Claude answers from them only, citing each one as [S1], [S2]; click a citation to read the story. Nothing matching = Claude isn't asked.
- **Topics**: your own subjects (e.g. *hypersonic OR "glide vehicle" -game*). Each is searched across world news (Google News and Bing News) on every daily download. Topic stories also join the main Stories view and link to the rest. Add, edit, pause, delete, or download now. Deleting a topic keeps what it found.
- **Search**: searches every article ever saved (not just the time window), through a full-text index kept in `data\index` (built in the background the first time; about 11 s per million articles). With a million articles a search takes well under a second. `"exact phrase"`, `OR`, `-leave out`, and filters: `source:bbc`, `source:"al jazeera"`, `desk:finance`, `after:2026-11-01`, `before:…`, `after:7d`, `region:europe`, `is:unread`, `is:starred` (also in the Stories filter box). **Search live news** fetches fresh articles for the same words now; **Save as topic** turns a search into a daily topic.
- **Podcasts, YouTube and social feeds**: in Settings > Edit feed list, **Add a podcast, YouTube channel or social account…** takes the link from your browser (youtube.com/@channel or a playlist, podcasts.apple.com/…, a Mastodon or Bluesky account, reddit.com/r/…) and finds its feed. Videos, episodes and posts group and link with the text coverage of the same event and are marked Video / Podcast / Post.
- **Other languages**: articles in Spanish, French, German, Russian, Arabic, Chinese and other languages are recognised, and Claude translates their headlines after the daily download (at most 150 a day by default, within your budget; each is translated once). They then group with the English coverage, Search finds them by their English words, and the story page shows "Translated from Spanish" with the original headline. Without a key, country and leader names in other languages (Ucrania, Украина, Libia…) still tie them to English stories.
- **Full text**: when a feed includes the whole article (many blogs, some news sites), the app keeps it (`data\fulltext`) and shows **Full text** on the story page; Search looks inside it too. Nothing is downloaded from the websites themselves, and nothing behind a paywall.
- **Phone**: in Settings > Phone choose a folder your phone syncs (e.g. in OneDrive). After each daily download the briefing is written there as a page made for a phone (`The Linked Desk briefing.html`, light or dark like the phone, with what's new on your watchlist, and the last two weeks in `briefings\`). Open it in the OneDrive, Dropbox or Files app. Nothing is put online.
- **News > Feed health**: every feed's status, last success, failures in a row, new articles per day and last error. A feed failing 7 times in a row is paused and retried weekly. Retry, change address, remove, or **Test all feeds**.
- **View > Logs**: every download in the last 30 days with per-feed detail, plus the error log.
- **Correct the app** on any story: **✓ Right / ✗ Wrong** on each link (right links are always shown; wrong ones are hidden and the words behind them count less), **Not this story** on an article that was grouped wrongly, and **Move to another desk…** (this story, everything from an outlet, or every headline with a word; optionally shown in a second desk too).
- **Tools > Sources**: every outlet with its article count. **Mute** one to hide it from Stories, the Briefing, Search and Ask (its articles stay in the archive; search `source:` still finds them), or give it a **weight** (0.25-5) so it counts more or less toward *Most covered*. **Mute this outlet** also sits under every article.
- **Notifications** (Settings): after each download, one Windows notification groups the new matches by watch ("Fed 2 · Hormuz 1" and the first headline), from the window or from the daily task when the app is closed. Optional: new links between desks. **Quiet hours** (e.g. 22:00-07:00) hold them; they're shown together afterwards. Clicking the tray icon opens the Watchlist.
- **Email digest** (Settings): the briefing by email once a day after the daily download, with headlines linking to the articles and a plain-text version. Any SMTP server (STARTTLS, SSL); the password is stored encrypted. For Gmail use smtp.gmail.com, port 587, STARTTLS and an **app password**. **Send test email now** tries it at once.
- **Export** (News > Export…, Ctrl+E): what's on screen (the open story, every story in the list, a thread, a storyline or the briefing) as a web page (.html), a Word document (.docx), Markdown (.md) or a spreadsheet for Excel (.csv, one row per article). Every headline keeps its link. **Copy** on any story puts it on the clipboard with its sources; pasted into Word, Outlook or Gmail the links come along.
- **News > Weekly report…**: any week in your archive (Monday to Sunday): top stories per desk, how stories developed, connections across desks, threads, your watchlist and tickers, and what Claude's daily digests said to watch. Preview it, then save as Word, HTML or Markdown (Word can save it as PDF), or copy it.
- **Tools > Archive and backup**: the archive's size and the search index; **compress months older than 6 months** (to about a fifth of the size, still read and searched as before; done after the daily download); **export any month as CSV** for Excel; **back up** to a folder you choose (e.g. inside OneDrive) now or automatically every week, keeping the newest 5; **restore** a backup (on this PC or a new one: what was there is moved aside into `before-restore-…`, not deleted).
- **Tools > Calibrate linking**: how well grouping and linking score on a labelled sample of real news and on your verdicts, and **Find best settings** per desk.
- **Tools > Words behind links**: which words create the most links; add a too-generic one to the stop words in one click. **Tools > Your corrections** lists your verdicts, desk rules and word lists (all removable).
- **Keyboard**: J/K next/previous story, / filter or search box, S star, U mark unread, F download, B briefing, L watchlist, M map, W world map, A ask, Ctrl+E export, ? all shortcuts (Help menu).
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
- `data\articles-YYYY-MM.tsv` – every article ever downloaded (opens in Excel); months older than 6 months become `articles-YYYY-MM.tsv.gz` (any zip tool opens them, or Tools > Archive and backup > Save as CSV). `data\index\` – the search index (rebuilt by itself; safe to delete). Nothing is deleted or capped. `data\backup\` – weekly copy of the current month. `data\topic-hits.tsv` – which topic found which article.
- `feed-health.tsv`, `settings.properties`, `logs\` (`fetch-YYYY-MM.log`, `errors.log`).
- `claude-usage.tsv` (every Claude request and its cost), `claude-cache.tsv` (answers already paid for), `link-checks.tsv` (Claude's verdicts on links), `digests\YYYY-MM-DD.txt` (each day's digest).
- `translations.tsv` (English versions of headlines in other languages), `data\fulltext\` (full texts from feeds that include them).
- `watchlist.txt` (*Name | search words | yes/no*), `watch-seen.tsv` (when you last opened each watch), `sources.txt` (*outlet = mute* or *outlet = 2*), `tickers.txt` (*SYMBOL = company names*), `pending-alerts.txt` (notifications held during quiet hours).
- `data\read.txt` (what you've read), `data\starred.tsv` (your stars), `regions.txt` (where outlets are based; add your own).
- How stories are linked (plain text, edit by hand if you like; changes apply straight away): `aliases.txt` (different names for one thing: *Federal Reserve = Fed*), `stopwords.txt` (words that never link stories), `link-feedback.tsv` (your Right/Wrong/Not this story verdicts), `desk-rules.tsv` (your desk moves), `calibration.properties` (thresholds per desk).
- The archive survives crashes and power cuts: a line cut off mid-write is skipped and the file is repaired on the next save.
- The Anthropic API key (optional, for "Ask Claude why") and the email password are stored **encrypted**: on Windows with your Windows account's protection (DPAPI).

## Command line
```
LinkedDesk.jar               open the window
LinkedDesk.jar --fetch       download once, notify, email the briefing, compress old months, update the index, weekly backup, exit (the daily task)
LinkedDesk.jar --summary     print stories, links and threads (--all for all history)
LinkedDesk.jar --print-task  show what the daily task will run
LinkedDesk.jar --report [YYYY-MM-DD] [file.docx|.html|.md]   weekly report for the week holding that day (default: last week)
LinkedDesk.jar --backup      back up now to the folder chosen in Tools > Archive and backup
```

## Build and test
Only the JDK is needed (no downloads): `build.bat` (Windows) or `build.sh`. The build runs 358 automated checks (Claude is replaced by a local stand-in, so no key or cost is needed) and stops if any fail (`--skip-tests` to skip).
- Line coverage (JDK only): `java -cp out;out-test com.linkeddesk.Coverage com.linkeddesk.feeds.FeedParser com.linkeddesk.store.ArticleStore`
- Performance: `java -Xmx2g -cp out;out-test com.linkeddesk.Benchmark 250000`

Code layout (`src/com/linkeddesk`): `app` (entry point, settings, encrypted secrets, logs, single window), `feeds` (feed list, topics, downloader, parser, feed health), `store` (archive, topic hits), `analysis` (desks, names and aliases, grouping, storylines, linking, threads, your corrections, calibration, search words), `service` (download/search coordination), `schedule` (daily timer, Windows task), `ai` (Claude: budget and usage, cache, digest, link checks, questions), `ui` (window, tabs, dialogs). Tests in `test/`.
