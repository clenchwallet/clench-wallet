from __future__ import annotations

import hashlib
from html.parser import HTMLParser
import importlib.util
import json
from pathlib import Path
import re
import shutil
import tempfile
import unittest
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET


WEBSITE_ROOT = Path(__file__).resolve().parents[1]
BUILD_PATH = WEBSITE_ROOT / "build.py"
SPEC = importlib.util.spec_from_file_location("clench_website_build", BUILD_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot import {BUILD_PATH}")
site_build = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(site_build)


class DocumentParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.ids: list[str] = []
        self.hrefs: list[str] = []
        self.asset_urls: list[str] = []
        self.canonicals: list[str] = []
        self.stylesheets: list[str] = []
        self.header_count = 0
        self.footer_count = 0
        self.main_count = 0
        self.skip_link_count = 0
        self.script_count = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = {key: value or "" for key, value in attrs}
        classes = set(values.get("class", "").split())
        if "id" in values:
            self.ids.append(values["id"])
        if tag == "header":
            self.header_count += 1
        elif tag == "footer":
            self.footer_count += 1
        elif tag == "main" and values.get("id") == "main":
            self.main_count += 1
        elif tag == "script":
            self.script_count += 1
        if tag == "a" and "skip-link" in classes and values.get("href") == "#main":
            self.skip_link_count += 1
        if "href" in values:
            self.hrefs.append(values["href"])
        if "src" in values:
            self.asset_urls.append(values["src"])
        if "srcset" in values:
            for candidate in values["srcset"].split(","):
                url = candidate.strip().split(maxsplit=1)[0]
                if url:
                    self.asset_urls.append(url)
        if tag == "link":
            rels = set(values.get("rel", "").split())
            href = values.get("href", "")
            if "canonical" in rels:
                self.canonicals.append(href)
            if "stylesheet" in rels:
                self.stylesheets.append(href)
            if rels & {"stylesheet", "icon", "apple-touch-icon"}:
                self.asset_urls.append(href)


def parse_document(text: str) -> DocumentParser:
    parser = DocumentParser()
    parser.feed(text)
    parser.close()
    return parser


def write_fixture(root: Path) -> None:
    (root / "src" / "partials").mkdir(parents=True)
    (root / "src" / "pages").mkdir(parents=True)
    (root / "public" / "assets").mkdir(parents=True)
    (root / "public" / "assets" / "site.css").write_text("body {}\n", encoding="utf-8")
    (root / "src" / "layout.html").write_text(
        "<!doctype html>\n<title>{{title}}</title>\n"
        "<link rel=canonical href=\"{{canonical_url}}\">\n"
        "<link rel=stylesheet href=\"/assets/site.css?v={{css_version}}\">\n"
        "{{header}}\n<main id=\"main\" class=\"{{main_class}}\">"
        "{{page_content}}</main>\n{{footer}}\n",
        encoding="utf-8",
    )
    (root / "src" / "partials" / "header.html").write_text(
        '<header><a class="skip-link" href="#main">Skip</a></header>',
        encoding="utf-8",
    )
    (root / "src" / "partials" / "footer.html").write_text(
        "<footer>&copy; {{copyright_year}}</footer>", encoding="utf-8"
    )
    (root / "src" / "pages" / "index.html").write_text(
        "<h1>{{release_tag}}</h1>", encoding="utf-8"
    )
    config = {
        "base_url": "https://example.test",
        "release_version": "1.2.3",
        "release_tag": "v1.2.3",
        "copyright_year": 2026,
        "pages": [
            {
                "source": "index.html",
                "output": "index.html",
                "path": "/",
                "title": "Example & <safe>",
                "description": "Example description",
                "og_title": "Example",
                "og_description": "Example social description",
                "lastmod": "2026-09-01",
                "main_class": "home-page",
            }
        ],
    }
    (root / "site.json").write_text(
        json.dumps(config, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )


class RendererTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        write_fixture(self.root)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_build_is_deterministic_and_check_detects_stale_output(self) -> None:
        site_build.build_site(self.root)
        first = {
            path.relative_to(self.root): path.read_bytes()
            for path in sorted((self.root / "public").rglob("*"))
            if path.is_file()
        }
        site_build.build_site(self.root)
        second = {
            path.relative_to(self.root): path.read_bytes()
            for path in sorted((self.root / "public").rglob("*"))
            if path.is_file()
        }
        self.assertEqual(first, second)
        self.assertEqual([], site_build.check_site(self.root))

        output = self.root / "public" / "index.html"
        output.write_text("stale\n", encoding="utf-8")
        self.assertIn("stale generated file: public/index.html", site_build.check_site(self.root))

        site_build.build_site(self.root)
        output.unlink()
        self.assertIn("missing generated file: public/index.html", site_build.check_site(self.root))

        site_build.build_site(self.root)
        extra = self.root / "public" / "forgotten.html"
        extra.write_text("<!doctype html>\n", encoding="utf-8")
        self.assertIn(
            "unexpected generated HTML: public/forgotten.html",
            site_build.check_site(self.root),
        )

    def test_metadata_is_escaped_and_only_fragments_render_as_markup(self) -> None:
        site_build.build_site(self.root)
        output = (self.root / "public" / "index.html").read_text(encoding="utf-8")
        self.assertIn("<title>Example &amp; &lt;safe&gt;</title>", output)
        self.assertIn("<header>", output)
        self.assertIn("<h1>v1.2.3</h1>", output)
        self.assertIn("<footer>", output)

    def test_rejects_duplicate_output_and_unresolved_tokens(self) -> None:
        config_path = self.root / "site.json"
        config = json.loads(config_path.read_text(encoding="utf-8"))
        duplicate = dict(config["pages"][0])
        duplicate["source"] = "second.html"
        (self.root / "src" / "pages" / "second.html").write_text(
            "<p>second</p>", encoding="utf-8"
        )
        config["pages"].append(duplicate)
        config_path.write_text(json.dumps(config), encoding="utf-8")
        with self.assertRaisesRegex(site_build.SiteError, "duplicate page output"):
            site_build.expected_outputs(self.root)

        shutil.rmtree(self.root)
        self.root.mkdir()
        write_fixture(self.root)
        (self.root / "src" / "pages" / "index.html").write_text(
            "<p>{{not_a_token}}</p>", encoding="utf-8"
        )
        with self.assertRaisesRegex(site_build.SiteError, "unknown or unavailable token"):
            site_build.expected_outputs(self.root)


class PublishedSiteTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.config = json.loads((WEBSITE_ROOT / "site.json").read_text(encoding="utf-8"))
        cls.pages: dict[str, str] = {}
        cls.parsers: dict[str, DocumentParser] = {}
        for page in cls.config["pages"]:
            output = page["output"]
            text = (WEBSITE_ROOT / "public" / output).read_text(encoding="utf-8")
            cls.pages[output] = text
            cls.parsers[output] = parse_document(text)

    def test_generated_output_is_current(self) -> None:
        self.assertEqual([], site_build.check_site(WEBSITE_ROOT))

    def test_every_page_has_the_same_single_header_footer_and_accessible_main(self) -> None:
        headers: set[str] = set()
        footers: set[str] = set()
        for output, text in self.pages.items():
            parser = self.parsers[output]
            with self.subTest(output=output):
                self.assertEqual(1, parser.header_count)
                self.assertEqual(1, parser.footer_count)
                self.assertEqual(1, parser.main_count)
                self.assertEqual(1, parser.skip_link_count)
                self.assertEqual(len(parser.ids), len(set(parser.ids)), "duplicate element IDs")
                header = re.findall(r"(?s)<header\b.*?</header>", text)
                footer = re.findall(r"(?s)<footer\b.*?</footer>", text)
                self.assertEqual(1, len(header))
                self.assertEqual(1, len(footer))
                headers.add(header[0])
                footers.add(footer[0])
        self.assertEqual(1, len(headers), "rendered headers differ between pages")
        self.assertEqual(1, len(footers), "rendered footers differ between pages")

    def test_internal_links_assets_and_fragments_exist(self) -> None:
        public = WEBSITE_ROOT / "public"
        ids_by_output = {
            output: set(parser.ids) for output, parser in self.parsers.items()
        }

        def local_target(url: str, current_output: str) -> tuple[Path, str, str] | None:
            parsed = urlsplit(url)
            if parsed.scheme or parsed.netloc:
                return None
            if not parsed.path:
                output = current_output
                target = public / current_output
            elif parsed.path == "/":
                output = "index.html"
                target = public / output
            elif parsed.path.startswith("/"):
                relative = unquote(parsed.path.lstrip("/"))
                target = public / relative
                output = relative
            else:
                target = (public / current_output).parent / unquote(parsed.path)
                output = str(target.relative_to(public))
            return target, output, unquote(parsed.fragment)

        for output, parser in self.parsers.items():
            for url in parser.hrefs + parser.asset_urls:
                if url.startswith(("mailto:", "data:")):
                    continue
                resolved = local_target(url, output)
                if resolved is None:
                    continue
                target, target_output, fragment = resolved
                with self.subTest(page=output, url=url):
                    self.assertTrue(target.is_file(), f"missing local target {target}")
                    if fragment:
                        self.assertIn(target_output, ids_by_output)
                        self.assertIn(fragment, ids_by_output[target_output])

    def test_canonicals_and_sitemap_are_unique_and_complete(self) -> None:
        base_url = self.config["base_url"].rstrip("/")
        expected: list[str] = []
        expected_lastmod: dict[str, str] = {}
        for page in self.config["pages"]:
            url = f"{base_url}/" if page["path"] == "/" else f"{base_url}{page['path']}"
            expected.append(url)
            expected_lastmod[url] = page["lastmod"]
            with self.subTest(output=page["output"]):
                self.assertEqual([url], self.parsers[page["output"]].canonicals)
        self.assertEqual(len(expected), len(set(expected)))

        root = ET.parse(WEBSITE_ROOT / "public" / "sitemap.xml").getroot()
        namespace = {"sm": "http://www.sitemaps.org/schemas/sitemap/0.9"}
        entries: list[tuple[str, str]] = []
        for url_element in root.findall("sm:url", namespace):
            location = url_element.findtext("sm:loc", namespaces=namespace)
            lastmod = url_element.findtext("sm:lastmod", namespaces=namespace)
            self.assertIsNotNone(location)
            self.assertIsNotNone(lastmod)
            entries.append((location or "", lastmod or ""))
        self.assertEqual(expected, [location for location, _ in entries])
        self.assertEqual(len(entries), len({location for location, _ in entries}))
        self.assertEqual(
            expected_lastmod,
            {location: lastmod for location, lastmod in entries},
        )

    def test_site_has_no_runtime_scripts_remote_assets_or_template_tokens(self) -> None:
        for output, text in self.pages.items():
            parser = self.parsers[output]
            with self.subTest(output=output):
                self.assertEqual(0, parser.script_count)
                self.assertNotIn("{{", text)
                self.assertNotIn("}}", text)
                for url in parser.asset_urls:
                    parsed = urlsplit(url)
                    self.assertFalse(
                        parsed.scheme or parsed.netloc,
                        f"remote runtime asset in {output}: {url}",
                    )

    def test_stylesheet_uses_content_digest_cache_key(self) -> None:
        css = (WEBSITE_ROOT / "public" / "assets" / "site.css").read_bytes()
        expected = f"/assets/site.css?v={hashlib.sha256(css).hexdigest()[:12]}"
        for output, parser in self.parsers.items():
            with self.subTest(output=output):
                self.assertEqual([expected], parser.stylesheets)

    def test_release_copy_comes_from_the_configured_version_and_tag(self) -> None:
        index = self.pages["index.html"]
        version = self.config["release_version"]
        tag = self.config["release_tag"]
        self.assertIn(f"Latest signed release · v{version}", index)
        self.assertIn(f"/releases/tag/{tag}", index)

    def test_public_is_the_complete_deploy_root(self) -> None:
        public = WEBSITE_ROOT / "public"
        self.assertTrue(public.is_dir())
        self.assertTrue(
            (public / ".well-known" / "autoconfig" / "mail" / "config-v1.1.xml").is_file()
        )
        forbidden = ["src", "tests", "site.json", "build.py", "README.md"]
        for name in forbidden:
            with self.subTest(name=name):
                self.assertFalse((public / name).exists())


if __name__ == "__main__":
    unittest.main()
