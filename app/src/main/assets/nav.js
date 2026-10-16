/* Mazda Hub navigation engine: route parsing, progress tracking, turn text, voice prompts.
   Pure logic (no page code) so it can be tested on its own. Units: meters, seconds; display in miles. */
(function (root) {
  'use strict';
  var R = 6371000, rad = Math.PI / 180;

  function hav(a, b) {
    var dLa = (b[0] - a[0]) * rad, dLo = (b[1] - a[1]) * rad;
    var s = Math.sin(dLa / 2) * Math.sin(dLa / 2) + Math.cos(a[0] * rad) * Math.cos(b[0] * rad) * Math.sin(dLo / 2) * Math.sin(dLo / 2);
    return 2 * R * Math.asin(Math.sqrt(s));
  }
  function bearing(a, b) {
    var y = Math.sin((b[1] - a[1]) * rad) * Math.cos(b[0] * rad);
    var x = Math.cos(a[0] * rad) * Math.sin(b[0] * rad) - Math.sin(a[0] * rad) * Math.cos(b[0] * rad) * Math.cos((b[1] - a[1]) * rad);
    return (Math.atan2(y, x) / rad + 360) % 360;
  }
  // distance from point p to segment a-b (all [lat, lon]); t = position along the segment (0..1)
  function projectPoint(p, a, b) {
    var kx = Math.cos(a[0] * rad) * 111320, ky = 110540;
    var bx = (b[1] - a[1]) * kx, by = (b[0] - a[0]) * ky, px = (p[1] - a[1]) * kx, py = (p[0] - a[0]) * ky;
    var L2 = bx * bx + by * by, t = L2 ? (px * bx + py * by) / L2 : 0;
    t = Math.max(0, Math.min(1, t));
    var dx = px - t * bx, dy = py - t * by;
    return { d: Math.sqrt(dx * dx + dy * dy), t: t, len: Math.sqrt(L2) };
  }

  var COMPASS = ['north', 'northeast', 'east', 'southeast', 'south', 'southwest', 'west', 'northwest'];
  function compass(b) { return COMPASS[Math.round((b || 0) / 45) % 8]; }
  var ROT = { 'uturn': 180, 'sharp right': 135, 'right': 90, 'slight right': 35, 'straight': 0, 'slight left': -35, 'left': -90, 'sharp left': -135 };
  function side(mod) { return /left/.test(mod || '') ? 'left' : 'right'; }
  function street(s) { return s.name || s.ref || ''; }

  function icon(step) {
    var m = step.m, t = m.type, mod = m.modifier || 'straight';
    if (t === 'arrive') return { kind: 'arrive', rot: 0 };
    if (t === 'roundabout' || t === 'rotary' || t === 'roundabout turn') return { kind: 'round', rot: ROT[mod] != null ? ROT[mod] : 0 };
    if (t === 'depart') return { kind: 'arrow', rot: 0 };
    return { kind: 'arrow', rot: ROT[mod] != null ? ROT[mod] : 0 };
  }

  function instruction(step) {
    var m = step.m, t = m.type, mod = m.modifier || '', nm = street(step), on = nm ? ' onto ' + nm : '';
    if (t === 'depart') return 'Head ' + compass(m.bearing_after) + (nm ? ' on ' + nm : '');
    if (t === 'arrive') return 'Arrive at your destination' + ((mod === 'left' || mod === 'right') ? ', on the ' + mod : '');
    if (t === 'roundabout' || t === 'rotary') return 'At the roundabout, take exit ' + (m.exit || '') + on;
    if (t === 'exit roundabout' || t === 'exit rotary') return 'Exit the roundabout' + on;
    if (t === 'on ramp') return 'Take the ramp' + (mod && mod !== 'straight' ? ' on the ' + side(mod) : '') + on;
    if (t === 'off ramp') return 'Take the exit' + (mod && mod !== 'straight' ? ' on the ' + side(mod) : '') + on;
    if (t === 'merge') return 'Merge' + (mod && mod !== 'straight' ? ' ' + side(mod) : '') + on;
    if (t === 'fork') return 'Keep ' + side(mod) + ' at the fork' + on;
    if ((t === 'continue' || t === 'new name') && (!mod || mod === 'straight')) return 'Continue' + (nm ? ' on ' + nm : '');
    if (mod === 'uturn') return 'Make a U-turn' + on;
    var word = mod === 'slight left' ? 'Bear left' : mod === 'slight right' ? 'Bear right' : 'Turn ' + (mod || 'ahead');
    return word + on;
  }

  function fmtDist(m) {
    var ft = m * 3.28084;
    if (ft < 1000) return Math.max(50, Math.round(ft / 50) * 50) + ' ft';
    var mi = m / 1609.344;
    return (mi < 10 ? mi.toFixed(1) : String(Math.round(mi))) + ' mi';
  }
  function sayDist(m) {
    var ft = m * 3.28084;
    if (ft < 1000) return Math.max(100, Math.round(ft / 100) * 100) + ' feet';
    var mi = m / 1609.344;
    if (mi < 0.3) return 'a quarter mile';
    if (mi < 0.62) return 'half a mile';
    if (mi < 1.2) return 'one mile';
    return (mi < 10 ? mi.toFixed(1) : String(Math.round(mi))) + ' miles';
  }
  function fmtDur(s) {
    var m = Math.max(1, Math.round(s / 60));
    if (m < 60) return m + ' min';
    return Math.floor(m / 60) + ' h ' + (m % 60) + ' min';
  }
  function lowerFirst(s) { return s.charAt(0).toLowerCase() + s.slice(1); }
  function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }

  // Build a route from an OSRM /route response (geometries=geojson, steps=true, overview=full)
  function buildRoute(j) {
    var r = j && j.routes && j.routes[0];
    if (!r) return null;
    var pts = r.geometry.coordinates.map(function (c) { return [c[1], c[0]]; });
    var cum = [0], i;
    for (i = 1; i < pts.length; i++) cum.push(cum[i - 1] + hav(pts[i - 1], pts[i]));
    var steps = [], last = 0;
    r.legs.forEach(function (leg) {
      leg.steps.forEach(function (s) {
        var loc = [s.maneuver.location[1], s.maneuver.location[0]], best = last, bd = Infinity;
        for (var k = last; k < pts.length; k++) { var d = hav(loc, pts[k]); if (d < bd) { bd = d; best = k; } }
        last = best;
        steps.push({ start: cum[best], dist: s.distance, dur: s.duration, name: s.name || '', ref: s.ref || '', m: s.maneuver });
      });
    });
    return { pts: pts, cum: cum, total: cum[cum.length - 1], dur: r.duration, steps: steps };
  }

  function Tracker(route) {
    this.r = route; this.seg = 0; this.prog = 0; this.off = 0; this.said = {};
    this.stepIdx = 0;
  }
  Tracker.prototype.update = function (lat, lon, acc, speed) {
    var r = this.r, p = [lat, lon], best = { d: 1e12, i: this.seg, t: 0, len: 0 }, i, q;
    var lo = Math.max(0, this.seg - 4), hi = Math.min(r.pts.length - 2, this.seg + 400);
    for (i = lo; i <= hi; i++) { q = projectPoint(p, r.pts[i], r.pts[i + 1]); if (q.d < best.d) best = { d: q.d, i: i, t: q.t, len: q.len }; }
    var tol = Math.max(45, (acc || 0) * 1.3);
    if (best.d > tol) {                       // look at the whole route in case we rejoined it further on
      for (i = 0; i < r.pts.length - 1; i++) { q = projectPoint(p, r.pts[i], r.pts[i + 1]); if (q.d < best.d) best = { d: q.d, i: i, t: q.t, len: q.len }; }
    }
    if (best.d > tol) { this.off++; }
    else { this.off = 0; this.seg = best.i; this.prog = Math.max(this.prog - 30, r.cum[best.i] + best.t * best.len); }
    var steps = r.steps, k = 0;
    for (i = 0; i < steps.length; i++) { if (steps[i].start <= this.prog + 1) k = i; else break; }
    this.stepIdx = k;
    var nxt = steps[k + 1] || null;
    var distToNext = nxt ? Math.max(0, nxt.start - this.prog) : Math.max(0, r.total - this.prog);
    var remaining = Math.max(0, r.total - this.prog);
    var stepEnd = nxt ? nxt.start : r.total, span = Math.max(1, stepEnd - steps[k].start);
    var remDur = clamp((stepEnd - this.prog) / span, 0, 1) * steps[k].dur;
    for (i = k + 1; i < steps.length; i++) remDur += steps[i].dur;
    var arrived = remaining < 30 || (nxt && nxt.m.type === 'arrive' && distToNext < 25);

    var say = null, sp = speed > 0 ? speed : 8;
    if (nxt && !arrived) {
      var far = clamp(sp * 28, 350, 1300), near = clamp(sp * 9, 70, 220), key = k + 1;
      if (distToNext <= near && !this.said[key + 'n']) { this.said[key + 'n'] = 1; this.said[key + 'f'] = 1; say = instruction(nxt); }
      else if (distToNext <= far && (nxt.start - steps[k].start) > far + 100 && !this.said[key + 'f']) {
        this.said[key + 'f'] = 1; say = 'In ' + sayDist(distToNext) + ', ' + lowerFirst(instruction(nxt));
      }
    }
    var ang = r.pts[Math.min(r.pts.length - 1, this.seg + 1)];
    return {
      offRoute: this.off >= 3, arrived: !!arrived, progress: this.prog, remaining: remaining, remDur: remDur,
      step: k, cur: steps[k], next: nxt, distToNext: distToNext, say: say,
      routeBearing: bearing(r.pts[this.seg], ang), snapDist: best.d
    };
  };

  var api = { hav: hav, bearing: bearing, projectPoint: projectPoint, instruction: instruction, icon: icon, fmtDist: fmtDist, sayDist: sayDist,
    fmtDur: fmtDur, buildRoute: buildRoute, Tracker: Tracker };
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.Nav = api;
})(typeof window !== 'undefined' ? window : this);
