# Find books: Project Gutenberg and the law

"Find books" searches a stored copy of the Project Gutenberg catalogue and adds a book to the import queue as an EPUB.
This note records what the feature does, what it assumes, and what to check before the app is published.
It is not legal advice. The author read the pages named below on 2026-11-14. Nobody at Project Gutenberg was contacted.

## What the feature does

- **Once a day, a GitHub Actions workflow** (`.github/workflows/catalogue.yml`, also run by hand) runs `scripts/publish-catalogue.sh`.
  It downloads Gutenberg's one bulk catalogue file, `feeds/rdf-files.tar.bz2` (about 127 MB), with one request.
  `scripts/build-catalogue.py` reduces it to the public-domain text books that have an EPUB (about 77,700 books: id, title, authors,
  languages, download count, bookshelves and subjects). The result is one xz file, `catalogue.tsv.xz` (about 2.2 MB; gzip and longer titles gave 3.5 MB).
  Titles are cut to their first line and 80 characters, and summaries, credits and file lists are left out.
  Its first line names the file format and the date Gutenberg built the feed.
- **The file is published to one release** of this repository, tagged `catalogue`, with `gh release upload catalogue ... --clobber`.
  The upload is skipped when the books are the same as the published ones. The app uses the fixed link
  `https://github.com/AlexHeavens/Screenplay/releases/download/catalogue/catalogue.tsv.xz` (`Catalogue.URL`), never `releases/latest`.
- **The app downloads the file once**, the first time "Find books" opens, and keeps it in its private files. It replaces the file
  at most once a day, when the screen opens. A failed or unreadable refresh keeps the old file. The app never calls Gutenberg to search.
- **Search runs on the device**: title and author words, genre chips (a bookshelf or subject that contains the genre word), English only
  by default, and a sort by title or by downloads. It needs no network after the first download.
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

## What "rating" means

Gutenberg has no ratings. The only measure is the catalogue's `pgterms:downloads` count, which the list file carries for every book.
The "Sort by" chips offer **Title A–Z** (the default) and **Most downloaded**. Gutenberg does not state what period the count covers.
The count is the one in the feed on the day the list was built.

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
- **Privacy policy**: say that opening "Find books" downloads the book list from github.com (at most once a day), and a tap sends the book's
  link to gutenberg.org, with the app's user agent and the device's IP address. Search words never leave the device. The app has no account, no analytics and no tracking.
- **`INTERNET` permission**: the manifest comment says why. Only "Find books" uses the network.
- **Store text**: do not put "Project Gutenberg" in the app name, icon or promotional text unless the trademark rules are met.
- **Catalogue release**: check that the `catalogue` release still holds a current file (the workflow's last run) and that the repository is public
  when the app is. `Catalogue.URL` is the one line to change if the file moves.
