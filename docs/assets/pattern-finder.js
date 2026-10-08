/*
 * The pattern finder on docs/patterns/index.md.
 *
 * Why this exists rather than reusing something. ontoink's own search box searches the nodes of
 * one graph - label, iri and type - which is how you find a term inside a pattern, not how you
 * find a pattern among 159. MkDocs Material's search is good and does index every pattern page's
 * prose, so it finds patterns too, but it cannot filter a list by facet or tell you how many of
 * the 159 matched. Both are wanted, so both are here: Material's box for "I remember a word", and
 * this for "show me the behavioural ones from MWO".
 *
 * There is no scope field in the data. Measured: no column in patterns/index.tsv, no key in any of
 * the 159 metadata.json files. So "scope" is read as collection plus category plus domain, and the
 * page says so rather than implying a field exists. Inventing a scope value for 159 patterns would
 * be a number somebody decided on, which is the same mistake as the harvested class_count that is
 * wrong for 41 of them and that the library refuses to display.
 *
 * No framework and no build step: this file is served as it is written, and it has to work on a
 * documentation page that already carries ontoink's 650 KB bundle on the pattern pages themselves.
 */
(function () {
  "use strict";

  var INDEX_URL = "patterns.json";
  var state = { patterns: [], sources: {}, source: "", category: "", query: "" };

  function el(id) {
    return document.getElementById(id);
  }

  function escapeHtml(text) {
    return String(text === undefined || text === null ? "" : text)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  /* Every word has to match, not any. With 159 entries "time part" should narrow rather than
   * widen - the same rule the plugin's own search uses, so the two agree. */
  function matches(pattern, words) {
    if (state.source && pattern.c !== state.source) {
      return false;
    }
    if (state.category && pattern.k !== state.category) {
      return false;
    }
    if (!words.length) {
      return true;
    }
    var haystack = (
      pattern.i + " " + pattern.n + " " + pattern.c + " " + pattern.p + " " +
      pattern.k + " " + pattern.d + " " + pattern.q + " " + pattern.s
    ).toLowerCase();
    for (var at = 0; at < words.length; at++) {
      if (haystack.indexOf(words[at]) === -1) {
        return false;
      }
    }
    return true;
  }

  function render() {
    var words = state.query.toLowerCase().split(/\s+/).filter(function (w) {
      return w.length > 0;
    });
    var hits = state.patterns.filter(function (p) {
      return matches(p, words);
    });

    var count = el("ob-count");
    if (count) {
      var total = state.patterns.length;
      if (hits.length === total) {
        count.textContent = total + " patterns.";
      } else if (hits.length === 0) {
        count.textContent = "Nothing matches. Clear the filter, or try one word.";
      } else {
        count.textContent = hits.length + " of " + total + " patterns.";
      }
    }

    var rows = hits.map(function (p) {
      var question = p.q ? p.q.split("|")[0].trim() : "";
      if (question.length > 120) {
        question = question.slice(0, 117) + "…";
      }
      var same = p.a
        ? ' <span class="ob-same">same as ' + escapeHtml(p.a) + "</span>"
        : "";
      return (
        '<li class="ob-hit">' +
        '<a class="ob-name" href="' + escapeHtml(p.i) + '/">' + escapeHtml(p.n) + "</a>" + same +
        '<span class="ob-meta">' +
        escapeHtml(state.sources[p.c] || p.c) +
        " &middot; " + escapeHtml(p.k || "uncategorised") +
        " &middot; " + escapeHtml(p.d || "general") +
        " &middot; " + p.t + (p.t === 1 ? " term" : " terms") +
        "</span>" +
        (question ? '<span class="ob-cq">' + escapeHtml(question) + "</span>" : "") +
        "</li>"
      );
    });

    var results = el("ob-results");
    if (results) {
      results.innerHTML = rows.length ? '<ul class="ob-hits">' + rows.join("") + "</ul>" : "";
    }
  }

  function chip(label, value, field) {
    var selected = state[field] === value;
    return (
      '<button type="button" class="ob-chip' + (selected ? " ob-chip-on" : "") +
      '" data-field="' + field + '" data-value="' + escapeHtml(value) + '"' +
      (selected ? ' aria-pressed="true"' : ' aria-pressed="false"') + ">" +
      escapeHtml(label) + "</button>"
    );
  }

  function buildFacets() {
    var host = el("ob-facets");
    if (!host) {
      return;
    }
    var bySource = {};
    var byCategory = {};
    state.patterns.forEach(function (p) {
      bySource[p.c] = (bySource[p.c] || 0) + 1;
      var k = p.k || "uncategorised";
      byCategory[k] = (byCategory[k] || 0) + 1;
    });

    /* Biggest first within each row, because a facet with three members is not where anybody
     * starts. The counts are on the chips so the reader can see the shape of the library without
     * clicking anything. */
    function chips(counts, field) {
      return Object.keys(counts)
        .sort(function (a, b) {
          return counts[b] - counts[a] || a.localeCompare(b);
        })
        .map(function (value) {
          var label = field === "source" ? state.sources[value] || value : value;
          return chip(label + " (" + counts[value] + ")", value, field);
        })
        .join("");
    }

    host.innerHTML =
      '<div class="ob-facet-row"><span class="ob-facet-label">Source</span>' +
      chip("All", "", "source") + chips(bySource, "source") + "</div>" +
      '<div class="ob-facet-row"><span class="ob-facet-label">Category</span>' +
      chip("All", "", "category") + chips(byCategory, "category") + "</div>";

    host.querySelectorAll(".ob-chip").forEach(function (button) {
      button.addEventListener("click", function () {
        state[button.getAttribute("data-field")] = button.getAttribute("data-value");
        buildFacets();
        render();
      });
    });
  }

  function start() {
    var box = el("ob-q");
    if (!box) {
      return;
    }
    fetch(INDEX_URL)
      .then(function (response) {
        if (!response.ok) {
          throw new Error("HTTP " + response.status);
        }
        return response.json();
      })
      .then(function (data) {
        state.patterns = data.patterns || [];
        state.sources = data.sources || {};
        buildFacets();
        render();
        box.addEventListener("input", function () {
          state.query = box.value;
          render();
        });
      })
      .catch(function (error) {
        /* Said out loud. A finder that silently shows nothing is indistinguishable from a library
         * with nothing in it, and the tables further down the page are still there either way. */
        var count = el("ob-count");
        if (count) {
          count.textContent =
            "The search index did not load (" + error.message +
            "). Every pattern is still listed by source below.";
        }
      });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
