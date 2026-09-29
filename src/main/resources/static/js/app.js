/* =====================================================================
   HIGH BYTES WMS — page behaviour
   No framework. htmx handles partial updates; this file holds the
   pieces that need real client state.
   ===================================================================== */
(function () {
  'use strict';

  /* ---- CSRF on every htmx request -----------------------------------
     Spring Security rejects a POST without the token, and htmx does not
     read the form's hidden field. */
  var tokenMeta  = document.querySelector('meta[name="_csrf"]');
  var headerMeta = document.querySelector('meta[name="_csrf_header"]');
  if (tokenMeta && headerMeta && window.htmx) {
    document.body.addEventListener('htmx:configRequest', function (evt) {
      evt.detail.headers[headerMeta.content] = tokenMeta.content;
    });
  }

  /* ---- Stock movement chart ------------------------------------------
     Data comes from the server as JSON in the page, never hardcoded. */
  var dataEl = document.getElementById('chartData');
  var plot   = document.getElementById('chartPlot');
  if (!dataEl || !plot) return;

  var CHART_DATA;
  try { CHART_DATA = JSON.parse(dataEl.textContent || '{}'); }
  catch (e) { CHART_DATA = {}; }

  var cols   = document.getElementById('chartCols');
  var yAxis  = document.getElementById('chartY');
  var xAxis  = document.getElementById('chartX');
  var tip    = document.getElementById('chartTip');
  var box    = document.getElementById('stockChart');
  var sub    = document.getElementById('chartSubtitle');

  function niceScale(peak) {
    if (!peak || peak <= 0) return { max: 100, step: 25 };
    var magnitude = Math.pow(10, Math.floor(Math.log10(peak)));
    var step = magnitude / 2;
    var max = Math.ceil(peak / step) * step;
    while (max / step > 6) { step *= 2; max = Math.ceil(peak / step) * step; }
    return { max: max, step: step };
  }

  function render(key) {
    var d = CHART_DATA[key];
    if (!d) return;
    if (sub && d.subtitle) sub.textContent = d.subtitle;

    var peak = Math.max.apply(null, (d.received || [0]).concat(d.dispatched || [0]));
    var scale = niceScale(peak);
    var ticks = Math.round(scale.max / scale.step);

    plot.querySelectorAll('.chart-grid').forEach(function (g) { g.remove(); });
    yAxis.innerHTML = '';
    for (var i = 0; i <= ticks; i++) {
      var value = i * scale.step;
      var pct = (value / scale.max) * 100;
      var label = document.createElement('span');
      label.style.bottom = pct + '%';
      label.textContent = Math.round(value).toLocaleString();
      yAxis.appendChild(label);
      if (i > 0) {
        var grid = document.createElement('div');
        grid.className = 'chart-grid';
        grid.style.bottom = pct + '%';
        plot.insertBefore(grid, cols);
      }
    }

    cols.innerHTML = '';
    xAxis.innerHTML = '';
    (d.labels || []).forEach(function (label, idx) {
      var col = document.createElement('div');
      col.className = 'chart-col';
      [['received', 'Received'], ['dispatched', 'Dispatched']].forEach(function (series) {
        var v = (d[series[0]] || [])[idx] || 0;
        var bar = document.createElement('div');
        bar.className = 'bar ' + (series[0] === 'received' ? 'received' : 'delivery');
        bar.dataset.h = (v / scale.max) * 100;
        bar.dataset.tip = label + ' · ' + series[1] + ': ' + v.toLocaleString();
        col.appendChild(bar);
      });
      cols.appendChild(col);
      var x = document.createElement('span');
      x.textContent = label;
      xAxis.appendChild(x);
    });

    requestAnimationFrame(function () {
      cols.querySelectorAll('.bar').forEach(function (b) { b.style.height = b.dataset.h + '%'; });
    });
  }

  cols.addEventListener('mousemove', function (e) {
    var bar = e.target.closest('.bar');
    if (!bar) { tip.classList.remove('show'); return; }
    var outer = box.getBoundingClientRect();
    var rect  = bar.getBoundingClientRect();
    tip.textContent = bar.dataset.tip;
    tip.style.left = (rect.left + rect.width / 2 - outer.left) + 'px';
    tip.style.top  = (rect.top - outer.top) + 'px';
    tip.classList.add('show');
  });
  cols.addEventListener('mouseleave', function () { tip.classList.remove('show'); });

  document.querySelectorAll('.period-group .btn').forEach(function (btn) {
    btn.addEventListener('click', function () {
      document.querySelectorAll('.period-group .btn').forEach(function (b) {
        b.classList.toggle('active', b === btn);
        b.setAttribute('aria-pressed', String(b === btn));
      });
      render(btn.dataset.period);
    });
  });

  render('7');
})();
