# Catalogue: Project Gutenberg and the law

"Catalogue" searches a stored copy of the Project Gutenberg catalogue and adds a book to the import queue as an EPUB.
This note records what the feature does, what it assumes, and what to check before the app is published.
It is not legal advice. The author read the pages named below on 2026-11-14. Nobody at Project Gutenberg was contacted.

## What the feature does

- **Once a day, a GitHub Actions workflow** (`.github/workflows/catalogue.yml`, also run by hand) runs `scripts/publish-catalogue.sh`.
  It downloads Gutenberg's one bulk catalogue file, `feeds/rdf-files.tar.bz2` (about 127 MB), with one request.
  `scripts/build-catalogue.py` reduces it to the public-domain text books that have an EPUB (about 77,700 books: id, title, authors,
  languages, download count, bookshelves and subjects). The result is one xz file, `catalogue.tsv.xz` (about 2.2 MB; gzip and longer titles gave 3.5 MB).
  Titles are cut to their first line and 80 characters, and summaries, credits and file lists are left out.
  Its first line names the file format and the date Gutenberg built the feed.
- **Ratings join the file monthly.** The job asks openlibrary.org for the newest dump date (a HEAD request on the `latest` redirect) and
  compares it with the dump date in the first line of `ol-ratings.tsv.xz`, an asset of the same release. Only a newer dump is read: the
  job streams the editions, works and authors dumps in 6 parallel byte ranges (`scripts/fetch-ranges.py`; one connection from archive.org
  gave 0.6 MB/s), keeps only the lines it needs (editions that name a Project Gutenberg id, works with at least 5 ratings, their authors),
  averages the ratings (`scripts/build-ratings.py`) and uploads the small result. On other days the catalogue is rebuilt from that asset. Nothing large is stored or committed. `DRY_RUN=1 scripts/publish-catalogue.sh`
  does everything except write to the release.
- **The file is published to one release** of this repository, tagged `catalogue`, with `gh release upload catalogue ... --clobber`.
  The upload is skipped when the books are the same as the published ones. The app uses the fixed link
  `https://github.com/AlexHeavens/Screenplay/releases/download/catalogue/catalogue.tsv.xz` (`Catalogue.URL`), never `releases/latest`.
- **The app downloads the file once**, the first time "Catalogue" opens, and keeps it in its private files. It replaces the file
  at most once a day, when the screen opens. A failed or unreadable refresh keeps the old file. The app never calls Gutenberg to search.
- **Search runs on the device**: title and author words, genre chips (a bookshelf or subject that contains the genre word), English only
  by default, and a sort by title, downloads or rating. It needs no network after the first download.
- **A tap sends one request** for that book's EPUB to gutenberg.org, using `https://www.gutenberg.org/ebooks/<id>.epub3.images`, when the
  book's turn comes in the import queue. The workflow keeps only books whose feed entry lists that file.
- Every request carries `User-Agent: Screenplay/<version> (+https://github.com/AlexHeavens/Screenplay)`.
  After a failed request the app refuses to send another for 2 s, doubling to 60 s, or for the `Retry-After` the server gave.
  It never retries by itself.
- The EPUB is kept as downloaded, with its Project Gutenberg licence page. The import, the backup and the EPUB export keep that text
  (`GutenbergTest.theProjectGutenbergLicenceSurvivesReadingAndWritingTheBook`). The book keeps `source` = `gutenberg:<id>` in its details.
- The screen says that the books come from Project Gutenberg's catalogue data, copied daily to this app's GitHub page, that the app is not
  made or endorsed by Project Gutenberg, that the books are public domain in the US only, and that the reader must check the law where
  they live. It links to gutenberg.org. The app has no About screen yet, so this text is the attribution.

## What "rating" and "downloads" mean

Gutenberg has no ratings. Its one number per book is the `pgterms:downloads` count, which the "Most downloaded" sort uses. Gutenberg does
not state what period the count covers; it is the count in the feed on the day the list was built.

"Best rated" uses ratings from **Open Library** (a project of the Internet Archive). Open Library publishes monthly dumps at
[openlibrary.org/developers/dumps](https://openlibrary.org/developers/dumps), read on 2026-11-15: the ratings dump
(`ol_dump_ratings_<date>.txt.gz`, about 9 MB, one row per rating: work key, edition key, rating 1 to 5, date) and the editions dump
(`ol_dump_editions_<date>.txt.gz`, 12.6 GB), the works dump (4 GB) and the authors dump (0.8 GB). The files are at `https://archive.org/download/ol_dump_<date>/`; the `..._latest` links on
openlibrary.org redirect there, and the date in the redirect is how the job sees that a new dump exists.

- **Licence.** The [licensing page](https://openlibrary.org/developers/licensing) says the Internet Archive asserts no new copyright over
  the material in the Open Library database, that rights issues may exist on some contributions, and that the aim is a database that can
  be used openly. It names no licence and asks for no attribution. The app credits Open Library anyway: the "Catalogue" notice says the
  ratings come from Open Library, says the app is not endorsed by it, and links to openlibrary.org.
- **The join.** An Open Library edition record can carry `identifiers.project_gutenberg`. A Gutenberg book takes the average of the
  ratings of the work its edition belongs to. The counts are of Open Library readers, not Gutenberg's. Only 947 of the 77,715 books are named
  that way, so a second match joins a book to a work (with at least 5 ratings) that has the same title and an author with the same
  surname. Title matching drops case, accents, a subtitle after `:`, `;` or `(`, an "or, ..." and a leading article. If several works
  match, the one with most ratings wins; an id match always wins over a title match.
- **Ranking.** A book needs at least 5 ratings to be ranked by its average (then by count). Others follow by downloads. The row shows
  "★ 4.5 (20)".

## Measured

Dump of 2026-10-31, feed of 2026-11-14, 77,715 books:

- **Books with a rating:** 1,507 (1.9%); 947 by Gutenberg id, the rest by title and author. Of these, 1,129 (1.45%) have 5 or more ratings and
  so can be ranked. The id join alone ranks 557 books, so the title and author match more than doubles the ranked books (624 more, 1,181
  before the catalogue filter).
- **Size:** `catalogue.tsv.xz` 2,239,328 bytes without ratings, 2,242,932 with them (+3,604 bytes). `ol-ratings.tsv.xz` 286,920 bytes.
- **Cost of one monthly read:** the editions dump took about 34 minutes with 6 ranges; the works dump 9.5 minutes and the authors dump
  1.4 minutes (4 GB and 0.8 GB). Memory of the python steps stays under 400 MB.
- **Few ratings overall:** Open Library has 19,317 works with 5 or more ratings; most Gutenberg books are not among them, so a rating
  shows on few rows. Sorting by rating is useful for the well-known books.

## What was read, and what it says

- [Terms of use](https://www.gutenberg.org/policy/terms_of_use.html): the site is for human users only; automated access can lead to an
  IP block. More than about 100 books a day belongs on a mirror. OPDS clients must use a proper user agent with a contact address and must
  make no more requests than a browser user. Large-scale deep-linking to EPUB files is not allowed, and "mock front-ends" that leave the
  cost of serving files to Gutenberg are not tolerated. Non-US readers must check their own copyright law.
- [Robot access](https://www.gutenberg.org/policy/robot_access.html): the exceptions for robots are the harvest links, mirrors and the catalogue feeds.
- [Licence](https://www.gutenberg.org/policy/license.html) and [permissions](https://www.gutenberg.org/policy/permission.html): the name
  "Project Gutenberg" is a registered trademark. Using it in a title, in promotional material or in a description means the copies must be
  verbatim (reformatting to another file format is allowed) and free of charge. A linked reference in an acknowledgement is not trademark use.
  The catalogue's copyright flag is sometimes wrong. Gutenberg also says that `www.gutenberg.org` blocks deep links by HTTP referrer.

## Assumptions that are not verified

1. **EPUB downloads from gutenberg.org by an app.** The catalogue's link (`/ebooks/<id>.epub3.images`) redirected to
   `/cache/epub/<id>/...epub` and returned 200 for one manual `curl` with the user agent above and no referrer. Nobody tested it
   from the app at scale. Gutenberg may treat an app that downloads files on demand as the "mock front-end" or the deep-linking its terms forbid.
   A mirror or the harvest route might be the intended way. Ask Gutenberg before publishing.
2. **The bulk catalogue and its copy.** Gutenberg offers `rdf-files.tar.bz2` for download and says the feeds may be fetched; the workflow
   fetches it once a day, which is no faster than the feed changes. Republishing a reduced copy of the facts (titles, authors, counts) on
   GitHub was not cleared with Gutenberg. GitHub release downloads have no stated rate limit for this use; a hosting change is one constant (`Catalogue.URL`).
   The workflow itself has run only by hand so far (CI minutes were used up); the first file was published by running the same script locally.
3. **Copyright.** The list holds only books whose `dcterms:rights` says "Public domain in the USA", of type Text. The catalogue can be wrong.
   The app cannot know the law in the reader's country.
4. **Verbatim copies.** The EPUB export is text only: it drops images and splits long paragraphs. Gutenberg calls a format change acceptable,
   but nobody confirmed this for a rebuilt EPUB. The licence text stays in the export.
5. **Trademark.** The app is assumed to be free and to name Project Gutenberg only as a linked reference. If the app is sold, shows ads, or the
   store listing names Project Gutenberg in its title or promotional text, the trademark rules above apply and may need permission.
6. **No endorsement.** The wording says the app is not endorsed. Gutenberg did not approve it.

## Check before publishing

- **Gutenberg**: read the terms of use and the robot page again (they change). Decide with Gutenberg whether EPUB downloads through an app are allowed.
  Decide whether the app should use a mirror instead.
- **Play Store content policy**: check the rules for apps that let users download books (copyright, user-generated and "impersonation"
  sections), and what the data-safety form needs.
- **Privacy policy**: say that opening "Catalogue" downloads the book list from github.com (at most once a day), and a tap sends the book's
  link to gutenberg.org, with the app's user agent and the device's IP address. Search words never leave the device. The app has no account, no analytics and no tracking.
- **`INTERNET` permission**: the manifest comment says why. Only "Catalogue" uses the network.
- **Store text**: do not put "Project Gutenberg" in the app name, icon or promotional text unless the trademark rules are met.
- **Catalogue release**: check that the `catalogue` release still holds a current file (the workflow's last run) and that the repository is public
  when the app is. `Catalogue.URL` is the one line to change if the file moves.
