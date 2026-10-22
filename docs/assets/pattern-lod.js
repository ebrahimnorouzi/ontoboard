/*
 * Opens every pattern graph at level of detail 3.
 *
 * WHY THIS IS A SCRIPT AND NOT A FENCE OPTION. It should be `lod: 3` in the ontoink fence, and
 * ontoink.js even has the hook - `lodLevel: (data.lod_default != null) ? data.lod_default : ...`
 * with a comment saying "authors can override with a fence YAML `lod: N`". That hook is dead:
 * nothing on the Python side ever writes `lod_default`, ontoink's fence reader accepts a fixed
 * list of keys and silently ignores the rest, and the plugin's mkdocs options are only `report`,
 * `quality_gate` and `strict_quality`. Measured on ontoink 0.7.9 and on the 0.7.7 installed
 * here: `lod:`, `lod_default:`, `lod_level:`, `detail:` and `level:` all produce a payload
 * identical to passing nothing, and the toolbar still opens on L6.
 *
 * So the only ways in are the reader's own toolbar dropdown and the exported API. This takes the
 * second. Wiring `lod:` through properly is a change to ontoink, which is a separate repository.
 *
 * WHY L3. The levels are cumulative, and their floors are in ontoink.js:
 *
 *   L0  classes only, no edges
 *   L1  + the subclass hierarchy
 *   L2  + individuals, property nodes, object properties
 *   L3  + OWL restrictions and SHACL constraints
 *   L4  + datatype properties and literal nodes
 *   L5  the same as L4 (nothing has floor 5)
 *   L6  + inferred triples
 *
 * L3 is therefore the highest level that still hides every literal while keeping all of the
 * structure a pattern is about. It is a large difference on a real pattern: eep.ttl is 772 nodes
 * and 1,340 edges at L6, of which 558 nodes are literals, and 214 nodes at L3.
 *
 * WHY `load` AND NOT `DOMContentLoaded`. ontoink mounts its graphs from DOMContentLoaded and the
 * plugin injects its own script last, so a handler on the same event races it. `load` is after
 * all of that. The retries are for a graph whose Turtle is still being fetched when `load`
 * fires.
 *
 * WHY IT STOPS RETRYING A CONTAINER ONCE IT HAS SET IT. A reader who picks a different level
 * from the dropdown must keep it. Re-applying on a timer would take their choice away a second
 * later. "Set it" means ontoink's own instance now reports level 3, not merely that the call
 * was made - see the comment in apply().
 *
 * HOW IT IS LOADED. From mkdocs.yml's `extra_javascript`, so mkdocs computes the path to it from
 * each page. The first version emitted `<script src="../assets/pattern-lod.js">` into every
 * pattern page, which 404s: with use_directory_urls - the default - the page is served from
 * patterns/<id>/index.html, so that resolves to patterns/assets/pattern-lod.js. mkdocs rewrites
 * relative links written in Markdown; it does not rewrite a src attribute in raw HTML.
 * tools/check-pattern-pages.py now resolves every script a built pattern page asks for and
 * fails the build if this one is not among them, because a 404 here is silent: the graphs
 * simply open at L6 and the page still says they open at L3.
 */
(function () {
    "use strict";

    var WANTED = 3;
    var ATTEMPTS = [0, 400, 1200, 2500, 5000];
    var done = Object.create(null);

    function apply() {
        var api = window.ontoink;
        if (!api || typeof api.setLodLevel !== "function") {
            return false;
        }
        var containers = document.querySelectorAll(".ontoink-container");
        var any = false;
        for (var at = 0; at < containers.length; at++) {
            var id = containers[at].id;
            if (!id || done[id]) {
                continue;
            }
            // CALLING IT IS NOT EVIDENCE THAT IT TOOK. setLodLevel begins
            // `var inst = instances[id]; if (!inst) return;` and returns the same way when the
            // instance has no cytoscape core yet, so a graph still fetching its Turtle accepts
            // the call and ignores it. The first version marked that container done and never
            // came back - the retries below existed for exactly this case and could not fire.
            // ontoink keeps the level on the instance and exports getInstance, so read it back.
            var inst = typeof api.getInstance === "function" ? api.getInstance(id) : null;
            if (!inst || !inst.cy) {
                continue;
            }
            try {
                api.setLodLevel(id, WANTED);
            } catch (midRender) {
                // A later attempt will get it, and if none does the graph opens at its own
                // default - a worse view, not a broken one, so there is nothing here worth
                // telling the reader about.
                continue;
            }
            if (inst.lodLevel === WANTED) {
                done[id] = true;
                any = true;
            }
        }
        return any;
    }

    window.addEventListener("load", function () {
        for (var at = 0; at < ATTEMPTS.length; at++) {
            if (ATTEMPTS[at] === 0) {
                apply();
            } else {
                window.setTimeout(apply, ATTEMPTS[at]);
            }
        }
    });
})();
