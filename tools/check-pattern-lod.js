/*
 * Runs docs/assets/pattern-lod.js against a stubbed ontoink and asserts what it does.
 *
 *     node tools/check-pattern-lod.js
 *
 * WHY THIS IS A TEST AND NOT A READING OF THE CODE. The script's job - open every pattern graph
 * at level of detail 3 - fails silently in both directions. ontoink's setLodLevel begins
 * `var inst = instances[id]; if (!inst) return;` and returns the same way when the instance has
 * no cytoscape core yet, so a graph still fetching its Turtle ACCEPTS the call and ignores it.
 * The first version of the script marked that container done and never came back, which meant
 * the retries it was built around could not fire; measured against that version, test 1 below
 * ends with no graph set at all. Nothing in the page would have said so - the graphs would just
 * have opened at L6 under prose saying L3.
 *
 * The stub is ontoink's guard, not a mock of it: getInstance returns null until the plan says
 * the graph has mounted, and setLodLevel only records a level once there is an instance with a
 * core. What a browser would add is whether cytoscape really is mounted by `load`, which is the
 * one part of the chain no check here can reach.
 */
var fs = require("fs");
var path = require("path");
var vm = require("vm");

var SCRIPT = process.argv[2]
    || path.join(__dirname, "..", "docs", "assets", "pattern-lod.js");
var source = fs.readFileSync(SCRIPT, "utf8");

function run(stubPlan) {
    var timers = [];
    var containers = [{ id: "g1" }, { id: "g2" }];
    var levels = {};            // what ontoink was told, per container
    var instances = {};         // what getInstance returns, per container
    var calls = [];

    var api = {
        setLodLevel: function (id, level) {
            calls.push([id, level]);
            var inst = instances[id];
            if (!inst || !inst.cy) {
                return;         // ontoink's own guard, verbatim in effect
            }
            inst.lodLevel = level;
            levels[id] = level;
        },
        getInstance: function (id) { return instances[id] || null; },
    };

    var listeners = {};
    var sandbox = {
        window: {
            ontoink: api,
            addEventListener: function (name, fn) { listeners[name] = fn; },
            setTimeout: function (fn, ms) { timers.push([ms, fn]); },
        },
        document: {
            querySelectorAll: function () { return containers; },
        },
    };
    sandbox.window.window = sandbox.window;
    sandbox.window.document = sandbox.document;
    vm.createContext(sandbox);
    vm.runInContext(source, sandbox);

    // `load` fires, then each timer in the order it was registered, with the stub plan
    // deciding when each graph finishes mounting.
    var step = 0;
    function advance() {
        step += 1;
        stubPlan(step, instances);
    }
    advance();
    listeners.load();
    while (timers.length) {
        var next = timers.shift();
        advance();
        next[1]();
    }
    return { levels: levels, calls: calls, instances: instances };
}

var failures = [];
function check(what, got, wanted) {
    var ok = JSON.stringify(got) === JSON.stringify(wanted);
    console.log((ok ? "  ok   " : "  FAIL ") + what + "  got " + JSON.stringify(got)
        + (ok ? "" : ", wanted " + JSON.stringify(wanted)));
    if (!ok) { failures.push(what); }
}

console.log("1. a graph that mounts late is still set (this is the bug that shipped)");
var late = run(function (step, instances) {
    if (step >= 3) {
        instances.g1 = instances.g1 || { cy: {}, lodLevel: 6 };
        instances.g2 = instances.g2 || { cy: {}, lodLevel: 6 };
    }
});
check("both graphs end at level 3", late.levels, { g1: 3, g2: 3 });

console.log("2. a graph mounted from the start is set once and not touched again");
var early = run(function (step, instances) {
    if (step === 1) {
        instances.g1 = { cy: {}, lodLevel: 6 };
        instances.g2 = { cy: {}, lodLevel: 6 };
    }
});
check("level 3 on both", early.levels, { g1: 3, g2: 3 });
check("one call per container, not five", early.calls.length, 2);

console.log("3. a reader who picks another level keeps it");
var reader = run(function (step, instances) {
    if (step === 1) {
        instances.g1 = { cy: {}, lodLevel: 6 };
        instances.g2 = { cy: {}, lodLevel: 6 };
    }
    if (step === 2 && instances.g1) {
        instances.g1.lodLevel = 6;   // the reader turns it back up after the first attempt
    }
});
check("not re-applied to g1", reader.calls.filter(function (c) { return c[0] === "g1"; }).length, 1);

console.log("4. no ontoink at all is not an error");
var alone = (function () {
    var listeners = {};
    var sandbox = {
        window: {
            addEventListener: function (n, f) { listeners[n] = f; },
            setTimeout: function () {},
        },
        document: { querySelectorAll: function () { return []; } },
    };
    sandbox.window.window = sandbox.window;
    vm.createContext(sandbox);
    vm.runInContext(source, sandbox);
    try { listeners.load(); return "no throw"; } catch (e) { return "threw: " + e.message; }
})();
check("page without ontoink", alone, "no throw");

console.log(failures.length ? "\nFAILURES: " + failures.join(", ") : "\nall four hold");
process.exit(failures.length ? 1 : 0);
