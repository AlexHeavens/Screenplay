# Find books: Project Gutenberg and the law

"Find books" searches the Project Gutenberg catalogue and adds a book to the import queue as an EPUB.
This note records what the feature does, what it assumes, and what to check before the app is published.
It is not legal advice. The author read the pages named below on 2026-11-14. Nobody at Project Gutenberg or Gutendex was contacted.

## What the feature does

- It asks Gutendex (`Gutendex.BASE_URL`, one constant), a free third-party JSON service over the Project Gutenberg catalogue.
  It never loads gutenberg.org pages and never scrapes a search page.
- A search sends one request. It starts only when the reader taps Search or a genre. Changing the popularity filter sends none.
  The screen opens with no request. Nothing runs in the background. Nothing is fetched ahead of a tap.
- A tap on a result sends one request for that book's EPUB, using the link from the catalogue entry (`formats["application/epub+zip"]`).
  The app fetches only `https` links on gutenberg.org. A book without an EPUB shows "No EPUB available" and sends no request.
- Every request carries `User-Agent: Screenplay/<version> (+https://github.com/AlexHeavens/Screenplay)`.
  Gutenberg asks for a client name and a contact address (OPDS section of its terms).
- Search results are cached in memory for 5 minutes. After a failed request the app refuses to send another for 2 s, doubling to 60 s,
  or for the `Retry-After` the server gave. It never retries by itself.
- The EPUB is kept as downloaded, with its Project Gutenberg licence page. The import, the backup and the EPUB export keep that text
  (`GutendexTest.theProjectGutenbergLicenceSurvivesReadingAndWritingTheBook`). The book keeps `source` = `gutenberg:<id>` in its details.
- The screen says that the books come from Project Gutenberg, that the app is not made or endorsed by it, that the books are public
  domain in the US only, and that the reader must check the law where they live. It links to gutenberg.org.
  The app has no About screen yet, so this text is the attribution.

## What "rating" means

Gutenberg and Gutendex have no ratings. The filter is **Popularity (downloads)** from the `download_count` field: Any, Popular
(5,000 or more) and Very popular (20,000 or more). The limits come from two real first pages (2026-11-14):
the 32 Dickens books had 2,435 to 50,704 downloads, and the 32 most downloaded English science fiction books had 6,782 to 182,729.
Results are sorted by downloads. The filter works on the pages already loaded. Gutendex does not state what period the count covers.

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
2. **Gutendex.** Its own terms, rate limits and long-term availability were not checked. Measured on 2026-11-14 from this machine: one request took
   19 s, one 54 s, one 136 s, and one failed after 150 s. The client waits 60 s for a read, then shows a failure message.
3. **Copyright.** Books with `copyright: true` and any `media_type` other than `Text` are hidden. A book with `copyright: null` is shown.
   The catalogue can be wrong. The app cannot know the law in the reader's country.
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
- **Privacy policy**: say that a search sends the search words and genre, and a tap sends the book's link, over the internet to gutendex.com
  and gutenberg.org, with the app's user agent and the device's IP address. The app has no account, no analytics and no tracking.
- **`INTERNET` permission**: the manifest comment says why. Only "Find books" uses the network.
- **Store text**: do not put "Project Gutenberg" in the app name, icon or promotional text unless the trademark rules are met.
- **Gutendex**: check its terms and whether it still runs. `Gutendex.BASE_URL` is the one line to change.
