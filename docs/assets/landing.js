/*
 * Reveals the landing page's sections as they come into view.
 *
 * THE THING THAT IS EASY TO GET WRONG HERE is not the animation, it is that this site runs with
 * Material's `navigation.instant`. That replaces the document body over XHR instead of loading a
 * new page, so a script that only runs on DOMContentLoaded works exactly once - the first page a
 * visitor lands on - and every later navigation back to the front page shows sections that were
 * set up to start invisible and have nothing left to make them visible. Material publishes an
 * observable, `document$`, which emits on every such swap; subscribing to it is the whole fix, and
 * the DOMContentLoaded path below is the fallback for when instant navigation is off.
 *
 * THE SECOND THING is that the page must read correctly with this file absent, blocked or still in
 * flight. So nothing is hidden by CSS until this script sets data-reveal="on" - see landing.css.
 * The sequence matters: mark the page, then immediately show everything already on screen, so the
 * hero does not flicker.
 *
 * No framework and no build step: this is served exactly as written.
 */
(function () {
  "use strict";

  var SELECTOR = ".ob-reveal";
  var SHOWN = "ob-shown";
  var observer = null;

  function showAll(sections) {
    for (var at = 0; at < sections.length; at++) {
      sections[at].classList.add(SHOWN);
    }
  }

  function init() {
    var sections = document.querySelectorAll(SELECTOR);
    if (!sections.length) {
      return;
    }

    /* Disconnect the previous page's observer. With instant navigation the old document is gone
     * but a live IntersectionObserver would keep its nodes reachable. */
    if (observer) {
      observer.disconnect();
      observer = null;
    }

    var still = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (still || typeof IntersectionObserver === "undefined") {
      /* Either the reader asked for less motion, or the browser cannot do this cheaply. Both get
       * the finished page, and data-reveal is deliberately NOT set so the CSS never hides
       * anything in the first place. */
      showAll(sections);
      return;
    }

    document.documentElement.setAttribute("data-reveal", "on");

    observer = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) {
            entry.target.classList.add(SHOWN);
            /* Once shown, stop watching it. This is a one-way reveal: a section that faded out
             * again on scroll-up would be an effect, not a help. */
            observer.unobserve(entry.target);
          }
        });
      },
      /* A little before it arrives, so the section is already settled by the time it is read
       * rather than animating under the reader's eyes. */
      { rootMargin: "0px 0px -12% 0px", threshold: 0.05 }
    );

    for (var at = 0; at < sections.length; at++) {
      observer.observe(sections[at]);
    }

    /* Anything already in view when the page opens is shown at once rather than animated in -
     * the hero must not fade in on arrival, and a visitor who lands deep-linked part-way down
     * the page should not find the section above the fold hidden. */
    requestAnimationFrame(function () {
      for (var i = 0; i < sections.length; i++) {
        var box = sections[i].getBoundingClientRect();
        if (box.top < window.innerHeight && box.bottom > 0) {
          sections[i].classList.add(SHOWN);
          observer.unobserve(sections[i]);
        }
      }
    });
  }

  /* Material's instant navigation. `document$` is a global it exposes when the feature is on, and
   * it emits once on first load and again after every page swap. */
  if (typeof window.document$ !== "undefined" && window.document$ && window.document$.subscribe) {
    window.document$.subscribe(init);
  } else if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
