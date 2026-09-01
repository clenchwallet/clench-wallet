#!/usr/bin/env python3
"""Build the deterministic, dependency-free Clench static website."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import html
import json
import os
from pathlib import Path
import re
import sys
from typing import Any, Mapping
from urllib.parse import urlsplit


WEBSITE_ROOT = Path(__file__).resolve().parent
TOKEN_RE = re.compile(r"{{([a-z][a-z0-9_]*)}}")
SAFE_HTML_NAME_RE = re.compile(r"[a-z0-9][a-z0-9-]*\.html")
CSS_CLASS_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_-]*(?: [A-Za-z_][A-Za-z0-9_-]*)*")
VERSION_RE = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")
TOP_LEVEL_KEYS = {
    "base_url",
    "release_version",
    "release_tag",
    "copyright_year",
    "pages",
}
PAGE_KEYS = {
    "source",
    "output",
    "path",
    "title",
    "description",
    "og_title",
    "og_description",
    "lastmod",
    "main_class",
}
GLOBAL_TOKENS = {
    "base_url",
    "release_version",
    "release_tag",
    "copyright_year",
    "css_version",
}
PAGE_TOKENS = PAGE_KEYS | {"canonical_url"}
RAW_LAYOUT_TOKENS = {"header", "footer", "page_content"}


class SiteError(ValueError):
    """Raised when the site configuration or templates are invalid."""


def _reject_duplicate_json_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise SiteError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _read_json(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_reject_duplicate_json_keys,
        )
    except OSError as exc:
        raise SiteError(f"cannot read {path}: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise SiteError(f"invalid JSON in {path}: {exc}") from exc
    if not isinstance(data, dict):
        raise SiteError("site.json must contain a JSON object")
    return data


def _require_exact_keys(value: Mapping[str, Any], expected: set[str], label: str) -> None:
    actual = set(value)
    missing = sorted(expected - actual)
    unknown = sorted(actual - expected)
    if missing or unknown:
        details: list[str] = []
        if missing:
            details.append(f"missing {', '.join(missing)}")
        if unknown:
            details.append(f"unknown {', '.join(unknown)}")
        raise SiteError(f"{label} has invalid keys ({'; '.join(details)})")


def _require_string(value: Any, label: str, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str):
        raise SiteError(f"{label} must be a string")
    if not allow_empty and not value:
        raise SiteError(f"{label} must not be empty")
    if "\x00" in value:
        raise SiteError(f"{label} must not contain NUL")
    return value


def _validate_html_name(value: Any, label: str) -> str:
    name = _require_string(value, label)
    if not SAFE_HTML_NAME_RE.fullmatch(name) or Path(name).name != name:
        raise SiteError(f"{label} must be a safe, one-level lowercase .html name")
    return name


def _validate_config(data: dict[str, Any]) -> dict[str, Any]:
    _require_exact_keys(data, TOP_LEVEL_KEYS, "site.json")

    base_url = _require_string(data["base_url"], "base_url").rstrip("/")
    parsed_base = urlsplit(base_url)
    if (
        parsed_base.scheme != "https"
        or not parsed_base.netloc
        or parsed_base.path
        or parsed_base.query
        or parsed_base.fragment
        or parsed_base.username
        or parsed_base.password
    ):
        raise SiteError("base_url must be an HTTPS origin without a path, query, or fragment")

    release_version = _require_string(data["release_version"], "release_version")
    if not VERSION_RE.fullmatch(release_version):
        raise SiteError("release_version must use dotted numeric form, such as 0.3.28")
    release_tag = _require_string(data["release_tag"], "release_tag")
    if release_tag != f"v{release_version}":
        raise SiteError("release_tag must equal 'v' followed by release_version")

    copyright_year = data["copyright_year"]
    if (
        not isinstance(copyright_year, int)
        or isinstance(copyright_year, bool)
        or not 2000 <= copyright_year <= 9999
    ):
        raise SiteError("copyright_year must be a four-digit integer")

    raw_pages = data["pages"]
    if not isinstance(raw_pages, list) or not raw_pages:
        raise SiteError("pages must be a non-empty array")

    pages: list[dict[str, str]] = []
    sources: set[str] = set()
    outputs: set[str] = set()
    paths: set[str] = set()
    for index, raw_page in enumerate(raw_pages):
        label = f"pages[{index}]"
        if not isinstance(raw_page, dict):
            raise SiteError(f"{label} must be an object")
        _require_exact_keys(raw_page, PAGE_KEYS, label)

        source = _validate_html_name(raw_page["source"], f"{label}.source")
        output = _validate_html_name(raw_page["output"], f"{label}.output")
        path = _require_string(raw_page["path"], f"{label}.path")
        expected_path = "/" if output == "index.html" else f"/{output}"
        if path != expected_path:
            raise SiteError(f"{label}.path must be {expected_path!r} for output {output!r}")

        title = _require_string(raw_page["title"], f"{label}.title")
        description = _require_string(raw_page["description"], f"{label}.description")
        og_title = _require_string(raw_page["og_title"], f"{label}.og_title")
        og_description = _require_string(
            raw_page["og_description"], f"{label}.og_description"
        )
        lastmod = _require_string(raw_page["lastmod"], f"{label}.lastmod")
        try:
            parsed_lastmod = dt.date.fromisoformat(lastmod)
        except ValueError as exc:
            raise SiteError(f"{label}.lastmod must be an ISO date") from exc
        if parsed_lastmod.isoformat() != lastmod:
            raise SiteError(f"{label}.lastmod must use YYYY-MM-DD form")

        main_class = _require_string(
            raw_page["main_class"], f"{label}.main_class", allow_empty=True
        )
        if main_class and not CSS_CLASS_RE.fullmatch(main_class):
            raise SiteError(f"{label}.main_class must be a space-separated CSS class list")

        if source in sources:
            raise SiteError(f"duplicate page source: {source}")
        if output in outputs:
            raise SiteError(f"duplicate page output: {output}")
        if path in paths:
            raise SiteError(f"duplicate page path: {path}")
        sources.add(source)
        outputs.add(output)
        paths.add(path)

        pages.append(
            {
                "source": source,
                "output": output,
                "path": path,
                "title": title,
                "description": description,
                "og_title": og_title,
                "og_description": og_description,
                "lastmod": lastmod,
                "main_class": main_class,
            }
        )

    return {
        "base_url": base_url,
        "release_version": release_version,
        "release_tag": release_tag,
        "copyright_year": copyright_year,
        "pages": pages,
    }


def _read_text(path: Path, label: str) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        raise SiteError(f"cannot read {label} at {path}: {exc}") from exc
    except UnicodeDecodeError as exc:
        raise SiteError(f"{label} must be UTF-8: {path}") from exc


def _render(template: str, values: Mapping[str, str], label: str) -> str:
    tokens = TOKEN_RE.findall(template)
    for token in tokens:
        if token not in values:
            raise SiteError(f"unknown or unavailable token {{{{{token}}}}} in {label}")
    rendered = TOKEN_RE.sub(lambda match: values[match.group(1)], template)
    if "{{" in rendered or "}}" in rendered:
        raise SiteError(f"malformed or unresolved template token in {label}")
    return rendered


def _escaped_values(values: Mapping[str, Any]) -> dict[str, str]:
    return {key: html.escape(str(value), quote=True) for key, value in values.items()}


def _canonical_url(base_url: str, path: str) -> str:
    return f"{base_url}/" if path == "/" else f"{base_url}{path}"


def _sitemap(config: Mapping[str, Any]) -> bytes:
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
    ]
    for page in config["pages"]:
        url = _canonical_url(config["base_url"], page["path"])
        lines.extend(
            [
                "  <url>",
                f"    <loc>{html.escape(url, quote=True)}</loc>",
                f"    <lastmod>{html.escape(page['lastmod'], quote=True)}</lastmod>",
                "  </url>",
            ]
        )
    lines.append("</urlset>")
    return ("\n".join(lines) + "\n").encode("utf-8")


def expected_outputs(root: Path = WEBSITE_ROOT) -> dict[Path, bytes]:
    """Return every generated file and its expected deterministic bytes."""

    root = root.resolve()
    config = _validate_config(_read_json(root / "site.json"))
    source_root = root / "src"
    pages_root = source_root / "pages"
    public_root = root / "public"

    css_path = public_root / "assets" / "site.css"
    try:
        css_bytes = css_path.read_bytes()
    except OSError as exc:
        raise SiteError(f"cannot read stylesheet at {css_path}: {exc}") from exc
    css_version = hashlib.sha256(css_bytes).hexdigest()[:12]

    layout = _read_text(source_root / "layout.html", "layout template")
    header_template = _read_text(source_root / "partials" / "header.html", "header partial")
    footer_template = _read_text(source_root / "partials" / "footer.html", "footer partial")
    for token in RAW_LAYOUT_TOKENS:
        count = layout.count(f"{{{{{token}}}}}")
        if count != 1:
            raise SiteError(f"layout must contain {{{{{token}}}}} exactly once (found {count})")

    configured_sources = {page["source"] for page in config["pages"]}
    actual_sources = {path.name for path in pages_root.glob("*.html")}
    unconfigured_sources = sorted(actual_sources - configured_sources)
    if unconfigured_sources:
        raise SiteError(f"unconfigured page sources: {', '.join(unconfigured_sources)}")

    global_values = {
        "base_url": config["base_url"],
        "release_version": config["release_version"],
        "release_tag": config["release_tag"],
        "copyright_year": config["copyright_year"],
        "css_version": css_version,
    }
    escaped_globals = _escaped_values(global_values)
    header = _render(header_template, escaped_globals, "header partial")
    footer = _render(footer_template, escaped_globals, "footer partial")

    outputs: dict[Path, bytes] = {}
    for page in config["pages"]:
        source_path = pages_root / page["source"]
        if not source_path.is_file():
            raise SiteError(f"configured page source does not exist: {source_path}")

        canonical_url = _canonical_url(config["base_url"], page["path"])
        page_values = {**global_values, **page, "canonical_url": canonical_url}
        escaped_page_values = _escaped_values(page_values)
        page_content = _render(
            _read_text(source_path, f"page source {page['source']}"),
            escaped_page_values,
            f"page source {page['source']}",
        )
        layout_values = {
            **escaped_page_values,
            "header": header,
            "footer": footer,
            "page_content": page_content,
        }
        rendered = _render(layout, layout_values, f"layout for {page['output']}")
        if not rendered.endswith("\n"):
            rendered += "\n"
        outputs[public_root / page["output"]] = rendered.encode("utf-8")

    outputs[public_root / "sitemap.xml"] = _sitemap(config)
    return outputs


def _generated_html_files(root: Path) -> set[Path]:
    public_root = root.resolve() / "public"
    if not public_root.is_dir():
        return set()
    return {path for path in public_root.glob("*.html") if path.is_file()}


def check_site(root: Path = WEBSITE_ROOT) -> list[str]:
    """Return human-readable freshness errors without changing the tree."""

    root = root.resolve()
    expected = expected_outputs(root)
    expected_html = {path for path in expected if path.suffix == ".html"}
    errors: list[str] = []
    for extra in sorted(_generated_html_files(root) - expected_html):
        errors.append(f"unexpected generated HTML: {extra.relative_to(root)}")
    for path, expected_bytes in sorted(expected.items(), key=lambda item: str(item[0])):
        try:
            actual_bytes = path.read_bytes()
        except FileNotFoundError:
            errors.append(f"missing generated file: {path.relative_to(root)}")
            continue
        except OSError as exc:
            errors.append(f"cannot read generated file {path.relative_to(root)}: {exc}")
            continue
        if actual_bytes != expected_bytes:
            errors.append(f"stale generated file: {path.relative_to(root)}")
    return errors


def _write_atomic(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        if path.read_bytes() == data:
            return
    except FileNotFoundError:
        pass
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_bytes(data)
    os.replace(temporary, path)


def build_site(root: Path = WEBSITE_ROOT) -> None:
    """Render all configured pages and remove stale root-level HTML outputs."""

    root = root.resolve()
    outputs = expected_outputs(root)
    expected_html = {path for path in outputs if path.suffix == ".html"}
    for extra in sorted(_generated_html_files(root) - expected_html):
        extra.unlink()
    for path, data in sorted(outputs.items(), key=lambda item: str(item[0])):
        _write_atomic(path, data)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify that committed public output is complete and current",
    )
    args = parser.parse_args(argv)
    try:
        if args.check:
            errors = check_site()
            if errors:
                for error in errors:
                    print(error, file=sys.stderr)
                return 1
            print("Website output is current.")
        else:
            build_site()
            print("Built website/public.")
    except SiteError as exc:
        print(f"website build failed: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
